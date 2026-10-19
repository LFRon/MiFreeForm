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
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.model.resolveWindowSizePx
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import com.buildsession.betterYAMF.window.backend.WindowOperation
import com.buildsession.betterYAMF.window.core.CommandStatus
import com.buildsession.betterYAMF.window.core.RegionId
import com.buildsession.betterYAMF.window.core.SceneMetrics
import com.buildsession.betterYAMF.window.core.SceneRect
import com.buildsession.betterYAMF.window.core.SceneStyle
import com.buildsession.betterYAMF.window.core.WindowCommand
import com.buildsession.betterYAMF.window.core.WindowInputEvent
import com.buildsession.betterYAMF.window.core.WindowSceneController
import com.buildsession.betterYAMF.window.core.WindowSceneEffect
import com.buildsession.betterYAMF.window.core.WindowSceneEvent
import com.buildsession.betterYAMF.window.core.WindowRegistration
import com.buildsession.betterYAMF.window.core.WindowShellRegistry
import com.buildsession.betterYAMF.window.execution.LatestOperation
import com.buildsession.betterYAMF.xposed.hook.system.SurfaceFrameSynchronizer
import com.buildsession.betterYAMF.xposed.hook.system.EntrySnapshotTransport
import com.buildsession.betterYAMF.xposed.hook.system.SystemBarMetrics
import com.buildsession.betterYAMF.xposed.hook.system.ViewSurfaceOps
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.services.YAMFManager.config
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logError
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event/execution adapter. All geometry, mode, animation and position memory live in window/core.
 * The full-display transparent backing window stays fixed; its native touch region contains only
 * this shell. Each scene buffer and task presentation is committed through the same BLAST frame.
 */
