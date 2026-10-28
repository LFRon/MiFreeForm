package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.annotation.SuppressLint
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

/** Quickstep mechanism adapter. Native references remain confined to this input invocation. */
internal class LauncherGestureAdapter(private val native: QuickstepNativeAccess,
    private val nativeDownAdmission: Boolean = false) {
    class PreparedInput internal constructor(internal val owner: Any, internal val event: MotionEvent,
        internal val value: EntryInput, internal val gestureEpoch: Long, internal val lifecycle: Long,
        internal val nativeDown: LauncherInputOwnership.Down?)
    private data class Invocation(val owner: Any, val event: MotionEvent)
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
    private var trackedSwipe: Any? = null
    private var lastCandidate: EntryCandidate? = null
    private val inputOwnership = LauncherInputOwnership()
    private var inputEnabled = false
    private var environmentCurrent = false

    fun acceptsInput(owner: Any): Boolean = inputEnabled && environmentCurrent && inputOwnership.accepts(owner)

    /** Platform role change, delivered on main by the event-driven environment monitor.
     * Revoke the old gesture; returning Home requires a fresh DOWN, never a continued MOVE.
     */
    fun setEnvironmentCurrent(current: Boolean) {
        if (environmentCurrent == current) return
        environmentCurrent = current
        ++epoch
        if (!current) disposeHandoffs()
        cancel()
    }

    private val entry = LauncherEntries.create(AndroidRuntimeScheduler(main), object : LauncherEntryPort {
        override val capabilities get() = LauncherEntryCapabilities(
            RecentsDropHandoff.bridgeAvailable(), RecentsDropHandoff.legacyAvailable())
        override fun candidate(): EntryCandidate? {
            val current = invocation ?: return null
            val gesture = native.gestureStateOf(current.owner, current.event) ?: return null
            val id = native.taskId(gesture)
            val consumer = native.activeConsumerOf(current.owner, current.event)
            val ownsInput = !nativeDownAdmission ||
                inputOwnership.owns(current.owner, gesture, native.consumerGestureOf(consumer))
            val swipe = consumer?.let(native::swipeOf)
            val tracking = when {
                !ownsInput -> EntryTracking.REJECTED
                trackedSwipe != null && trackedSwipe !== swipe -> EntryTracking.REJECTED
                swipe != null -> {
                    trackedSwipe = swipe
                    if (native.isTracking(swipe)) EntryTracking.TRACKING else EntryTracking.WAITING
                }
                else -> EntryTracking.WAITING
            }
            view?.sourcePose = swipe?.takeIf { tracking != EntryTracking.REJECTED }
                ?.let { handler -> { native.sources.peekPose(handler, id) } }
            return EntryCandidate(id, native.isHome(gesture), tracking).also { candidate ->
                if (com.buildsession.betterYAMF.BuildConfig.DEBUG && candidate != lastCandidate) {
                    lastCandidate = candidate
                    log(TAG) { "candidate gesture=$epoch display=${native.displayIdOf(current.event)} facts=$candidate caps=$capabilities" }
                }
            }
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
            val handoff = RecentsDropHandoff.prepare(native, current.owner, current.event, drop.taskId)
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
    fun create(context: Context, inputOwner: Any) {
        application = context.applicationContext
        inputOwnership.bind(inputOwner)
        inputEnabled = true
        RecentsDropHandoff.install(context, native)
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
                log(TAG) { "input ready owner=${inputOwner.javaClass.name} nativeDown=$nativeDownAdmission display=${actual.width}x${actual.height} rotation=${actual.rotation} retained=${RecentsDropHandoff.bridgeAvailable()} legacy=${RecentsDropHandoff.legacyAvailable()}" }
            }.onFailure {
                view = null; overlay?.close(); overlay = null
                logWarning(TAG, "Drop overlay unavailable; preserve native gesture", it)
            }
        }
    }

    /** Snapshot coordinates BEFORE Quickstep can rotate the borrowed MotionEvent. */
    fun prepareInput(receiver: Any, event: MotionEvent): PreparedInput? {
        if (!acceptsInput(receiver)) return null
        // This phone presenter belongs to the default display. A different display's monitor
        // must not reset this gesture or present its target on the primary screen.
        if (native.displayIdOf(event) != overlay?.displayId) return null
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerPhase.DOWN
            MotionEvent.ACTION_MOVE -> PointerPhase.MOVE
            MotionEvent.ACTION_UP -> PointerPhase.UP
            MotionEvent.ACTION_CANCEL -> PointerPhase.CANCEL
            else -> return null
        }
        var nativeDown: LauncherInputOwnership.Down? = null
        if (phase == PointerPhase.DOWN) {
            ++epoch
            trackedSwipe = null
            lastCandidate = null
            // Retire old source leases before native DOWN can reuse StateManager.
            RecentsDropHandoff.onGestureStarted(native.displayIdOf(event))
            if (nativeDownAdmission)
                nativeDown = inputOwnership.beforeDown(receiver, native.gestureStateOf(receiver, event))
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
        return PreparedInput(receiver, event, EntryInput(phase, point.x, point.y, downPoint.y,
            actual.width, actual.height, native.displayIdOf(event), actual.rotation, event.eventTime), epoch, lifecycleEpoch, nativeDown)
    }

    /** MOVE and an opted-in native DOWN are evaluated after native recognition, using coordinates
     * sampled before dispatch. UP stays before dispatch so only its final decision is deferred.
     */
    fun input(prepared: PreparedInput, navigationStart: Boolean? = null): RecentsDropHandoff.Handoff? {
        if (prepared.gestureEpoch != epoch || prepared.lifecycle != lifecycleEpoch) return null
        check(invocation == null) { "Nested native input invocation" }
        invocation = Invocation(prepared.owner, prepared.event)
        armedHandoff = null
        try {
            entry.onInput(if (navigationStart == null) prepared.value else
                prepared.value.copy(navigationStart = navigationStart))
            return armedHandoff
        } catch (error: Throwable) {
            // A gate already armed must still receive the native UP completion/error callback.
            val armed = armedHandoff
            cancel()
            logWarning(TAG, "Entry adapter failed; preserve native input cleanup", error)
            return armed
        } finally { invocation = null; armedHandoff = null }
    }

    fun inputFinished(prepared: PreparedInput, handoff: RecentsDropHandoff.Handoff?, error: Throwable?) {
        handoff?.inputFinished(error, allowLegacy = error == null && prepared.value.phase == PointerPhase.UP)
        if (prepared.gestureEpoch != epoch || prepared.lifecycle != lifecycleEpoch) return
        if (error != null || prepared.event.actionMasked == MotionEvent.ACTION_CANCEL) cancel()
        else if (prepared.value.phase == PointerPhase.DOWN && nativeDownAdmission) {
            // Pixel 907 creates a fresh GestureState in its native DOWN and passes that exact
            // object to OtherActivityInputConsumer. The accepted region belongs to Quickstep;
            // it can be wider than five percent of a short/rotated display.
            val gesture = native.gestureStateOf(prepared.owner, prepared.event)
            val consumer = native.activeConsumerOf(prepared.owner, prepared.event)
            val admitted = prepared.nativeDown?.let {
                inputOwnership.afterDown(it, gesture, native.consumerGestureOf(consumer))
            } == true
            log(TAG) { "native DOWN gesture=$epoch admitted=$admitted display=${prepared.value.displayId} consumer=${consumer?.javaClass?.name} start=(${prepared.value.x},${prepared.value.y})" }
            input(prepared, admitted)
        } else if (prepared.value.phase == PointerPhase.MOVE) input(prepared)
        if (prepared.value.phase == PointerPhase.UP || prepared.value.phase == PointerPhase.CANCEL) {
            trackedSwipe = null
            inputOwnership.clearGesture()
        }
    }

    /** Keep platform input delivery intact when this binding fails. Retired handlers cannot
     * cancel or revive the current component; initialization of a new service can recover.
     */
    fun inputFailed(prepared: PreparedInput, error: Throwable) {
        if (prepared.gestureEpoch != epoch || prepared.lifecycle != lifecycleEpoch) return
        inputFailed(prepared.owner, error)
    }

    fun inputFailed(owner: Any, error: Throwable) {
        if (!acceptsInput(owner)) return
        inputEnabled = false
        inputOwnership.close()
        ++epoch
        disposeHandoffs()
        cancel()
        logWarning(TAG, "native input binding failed; entry disabled for this component", error)
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
        trackedSwipe = null
        inputOwnership.clearGesture()
        runCatching { entry.cancel() }.onFailure { logWarning(TAG, "entry cancellation failed", it) }
        view?.visibility = View.GONE
        view?.sourcePose = null
    }
    private fun disposeHandoffs() {
        runCatching { RecentsDropHandoff.dispose() }
            .onFailure { logWarning(TAG, "native handoff disposal failed", it) }
    }

    fun destroy() {
        inputEnabled = false
        environmentCurrent = false
        inputOwnership.close()
        val owner = ++lifecycleEpoch
        ++epoch
        disposeHandoffs()
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
