package com.buildsession.betterYAMF.xposed.hook.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.window.api.*
import com.buildsession.betterYAMF.window.host.AndroidRuntimeScheduler
import com.buildsession.betterYAMF.window.runtime.LauncherEntries
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import de.robv.android.xposed.XposedHelpers

/** Quickstep mechanism adapter. Native references remain confined to this input invocation. */
internal class LauncherGestureAdapter {
    private data class Invocation(val anchor: LauncherInputAnchor, val owner: Any, val event: MotionEvent)
    private val main = Handler(Looper.getMainLooper())
    private var application: Context? = null
    private var overlay: LauncherOverlayDisplay? = null
    private var view: RecentsDropZoneView? = null
    private var invocation: Invocation? = null
    private var armedHandoff: RecentsDropHandoff.Handoff? = null
    private var epoch = 0L
    private var lifecycleEpoch = 0L
    private var downWidth = 0
    private var downHeight = 0
    private var downRotation = 0

    private val entry = LauncherEntries.create(AndroidRuntimeScheduler(main), object : LauncherEntryPort {
        override val capabilities get() = LauncherEntryCapabilities(RecentsDropHandoff.bridgeAvailable(), true)
        override fun candidate(): EntryCandidate? {
            val current = invocation ?: return null
            val gesture = current.anchor.gestureStateOf(current.owner, current.event) ?: return null
            val id = XposedHelpers.callMethod(gesture, "getTopRunningTaskId") as Int
            val running = XposedHelpers.callMethod(gesture, "getRunningTask")
            val home = running != null && XposedHelpers.callMethod(running, "isHomeTask") == true
            val resumed = runCatching {
                val thread = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread")
                val activities = XposedHelpers.getObjectField(thread, "mActivities") as Map<*, *>
                activities.values.any { record ->
                    val activity = XposedHelpers.getObjectField(record, "activity") as? Activity
                    activity != null && !activity.isFinishing && !XposedHelpers.getBooleanField(record, "paused")
                }
            }.getOrDefault(false)
            val swipe = runCatching {
                val consumer = current.anchor.inputConsumerOf(current.owner, current.event)
                val active = XposedHelpers.callMethod(consumer, "getActiveConsumerInHierarchy") ?: consumer
                XposedHelpers.getObjectField(active, "mInteractionHandler")
            }.getOrNull()
            view?.sourcePose = swipe?.let { handler -> { RecentsEntryCapture.peekPose(handler, id) } }
            return EntryCandidate(id, home, resumed, RecentsDropHandoff.isSourceVisible(gesture))
        }
        override fun present(frame: EntryTargetFrame) {
            layout(frame.bounds)
            view?.setActive(frame.active)
            view?.visibility = if (frame.visible) View.VISIBLE else View.GONE
            if (!frame.visible) view?.sourcePose = null
        }
        override fun beginRetained(drop: EntryDrop): Boolean {
            val current = invocation ?: return false
            val context = application ?: return false
            val handoff = RecentsDropHandoff.prepare(current.anchor, current.owner, current.event, drop.taskId)
            if (handoff?.begin(context, request(drop)) != true) return false
            armedHandoff = handoff
            return true
        }
        override fun queueLegacy(drop: EntryDrop) {
            val owner = epoch
            main.post { if (owner == epoch) application?.sendBroadcast(request(drop)) }
        }
        override fun suppressNativeGesture(durationMs: Long) {
            application?.sendBroadcast(Intent(Protocol.ACTION_RECENTS_GESTURE).setPackage("android")
                .putExtra(Protocol.EXTRA_SUPPRESS_MS, durationMs))
        }
    })

