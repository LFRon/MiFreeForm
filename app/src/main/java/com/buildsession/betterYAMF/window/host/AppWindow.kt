package com.buildsession.betterYAMF.window.host

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import com.buildsession.betterYAMF.window.api.*
import com.buildsession.betterYAMF.window.runtime.WindowRuntimes
import com.buildsession.betterYAMF.window.session.WindowSessions
import com.buildsession.betterYAMF.xposed.adapters.common.system.SurfaceFrameSynchronizer
import com.buildsession.betterYAMF.xposed.adapters.common.system.EntrySnapshotTransport
import com.buildsession.betterYAMF.xposed.adapters.common.system.UserScopedBroadcasts
import com.buildsession.betterYAMF.xposed.adapters.common.system.SystemBarMetrics
import com.buildsession.betterYAMF.xposed.adapters.common.system.ViewSurfaceOps
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logError
import com.buildsession.betterYAMF.xposed.utils.logWarning
import java.util.concurrent.atomic.AtomicInteger

/** Android adapter / composition root. It owns native resources, never a second window controller. */
@SuppressLint("ClickableViewAccessibility")
class AppWindow(val context: Context, private val onWindowInit: (WindowHost, Int) -> Unit) : WindowHost {
    override val windowId = ids.getAndIncrement()
    override var currentTaskId = -1
        private set
    override fun bindTask(taskId: Int) {
        check(runtime.bindTask(taskId)) { "Task binding was rejected for window=$windowId task=$taskId" }
        currentTaskId = taskId
    }
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var destroyed = false
    private var added = false
    private var adoptionRequested = false
    private var fullscreenReleased = false
    private var gestureRegion: RegionId? = null
    private var boosted = false
    private var dropZone: CornerDropZoneView? = null
    private var iconPackage: String? = null
    private var sourcePackage: String? = null
    private var sourceHandoffId = 0L
    private var sourceEpoch = 0L
    private var sourceUserId = 0
    private var launcherRotation = -1
    private var frameResults = 0
    private var loggedFullscreenStage: FullscreenStage? = null
    private var entryVisualLogged: String? = null
    private var stagedEntry = false
    private val style = SceneStyle(context.resources.displayMetrics.density,
        config.windowRoundedCorner.toFloat(), config.barRoundedCorner.toFloat(), config.animationSpeed,
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f),
        config.coloredController)
    private val view = WindowShellView(context, style)
    private val synchronizer = SurfaceFrameSynchronizer(view, view::touchRegion)
    private val params = overlayParams(Protocol.CHROME_WINDOW_TITLE + "#" + windowId)

    private val scheduler = AndroidRuntimeScheduler(handler)

    private val runtime: WindowRuntimeHandle = WindowRuntimes.createWindow(
        SessionKey(processEpoch, windowId), WindowOptions(style, config.defaultWindowWidth,
            config.defaultWindowHeight, config.portraitY, config.landscapeY), readMetrics(), scheduler,
        WindowPorts(object : TaskSessionPort {
            override fun existingViewport(): WindowRect? =
                YAMFManager.windowBackend.existingLogicalViewport(currentTaskId)?.toWindowRect()
            override fun adopt(layout: TaskLayout): Boolean {
                adoptionRequested = true
                return YAMFManager.switchToSmoothFreeform(currentTaskId,
                    layout.normalContent.rounded().toAndroidRect(), layout.logicalViewport.toAndroidRect())
            }
            override fun prepare(commandId: Long, layout: TaskLayout, result: (CommandStatus) -> Unit): WindowOperation {
                updateApplicationIcon()
                return YAMFManager.windowBackend.preparePresentation(currentTaskId,
                    layout.normalContent.rounded().toAndroidRect()) { status ->
                    if (status == CommandStatus.FAILED) logError(TAG, "prepare failed window=$windowId task=$currentTaskId command=$commandId")
                    else log("reYAMF_EntryV2") { "task prepared status=$status task=$currentTaskId command=$commandId handoff=$sourceHandoffId" }
                    result(status)
                }
            }
            override fun handoffFullscreen(commandId: Long, released: () -> Unit,
                result: (FullscreenHandoffResult) -> Unit): WindowOperation =
                YAMFManager.windowBackend.handoffFullscreen(currentTaskId, released) { value ->
                    if (value.status != CommandStatus.DONE)
                        logWarning(TAG, "fullscreen handoff result=$value window=$windowId task=$currentTaskId command=$commandId")
                    result(value)
                }
            override fun raise() = YAMFManager.windowBackend.bringTaskToFront(currentTaskId)
        }, object : ScenePresenter {
            override fun seed(frame: WindowSceneFrame) = view.render(frame)
            override fun present(submission: FrameSubmission, result: (CommandStatus) -> Unit) {
                val frame = submission.frame
                if (BuildConfig.DEBUG && stagedEntry) {
                    val visual = when { frame.taskAlpha <= 0f -> "source"; frame.showEntryImage -> "replacing"; else -> "live" }
                    if (visual != entryVisualLogged) {
                        entryVisualLogged = visual
                        log("reYAMF_EntryV2") { "visual=$visual task=$currentTaskId handoff=$sourceHandoffId frame=${submission.frameId} sourceClip=${frame.entryImage?.clip} viewport=${frame.content}" }
                    }
                }
                if (BuildConfig.DEBUG && loggedFullscreenStage != frame.fullscreen?.stage) {
                    loggedFullscreenStage = frame.fullscreen?.stage
                    log(TAG) { "fullscreen stage=$loggedFullscreenStage window=$windowId frame=${submission.frameId}" }
                }
                view.render(frame)
                synchronizer.present({ tx ->
                    if (!frame.presentsLiveContent || !adoptionRequested && frame.showEntryImage) true
                    else YAMFManager.windowBackend.writePresentationInto(tx, currentTaskId,
                        frame.content.rounded().toAndroidRect(), frame.contentRadius, frame.taskAlpha, frame.contentAlignment)
                }) { status ->
                    if (status == CommandStatus.FAILED) logError(TAG, "frame commit failed window=$windowId frame=${submission.frameId}")
                    else if (BuildConfig.DEBUG && ++frameResults <= 2) log("reYAMF_EntryV2") { "frame status=$status task=$currentTaskId handoff=$sourceHandoffId frame=${submission.frameId}" }
                    result(status)
                }
            }
            override fun highFrameRate(enabled: Boolean) = setFrameRateBoost(enabled)
            override fun dropTarget(visual: DropTargetVisual?) {
                if (visual == null) dropZone?.visibility = View.GONE
                else { showDropZone(); dropZone?.render(visual) }
            }
            override fun releaseEntryImage() { view.releaseEntryImage() }
        }, object : SourceHandoffPort {
            override fun acknowledge(status: CommandStatus) {
                val target = sourcePackage ?: return
                runCatching {
                    UserScopedBroadcasts.send(context, Intent(Protocol.ACTION_WINDOW_PRESENTED).setPackage(target)
                        .putExtra(Protocol.EXTRA_TASK_ID, currentTaskId).putExtra(Protocol.EXTRA_HANDOFF_ID, sourceHandoffId)
                        .putExtra(Protocol.EXTRA_COMMAND_STATUS, status.name)
                        .putExtra(Protocol.EXTRA_SOURCE_EPOCH, sourceEpoch), sourceUserId)
                }.onFailure { log(TAG, "report source handoff failed", it) }
            }
            override fun awaitDestination(result: (DestinationResult) -> Unit): WindowOperation {
                var finished = false
                var callback: Choreographer.FrameCallback? = null
                fun finish(value: DestinationResult) {
                    if (finished) return
                    finished = true
                    callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
                    callback = null
                    result(value)
                }
                fun poll() {
                    if (finished) return
                    if (destroyed) { finish(DestinationResult.Superseded); return }
                    try {
                        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
                        val aligned = rotation in 0..3 && (launcherRotation < 0 || rotation == launcherRotation)
                        if (aligned && YAMFManager.windowBackend.isTaskSurfaceStable(currentTaskId))
                            finish(DestinationResult.Done(readMetrics()))
                        else {
                            callback = Choreographer.FrameCallback { callback = null; poll() }
                                .also { Choreographer.getInstance().postFrameCallback(it) }
                        }
                    } catch (error: Throwable) {
                        logError(TAG, "destination observation failed window=$windowId task=$currentTaskId handoff=$sourceHandoffId", error)
                        finish(DestinationResult.Failed)
                    }
                }
                poll()
                return WindowOperation { finish(DestinationResult.Superseded) }
            }
        }, object : WindowLifecyclePort {
            override fun taskReleased() {
                fullscreenReleased = true
                YAMFManager.onHostTaskReleased(this@AppWindow)
            }
            override fun failed(reason: WindowFailure, cause: Throwable?) {
                logError(TAG, "runtime failure=$reason window=$windowId task=$currentTaskId", cause)
            }
            override fun finish(releaseTask: Boolean, restoreToFront: Boolean) = removeNativeHost(releaseTask, restoreToFront)
        }))

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val region = gestureRegion ?: return false
            input(WindowInputEvent.Move(windowId, e2.eventTime, region, e2.rawX, e2.rawY))
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val region = gestureRegion ?: return false
            // mini has no double-tap action; do not add GestureDetector's confirmation delay.
            input(WindowInputEvent.TapConfirmed(windowId, e.eventTime, region, e.rawX, e.rawY))
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1?.isFromSource(InputDevice.SOURCE_MOUSE) == true) return false
            input(WindowInputEvent.Fling(windowId, e2.eventTime, velocityX, velocityY))
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) runtime.post(WindowEvent.Metrics(readMetrics()))
        }
    }
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { runtime.post(WindowEvent.ResetPosition) }
    }
    init {
        view.onPointer = ::onPointer
        runCatching {
            check(SurfaceFrameSynchronizer.configureOverlay(params)) { "Required shell window capabilities are unavailable" }
            check(synchronizer.attach()) { "Required frame/input synchronization is unavailable" }
            Instances.windowManager.addView(view, params)
            added = true
            displayManager.registerDisplayListener(displayListener, handler)
            context.registerReceiver(resetReceiver, IntentFilter(Protocol.ACTION_RESET_ALL_WINDOW), Context.RECEIVER_NOT_EXPORTED)
            onWindowInit(this, windowId)
        }.onFailure { log(TAG, "create shell failed", it); runtime.close(true, true) }
    }
    private fun input(event: WindowInputEvent) = runtime.post(WindowEvent.Input(event))

    private fun onPointer(event: MotionEvent) {
        if (destroyed) return
        runCatching {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureRegion = view.frame?.input?.regionAt(event.rawX, event.rawY)
                val region = gestureRegion ?: return
                input(WindowInputEvent.Press(windowId, event.eventTime, region, event.rawX, event.rawY))
            }
            val region = gestureRegion ?: return
            val moving = region == RegionId.BODY_MASK || region == RegionId.HANDLE
            if (moving) detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (!moving)
                    input(WindowInputEvent.Move(windowId, event.eventTime, region, event.rawX, event.rawY))
                MotionEvent.ACTION_UP -> {
                    input(WindowInputEvent.Up(windowId, event.eventTime, region, event.rawX, event.rawY))
                    gestureRegion = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    input(WindowInputEvent.Cancel(windowId, event.eventTime, region, event.rawX, event.rawY))
                    gestureRegion = null
                }
            }
        }.onFailure { log(TAG, "shell input failed", it) }
    }

    override fun markOpenAsMini(dropX: Int, dropY: Int, sourceBounds: android.graphics.Rect?, awaitSourceRelease: Boolean,
        sourcePackage: String?, handoffId: Long, entrySnapshot: EntrySnapshot?, launcherRotation: Int, sourceEpoch: Long, sourceUserId: Int) {
        this.sourcePackage = sourcePackage
        this.sourceEpoch = sourceEpoch
        this.sourceUserId = sourceUserId
        sourceHandoffId = handoffId
        this.launcherRotation = launcherRotation.takeIf { it in 0..3 } ?: -1
        stagedEntry = entrySnapshot != null
        val source = sourceBounds?.takeIf { !it.isEmpty }?.let { SceneRect(it.left.toFloat(), it.top.toFloat(), it.width().toFloat(), it.height().toFloat()) }
        runtime.post(WindowEvent.Entry(EntryRequest(handoffId, source, dropX, dropY, awaitSourceRelease,
            stagedEntry, sourcePackage != null, entrySnapshot?.cornerRadius ?: 0f,
            entrySnapshot?.velocityX ?: 0f, entrySnapshot?.velocityY ?: 0f,
            entrySnapshot?.let { it.buffer.width.toFloat() / it.buffer.height })))
        if (runtime.destroyed) { entrySnapshot?.close(); return }
        // Register the handoff before wrapping native pixels, so a wrap failure also retires
        // the source gate through normal session cleanup instead of waiting for its watchdog.
        val image = entrySnapshot?.let(EntrySnapshotTransport::consumeBitmap)
        if (image != null) { view.retainEntryImage(image); runtime.post(WindowEvent.VisualAvailable) }
    }
    override fun openTask() = runtime.activateTask()
    override fun onEntryCancelled(handoffId: Long) = runtime.post(WindowEvent.EntryCancelled(handoffId))
    override fun onSourceReleased(handoffId: Long) = runtime.post(WindowEvent.SourceReleased(handoffId))
    override fun onPresentationInvalidated() = runtime.post(WindowEvent.PresentationInvalidated)
    override fun onTaskOwnershipLost() = runtime.post(WindowEvent.TaskOwnershipLost)
    override fun onOrganizerLeash() { handler.post { runtime.post(WindowEvent.TaskAvailable) } }
    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        if (YAMFManager.windowBackend.isHomeTask(taskInfo)) keepAboveHome()
        else if (taskInfo.taskId == currentTaskId) onTaskDescriptionChanged(taskInfo)
    }
    override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.taskId == currentTaskId) {
            updateApplicationIcon(taskInfo.topActivity?.packageName)
            runtime.post(WindowEvent.TaskColor(taskInfo.taskDescription?.backgroundColor ?: 0))
        }
    }
    private fun updateApplicationIcon(packageName: String? = WindowSessions.taskActivities[currentTaskId]?.packageName) {
        if (packageName == null || packageName == iconPackage) return
        runCatching { context.packageManager.getApplicationIcon(packageName) }.onSuccess {
            iconPackage = packageName; view.setApplicationIcon(it)
        }.onFailure { logWarning(TAG, "application icon unavailable task=$currentTaskId package=$packageName", it) }
    }
    override fun onTaskVisibilityChanged(visible: Boolean) = runtime.post(WindowEvent.Visibility(visible))
    override fun onHomeTop() = keepAboveHome()
    private fun keepAboveHome() {
        handler.post {
            if (!destroyed) runtime.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(
                YAMFManager.windowBackend.isTaskManaged(currentTaskId),
                YAMFManager.windowBackend.isAlwaysOnTopActive(), YAMFManager.windowBackend.isTouchActive(),
                SystemClock.uptimeMillis() < YAMFManager.windowBackend.recentsGestureSuppressUntilMs())))
        }
    }
    private fun showDropZone() {
        if (dropZone == null) {
            val overlay = CornerDropZoneView(context)
            val lp = overlayParams(Protocol.CHROME_WINDOW_TITLE + "_drop#$windowId").apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (!SurfaceFrameSynchronizer.configureOverlay(lp)) return
            Instances.windowManager.addView(overlay, lp)
            dropZone = overlay
        }
        dropZone?.visibility = View.VISIBLE
    }

    private fun setFrameRateBoost(enable: Boolean) {
        if (boosted == enable || !added) return
        val maxHz = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
        if (ViewSurfaceOps.setViewSurfaceFrameRate(view, maxHz, enable)) boosted = enable
    }

    override fun dumpGeometry() { handler.post { log(TAG) { "GEOM session=${runtime.session} task=$currentTaskId destroyed=$destroyed frame=${runtime.snapshot()}" } } }
    override fun onDestroy(releaseTask: Boolean) = runtime.close(releaseTask)
    private fun removeNativeHost(releaseTask: Boolean, restoreToFront: Boolean) {
        if (destroyed) return
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        // Remove the input window before disposing its restricted-region listener.
        runCatching { if (added) Instances.windowManager.removeViewImmediate(view) }.onFailure { log(TAG, "remove shell failed", it) }
        runCatching { setFrameRateBoost(false) }
        added = false
        runCatching { synchronizer.dispose() }
        view.releaseEntryImage()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { context.unregisterReceiver(resetReceiver) }
        if (releaseTask && !fullscreenReleased && currentTaskId > 0 && adoptionRequested) {
            runCatching { YAMFManager.switchTaskToFullscreen(currentTaskId, restoreToFront) }.onFailure { log(TAG, "release failed", it) }
        }
        dropZone?.let { runCatching { Instances.windowManager.removeViewImmediate(it) } }
        dropZone = null
        YAMFManager.unregisterHost(this)
    }
    private fun readMetrics(): SceneMetrics {
        val dm = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        return SceneMetrics(dm.widthPixels, dm.heightPixels, SystemBarMetrics.statusBarHeightPx(),
            SystemBarMetrics.navigationBarHeightPx(), Display.DEFAULT_DISPLAY,
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0)
    }

    private fun overlayParams(title: String) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        windowAnimations = 0
        setTitle(title)
    }

    companion object {
        const val TAG = "reYAMF_AppWindow"
        private val processEpoch = SystemClock.elapsedRealtimeNanos()
        private val ids = AtomicInteger(1)
    }
}