@SuppressLint("ClickableViewAccessibility")
class AppWindow(
    val context: Context,
    private val onWindowInit: (WindowHost, Int) -> Unit
) : WindowHost {
    override val windowId = ids.getAndIncrement()
    override var currentTaskId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val events = ArrayDeque<WindowSceneEvent>()
    private var draining = false
    private var destroyed = false
    private var added = false
    private var gestureRegion: RegionId? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var requestedGeneration = -1L
    private var boosted = false
    private var dropZone: CornerDropZoneView? = null
    private var taskPrepared = false
    private var adoptionRequested = false
    private var stagedEntry = false
    private var frameResults = 0
    private var bridgeReleased = false
    private var liveRevealStarted = false
    private var liveRevealFrameId = Long.MAX_VALUE
    private var lastVisibility: Boolean? = null
    private val preparation = LatestOperation<WindowOperation> { it.cancel() }
    private var sourcePackage: String? = null
    private var sourceHandoffId = 0L
    private var sourceReleaseTimeout: Runnable? = null

    private val metrics = readMetrics()
    private val style = SceneStyle(
        context.resources.displayMetrics.density,
        config.windowRoundedCorner.toFloat(), config.barRoundedCorner.toFloat(), config.animationSpeed,
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f),
        config.coloredController
    )
    private val initialSize = config.resolveWindowSizePx(metrics.width, metrics.height)
    private val intentController = shellRegistry.register(WindowRegistration(windowId, style.density))
    private val scene = WindowSceneController(
        windowId, style, metrics,
        WindowSceneController.initialContent(metrics, style, initialSize.first, initialSize.second,
            if (metrics.height >= metrics.width) config.portraitY else config.landscapeY), intentController
    )
    private val view = WindowShellView(context, style)
    private val synchronizer = SurfaceFrameSynchronizer(view, view::touchRegion)
    private val params = overlayParams(Protocol.CHROME_WINDOW_TITLE + "#" + windowId)

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
            if (displayId == Display.DEFAULT_DISPLAY) enqueue(WindowSceneEvent.Metrics(readMetrics()))
        }
    }
    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            enqueue(WindowSceneEvent.ResetPosition)
        }
    }

    init {
        view.onPointer = ::onPointer
        view.render(scene.snapshot())
        runCatching {
            check(SurfaceFrameSynchronizer.configureOverlay(params)) { "Required shell window capabilities are unavailable" }
            check(synchronizer.attach()) { "Required frame/input synchronization is unavailable" }
            Instances.windowManager.addView(view, params)
            added = true
            displayManager.registerDisplayListener(displayListener, handler)
            context.registerReceiver(resetReceiver, IntentFilter(Protocol.ACTION_RESET_ALL_WINDOW), Context.RECEIVER_NOT_EXPORTED)
            onWindowInit(this, windowId)
        }.onFailure {
            log(TAG, "create shell failed", it)
            destroy(releaseTask = true, restoreToFront = true)
        }
    }

    /** Main-thread message pump prevents nested callbacks from reentering core or its effects. */
    private fun enqueue(event: WindowSceneEvent) {
        if (Looper.myLooper() != handler.looper) {
            handler.post { enqueue(event) }
            return
        }
        if (destroyed) return
        events.addLast(event)
        if (draining) return
        draining = true
        try {
            while (events.isNotEmpty() && !destroyed) {
                val next = events.removeFirst()
                scene.onEvent(next).forEach(::execute)
            }
            setFrameRateBoost(scene.prefersHighFrameRate())
        } catch (error: Throwable) {
            log(TAG, "scene event failed", error)
            destroy(releaseTask = true, restoreToFront = true)
        } finally {
            draining = false
        }
    }

    private fun input(event: WindowInputEvent) = enqueue(WindowSceneEvent.Input(event))

    private fun execute(effect: WindowSceneEffect) {
        if (destroyed) return
        when (effect) {
            is WindowSceneEffect.Prepare -> {
                val ticket = preparation.begin()
                val operation = YAMFManager.windowBackend.preparePresentation(
                    currentTaskId, effect.normalContent.rounded().toAndroidRect()
                ) { status ->
                    fun completePreparation() {
                        if (destroyed || !preparation.complete(ticket)) return
                        taskPrepared = status == CommandStatus.DONE
                        if (status == CommandStatus.FAILED) {
                            logError(TAG, "prepare failed window=$windowId task=$currentTaskId command=${effect.commandId} handoff=$sourceHandoffId")
                        } else {
                            log("reYAMF_EntryV2") { "task prepared status=$status task=$currentTaskId command=${effect.commandId} handoff=$sourceHandoffId" }
                        }
                        enqueue(WindowSceneEvent.Prepared(effect.commandId, status))
                    }
                    if (Looper.myLooper() == handler.looper) completePreparation()
                    else handler.post { completePreparation() }
                }
                preparation.attach(ticket, operation)
            }
            is WindowSceneEffect.Present -> {
                val bridge = view.hasEntryImage && !liveRevealStarted
                // The old image and new app have different layout aspects. Replace only at a
                // settled, prepared pose; never center-crop the new layout into the old card.
                val revealLive = bridge && taskPrepared && !scene.isAnimating() && effect.frame.alpha > 0f
                if (revealLive) {
                    liveRevealStarted = true
                    liveRevealFrameId = effect.frameId
                }
                view.render(effect.frame, showEntryImage = bridge && !revealLive)
                synchronizer.present({ transaction ->
                    if (!adoptionRequested && bridge) true
                    else YAMFManager.windowBackend.writePresentationInto(
                        transaction, currentTaskId, effect.frame.content.rounded().toAndroidRect(),
                        effect.frame.contentRadius, if (bridge && !revealLive) 0f else effect.frame.alpha
                    )
                }) { status ->
                    if (destroyed) return@present
                    if (effect.frameId >= liveRevealFrameId && status == CommandStatus.DONE && !bridgeReleased) {
                        bridgeReleased = true
                        view.releaseEntryImage()
                        log("reYAMF_EntryV2") { "live committed task=$currentTaskId handoff=$sourceHandoffId frame=${effect.frameId} content=${effect.frame.content}" }
                    }
                    if (status == CommandStatus.FAILED) {
                        logError(TAG, "frame commit failed window=$windowId task=$currentTaskId handoff=$sourceHandoffId frame=${effect.frameId}")
                    } else if (++frameResults <= 2) {
                        log("reYAMF_EntryV2") { "frame status=$status task=$currentTaskId handoff=$sourceHandoffId frame=${effect.frameId} bridge=$bridge alpha=${effect.frame.alpha} content=${effect.frame.content}" }
                    }
                    enqueue(WindowSceneEvent.FrameCommitted(effect.frameId, status))
                }
            }
            is WindowSceneEffect.RequestFrame -> requestFrame(effect.generation)
            is WindowSceneEffect.SourceHandoffResult -> reportSourceHandoff(effect.status)
            is WindowSceneEffect.SystemCommand -> executeSystem(effect.command)
            WindowSceneEffect.FinishClose -> onDestroy(releaseTask = true)
            is WindowSceneEffect.Failed -> {
                logError(TAG, "${effect.operation} failed window=$windowId task=$currentTaskId")
                destroy(releaseTask = true, restoreToFront = true)
            }
        }
    }

    private fun requestFrame(generation: Long) {
        if (frameCallback != null && requestedGeneration == generation) return
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        requestedGeneration = generation
        val callback = Choreographer.FrameCallback { timeNanos ->
            frameCallback = null
            enqueue(WindowSceneEvent.Tick(generation, timeNanos / 1_000_000L))
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun onPointer(event: MotionEvent) {
        if (destroyed) return
        runCatching {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                gestureRegion = view.frame?.hitTest(event.rawX, event.rawY, style)
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

    private fun executeSystem(command: WindowCommand) {
        when (command) {
            is WindowCommand.DropZoneShow -> showDropZone()
            is WindowCommand.DropHover -> dropZone?.render(command.hover, command.active)
            is WindowCommand.DropZoneHide -> dropZone?.visibility = View.GONE
            is WindowCommand.RequestFullscreen -> {
                if (YAMFManager.switchTaskToFullscreen(currentTaskId)) onDestroy(releaseTask = false)
                else enqueue(WindowSceneEvent.RestorePreview)
            }
            is WindowCommand.RaiseWindow -> {
                // Explicit user selection raises the child task; automatic Home maintenance
                // still respects the backend's always-on-top capability below.
                YAMFManager.windowBackend.bringTaskToFront(currentTaskId)
            }
            else -> Unit
        }
    }

    override fun markOpenAsMini(
        dropX: Int, dropY: Int, sourceBounds: android.graphics.Rect?, awaitSourceRelease: Boolean,
        sourcePackage: String?, handoffId: Long, entrySnapshot: EntrySnapshot?
    ) {
        val source = sourceBounds?.takeIf { it.width() > 0 && it.height() > 0 }?.let {
            SceneRect(it.left.toFloat(), it.top.toFloat(), it.width().toFloat(), it.height().toFloat())
        }
        this.sourcePackage = sourcePackage
        sourceHandoffId = handoffId
        stagedEntry = entrySnapshot != null
        enqueue(WindowSceneEvent.OpenAsMini(source, dropX, dropY, awaitSourceRelease,
            entrySnapshot?.cornerRadius ?: 0f, entrySnapshot?.velocityX ?: 0f, entrySnapshot?.velocityY ?: 0f))
        if (entrySnapshot != null) {
            view.retainEntryImage(EntrySnapshotTransport.consumeBitmap(entrySnapshot))
            enqueue(WindowSceneEvent.VisualAvailable)
        }
    }

    override fun openTask(): Boolean {
        if (destroyed) return false
        adoptionRequested = true
        val success = YAMFManager.switchToSmoothFreeform(currentTaskId, scene.normalViewport().rounded().toAndroidRect())
        if (!success) destroy(releaseTask = true, restoreToFront = true)
        return success
    }

    override fun onEntryCancelled(handoffId: Long) {
        if (handoffId == sourceHandoffId && stagedEntry) {
            log("reYAMF_EntryV2") { "cancel host task=$currentTaskId handoff=$handoffId adopted=$adoptionRequested" }
            destroy(releaseTask = adoptionRequested, restoreToFront = true)
        }
    }

    override fun onSourceReleased(handoffId: Long) {
        if (handoffId == sourceHandoffId) {
            sourceReleaseTimeout?.let(handler::removeCallbacks)
            sourceReleaseTimeout = null
            if (stagedEntry && !adoptionRequested && !YAMFManager.activateWindowTask(this)) return
            enqueue(WindowSceneEvent.SourceReleased)
        }
    }
    override fun onPresentationInvalidated() = enqueue(WindowSceneEvent.PresentationInvalidated)

    override fun onOrganizerLeash() {
        // Organizer calls can arrive while WM is still applying adoption. Enter on a fresh turn.
        handler.post { enqueue(WindowSceneEvent.TaskAvailable) }
    }

    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
        if (YAMFManager.windowBackend.isHomeTask(taskInfo)) keepAboveHome()
        else if (taskInfo.taskId == currentTaskId) onTaskDescriptionChanged(taskInfo)
    }

    override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {
        if (taskInfo.taskId == currentTaskId) enqueue(WindowSceneEvent.TaskColor(taskInfo.taskDescription?.backgroundColor ?: 0))
    }

    override fun onTaskVisibilityChanged(visible: Boolean) {
        fun applyVisibility() {
            if (destroyed || !taskPrepared || lastVisibility == visible) return
            // A hidden preparation is a resource phase, not a request to hide the user's bridge.
            // There is no delayed false capable of overriding a later native true.
            lastVisibility = visible
            log("reYAMF_EntryV2") { "visibility task=$currentTaskId handoff=$sourceHandoffId visible=$visible" }
            enqueue(WindowSceneEvent.Visibility(visible))
        }
        if (Looper.myLooper() == handler.looper) applyVisibility() else handler.post { applyVisibility() }
    }

    override fun onHomeTop() = keepAboveHome()

    private fun keepAboveHome() {
        if (destroyed || YAMFManager.windowBackend.isAlwaysOnTopActive()) return
        // Non-AOT capability fallback: never reorder while the user or recents owns a gesture.
        handler.post {
            if (!destroyed && currentTaskId > 0 && !YAMFManager.windowBackend.isTouchActive() &&
                SystemClock.uptimeMillis() >= YAMFManager.windowBackend.recentsGestureSuppressUntilMs()) {
                YAMFManager.windowBackend.bringTaskToFront(currentTaskId)
            }
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

    override fun dumpGeometry() {
        handler.post {
            log(TAG) { "GEOM window=$windowId task=$currentTaskId destroyed=$destroyed core=${scene.shellSnapshot()} frame=${scene.snapshot()}" }
        }
    }

    private fun reportSourceHandoff(status: CommandStatus) {
        sourceReleaseTimeout?.let(handler::removeCallbacks)
        sourceReleaseTimeout = null
        if (status == CommandStatus.DONE) {
            // A staged entry is NOT allowed to adopt on a missing acknowledgement. Legacy entries
            // already received native completion before creation and can release their frame wait.
            sourceReleaseTimeout = Runnable {
                sourceReleaseTimeout = null
                logError(TAG, "source acknowledgement timeout window=$windowId handoff=$sourceHandoffId")
                if (stagedEntry) destroy(releaseTask = adoptionRequested, restoreToFront = true)
                else enqueue(WindowSceneEvent.SourceReleased)
            }.also { handler.postDelayed(it, 5000L) } // LoS's surface-sync failure bound.
        }
        val destination = sourcePackage ?: run {
            if (status == CommandStatus.DONE) onSourceReleased(sourceHandoffId)
            return
        }
        runCatching {
            context.sendBroadcast(Intent(Protocol.ACTION_WINDOW_PRESENTED)
                .setPackage(destination).putExtra(Protocol.EXTRA_TASK_ID, currentTaskId)
                .putExtra(Protocol.EXTRA_HANDOFF_ID, sourceHandoffId)
                .putExtra(Protocol.EXTRA_COMMAND_STATUS, status.name))
        }.onFailure { log(TAG, "report source handoff failed", it) }
    }

    override fun onDestroy(releaseTask: Boolean) = destroy(releaseTask, restoreToFront = false)

    private fun destroy(releaseTask: Boolean, restoreToFront: Boolean) {
        if (Looper.myLooper() != handler.looper) {
            handler.post { destroy(releaseTask, restoreToFront) }
            return
        }
        if (destroyed) return
        scene.onEvent(WindowSceneEvent.Destroy).forEach(::execute)
        shellRegistry.unregister(intentController)
        destroyed = true
        preparation.cancel()
        events.clear()
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        handler.removeCallbacksAndMessages(null)
        sourceReleaseTimeout = null
        // Detach the full-display input window before removing its restricted touch-region
        // listener. Frame failures are posted out of ViewRoot traversal by the synchronizer.
        runCatching { if (added) Instances.windowManager.removeViewImmediate(view) }
            .onFailure { log(TAG, "remove shell failed", it) }
        added = false
        runCatching { synchronizer.dispose() }
        view.releaseEntryImage()
        runCatching { setFrameRateBoost(false) }
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { context.unregisterReceiver(resetReceiver) }
        if (releaseTask && currentTaskId > 0 && adoptionRequested) {
            // A failed entry restores the app. Only an intentional close sends it to the back.
            runCatching { YAMFManager.switchTaskToFullscreen(currentTaskId, toFront = restoreToFront) }
                .onFailure { log(TAG, "release failed", it) }
        }
        dropZone?.let { runCatching { Instances.windowManager.removeView(it) } }
        dropZone = null
        YAMFManager.unregisterHost(this)
    }

    private fun readMetrics(): SceneMetrics {
        val dm = DisplayMetrics()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        return SceneMetrics(dm.widthPixels, dm.heightPixels, SystemBarMetrics.statusBarHeightPx(), SystemBarMetrics.navigationBarHeightPx())
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
        private val ids = AtomicInteger(1)
        private val shellRegistry = WindowShellRegistry()
    }
}