    @SuppressLint("RtlHardcoded") // Physical upper-right, matching CORE's display coordinates in every locale.
    fun create(context: Context) {
        application = context.applicationContext
        RecentsDropHandoff.install(context)
        ManagedTaskFilter.ensureReceiverRegistered(context)
        val owner = ++lifecycleEpoch
        main.post {
            if (owner != lifecycleEpoch) return@post
            runCatching {
                val display = overlay ?: LauncherOverlayDisplay(context).also { overlay = it }
                val actual = checkNotNull(display.frame())
                val zone = entry.targetBounds(actual.width, actual.height)
                if (view == null) {
                    val target = RecentsDropZoneView(display.context).apply { visibility = View.GONE }
                    val lp = WindowManager.LayoutParams().apply {
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        format = PixelFormat.TRANSLUCENT
                        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        width = zone.width; height = zone.height
                        gravity = Gravity.TOP or Gravity.RIGHT
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                    display.windowManager.addView(target, lp)
                    view = target
                }
            }.onFailure {
                view = null; overlay?.close(); overlay = null
                logWarning(TAG, "Drop overlay unavailable; preserve native gesture", it)
            }
        }
    }

    fun input(anchor: LauncherInputAnchor, receiver: Any, event: MotionEvent): RecentsDropHandoff.Handoff? {
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerPhase.DOWN
            MotionEvent.ACTION_MOVE -> PointerPhase.MOVE
            MotionEvent.ACTION_UP -> PointerPhase.UP
            MotionEvent.ACTION_CANCEL -> PointerPhase.CANCEL
            else -> return null
        }
        if (phase == PointerPhase.DOWN) {
            ++epoch
            // Retire old source leases before native DOWN can reuse StateManager.
            RecentsDropHandoff.onGestureStarted(XposedHelpers.callMethod(event, "getDisplayId") as Int)
        }
        val actual = overlay?.frame()?.takeIf { view != null } ?: run { cancel(); return null }
        if (phase == PointerPhase.DOWN) {
            downWidth = actual.width; downHeight = actual.height; downRotation = actual.rotation
        }
        if (downWidth <= 0 || downHeight <= 0) return null
        val point = LauncherMotionCoordinates.point(event, actual.rotation, actual.width, actual.height)
            ?: run { cancel(); return null }
        val downPoint = LauncherCoordinateMapper.rotatePoint(point.x, point.y, actual.rotation,
            downRotation, downWidth, downHeight)
        check(invocation == null) { "Nested native input invocation" }
        invocation = Invocation(anchor, receiver, event)
        armedHandoff = null
        try {
            entry.onInput(EntryInput(phase, point.x, point.y, downPoint.y, actual.width, actual.height,
                XposedHelpers.callMethod(event, "getDisplayId") as Int, actual.rotation, event.eventTime))
            return armedHandoff
        } catch (error: Throwable) {
            // A gate already armed must still receive the native UP completion/error callback.
            val armed = armedHandoff
            cancel()
            logWarning(TAG, "Entry adapter failed; preserve native input cleanup", error)
            return armed
        } finally { invocation = null; armedHandoff = null }
    }

    private fun request(drop: EntryDrop) = Intent(Protocol.ACTION_OPEN_IN_YAMF).setPackage("android")
        .putExtra(Protocol.EXTRA_TASK_ID, drop.taskId).putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_RECENT)
        .putExtra(Protocol.EXTRA_FROM_DROP_ZONE, true).putExtra(Protocol.EXTRA_DROP_X, drop.x)
        .putExtra(Protocol.EXTRA_DROP_Y, drop.y)

    private fun layout(zone: WindowRect) {
        val target = view ?: return
        val lp = target.layoutParams as? WindowManager.LayoutParams ?: return
        if (lp.width != zone.width || lp.height != zone.height) {
            lp.width = zone.width; lp.height = zone.height
            overlay?.windowManager?.updateViewLayout(target, lp)
        }
    }

    private fun cancel() {
        runCatching { entry.cancel() }.onFailure { logWarning(TAG, "entry cancellation failed", it) }
        view?.visibility = View.GONE
        view?.sourcePose = null
    }
    fun destroy() {
        val owner = ++lifecycleEpoch
        ++epoch
        RecentsDropHandoff.dispose()
        cancel()
        main.post {
            if (owner != lifecycleEpoch) return@post
            view?.let { runCatching { overlay?.windowManager?.removeViewImmediate(it) }
                .onFailure { error -> log(TAG, "remove drop target failed", error) } }
            view = null; overlay?.close(); overlay = null; application = null
        }
    }
    companion object { private const val TAG = "reYAMF_LauncherAdapter" }
}
