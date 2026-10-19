package com.buildsession.betterYAMF.xposed.hook.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import com.buildsession.betterYAMF.window.core.CommandStatus
import com.buildsession.betterYAMF.xposed.hook.system.EntrySnapshotTransport
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.TRUSTED_SENDER_PERMISSION
import com.buildsession.betterYAMF.xposed.utils.logError
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Native ownership: retained source buffer -> destination bridge commit -> captured native finish
 * -> retire this Launcher -> adopt the live task. A rectangle alone is never a visual handoff.
 */
internal object RecentsDropHandoff {
    private const val TAG = "reYAMF_EntryV2"
    private const val FAILURE_TIMEOUT_MS = 5000L // LoS BLASTSyncEngine failure bound, no start delay.
    private val handler = Handler(Looper.getMainLooper())
    private var installed = false
    private var bridgeSupported = false
    private var application: Context? = null
    private val owners = HandoffOwners<Pending>()
    private val hookedEnds = mutableSetOf<Method>()

    private class Pending(val handoff: Handoff, val context: Context, val intent: Intent) {
        val gestureEnd = DeferredGestureEnd()
        val armedAt = SystemClock.elapsedRealtime()
        var endReceived = false
        var pixels: EntrySnapshotTransport.Lease? = null
        var nativeFinishRequested = false
        var nativeFinished = false
        var cancelDestination = false
        var sourceRetired: CommandStatus? = null
        var timeout: Runnable? = null
    }

    fun install(context: Context) {
        if (installed) return
        application = context.applicationContext
        // Register before gestures warm up the native producer. UP only uses the cached probe;
        // it must not be the first occasion on which the transform methods are hooked.
        runCatching { RecentsSourceFrame.install(context.classLoader) }
            .onFailure { logWarning(TAG, "native source synchronization capability unavailable", it) }
        context.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Protocol.ACTION_ENTRY_CAPABILITIES) {
                    bridgeSupported = intent.getIntExtra(Protocol.EXTRA_ENTRY_PROTOCOL, 0) == Protocol.ENTRY_PROTOCOL
                    log(TAG) { "backend protocol ready=$bridgeSupported" }
                    return
                }
                val taskId = intent.getIntExtra(Protocol.EXTRA_TASK_ID, -1)
                val id = intent.getLongExtra(Protocol.EXTRA_HANDOFF_ID, 0L)
                val owner = owners.all().firstOrNull { it.value.handoff.taskId == taskId && it.value.handoff.id == id } ?: return
                val status = intent.getStringExtra(Protocol.EXTRA_COMMAND_STATUS)
                    ?.let { runCatching { CommandStatus.valueOf(it) }.getOrNull() } ?: CommandStatus.FAILED
                if (status == CommandStatus.DONE) {
                    if (!owner.value.nativeFinishRequested) {
                        log(TAG) { "bridge committed task=$taskId handoff=$id; request native finish" }
                        requestNativeFinish(owner)
                    }
                } else if (!owner.value.nativeFinishRequested) {
                    abortBeforeFinish(owner, "bridge $status")
                } else {
                    owner.value.cancelDestination = true
                    cancelDestination(owner.value)
                }
            }
        }, IntentFilter().apply {
            addAction(Protocol.ACTION_WINDOW_PRESENTED)
            addAction(Protocol.ACTION_ENTRY_CAPABILITIES)
        }, TRUSTED_SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
        installed = true
        // A newly loaded Launcher must not send the two-phase protocol to an older, still-loaded
        // system_server module after APK replacement. Capability agreement precedes interception.
        queryCapabilities()
    }

    private fun queryCapabilities() {
        val context = application ?: return
        context.sendBroadcast(Intent(Protocol.ACTION_QUERY_ENTRY_CAPABILITIES).setPackage("android")
            .putExtra(Protocol.EXTRA_SOURCE_PACKAGE, context.packageName))
    }

    /** Runs before native DOWN can reuse the shared Launcher StateManager. */
    fun onGestureStarted(displayId: Int) {
        owners.invalidate(displayId)?.let { supersede(it, retireBeforeDown = true) }
        if (!bridgeSupported) queryCapabilities()
    }

    fun dispose() {
        owners.all().forEach { owners.take(it)?.let(::supersede) }
    }

    private fun supersede(pending: Pending, retireBeforeDown: Boolean = false) {
        pending.timeout?.let(handler::removeCallbacks)
        pending.handoff.source.close()
        pending.pixels?.close()
        pending.pixels = null
        pending.gestureEnd.finish(resume = false)
        cancelDestination(pending)
        val retireNow = retireBeforeDown && !pending.nativeFinishRequested && pending.handoff.ownsCurrentController()
        if (!pending.nativeFinished) {
            runCatching { pending.handoff.animation.forceFinish(Runnable {}) }
                .onFailure { log(TAG, "superseded captured finish failed", it) }
        }
        if (retireNow) {
            pending.handoff.cleanupConsumer()
            pending.handoff.retireSource()
        }
        // No delayed callback is allowed to reset the next gesture's shared Launcher UI.
        log(TAG) { "superseded task=${pending.handoff.taskId} handoff=${pending.handoff.id}" }
    }

    class Handoff internal constructor(
        internal val taskId: Int,
        private val displayId: Int,
        internal val animation: CapturedRecentsAnimation,
        private val retirement: LauncherSourceRetirement,
        internal val swipeHandler: Any,
        private val activeConsumer: Any,
        private val currentController: () -> Any?,
        internal val source: RecentsSourceFrame,
        internal val launcherRotation: Int
    ) {
        internal val id = SystemClock.elapsedRealtimeNanos()

        fun begin(context: Context, intent: Intent): Boolean {
            owners.invalidate(displayId)?.let(::supersede)
            if (!ownsCurrentController()) { source.close(); return false }
            val pending = Pending(this, context.applicationContext, Intent(intent))
            val owner = owners.register(displayId, pending)
            pending.timeout = Runnable {
                if (owners.owns(owner)) abortBeforeFinish(owner, "source/bridge timeout")
            }.also { handler.postDelayed(it, FAILURE_TIMEOUT_MS) }
            log(TAG) { "native UP armed task=$taskId handoff=$id" }
            return true
        }

        /** Called after original input dispatch, even when native UP takes a cancellation path. */
        fun inputFinished(error: Throwable?) {
            val owner = owners.all().firstOrNull { it.value.handoff === this } ?: return
            if (error != null || !owner.value.endReceived) {
                abortBeforeFinish(owner, if (error != null) "native input failed" else "native UP did not reach end")
            }
        }

        internal fun cleanupConsumer() {
            runCatching { XposedHelpers.callMethod(activeConsumer, "onConsumerAboutToBeSwitched") }
                .onFailure { log(TAG, "gesture cleanup failed task=$taskId handoff=$id", it) }
        }

        internal fun ownsCurrentController(): Boolean = currentController() === animation.controller

        internal fun retireSource(): CommandStatus = runCatching {
            val current = currentController()
            if (current != null && current !== animation.controller) return CommandStatus.SUPERSEDED
            retirement.finish()
            CommandStatus.DONE
        }.onFailure { log(TAG, "retire source failed task=$taskId handoff=$id", it) }.getOrDefault(CommandStatus.FAILED)
    }

    private fun dispatchBridge(owner: HandoffOwners.Owner<Pending>, snapshot: EntrySnapshot) {
        val pending = owner.value
        val handoff = pending.handoff
        try {
            pending.pixels = EntrySnapshotTransport.Lease(snapshot)
            val request = Intent(pending.intent).apply {
                putExtra(Protocol.EXTRA_ENTRY_PROTOCOL, Protocol.ENTRY_PROTOCOL)
                putExtra(Protocol.EXTRA_SOURCE_PACKAGE, pending.context.packageName)
                putExtra(Protocol.EXTRA_HANDOFF_ID, handoff.id)
                putExtra(Protocol.EXTRA_LAUNCHER_ROTATION, handoff.launcherRotation)
                pending.pixels!!.attachTo(this)
            }
            pending.context.sendBroadcast(request)
            log(TAG) { "bridge requested after source commit task=${handoff.taskId} handoff=${handoff.id} source=${snapshot.bounds} elapsedMs=${SystemClock.elapsedRealtime() - pending.armedAt}" }
        } catch (error: Throwable) {
            if (pending.pixels == null) snapshot.close()
            abortBeforeFinish(owner, "bridge dispatch failed")
            log(TAG, "bridge dispatch failed task=${handoff.taskId} handoff=${handoff.id}", error)
        }
    }

    private fun requestNativeFinish(owner: HandoffOwners.Owner<Pending>) {
        if (!owners.owns(owner) || owner.value.nativeFinishRequested) return
        val pending = owner.value
        pending.timeout?.let(handler::removeCallbacks)
        pending.pixels?.close()
        pending.pixels = null
        pending.nativeFinishRequested = true
        pending.gestureEnd.finish(resume = false)
        pending.timeout = Runnable {
            if (!owners.owns(owner)) return@Runnable
            logError(TAG, "native timeout task=${pending.handoff.taskId}; retry captured controller only")
            forceNativeFinish(owner)
        }.also { handler.postDelayed(it, FAILURE_TIMEOUT_MS) }
        runCatching {
            // Retain the known-good ordering: finish(true,false) before consumer cleanup. Its own
            // finish requests can then only attach callbacks, never enable PiP or change the target.
            pending.handoff.animation.finish(Runnable { handler.post { nativeFinished(owner) } })
            pending.handoff.cleanupConsumer()
            // Consumer cleanup ends the user's controlled Launcher animation and can expose a
            // normal overview thumbnail. Retire that UI in this turn, under the committed bridge;
            // native ownership still waits for the captured controller's actual completion.
            pending.sourceRetired = pending.handoff.retireSource()
        }.onFailure {
            log(TAG, "native finish failed", it)
            forceNativeFinish(owner)
            pending.handoff.cleanupConsumer()
        }
    }

    private fun forceNativeFinish(owner: HandoffOwners.Owner<Pending>) {
        if (!owners.owns(owner)) return
        val pending = owner.value
        pending.cancelDestination = true
        cancelDestination(pending)
        pending.timeout?.let(handler::removeCallbacks)
        // The retry also needs a terminal bound: a missing native callback cannot keep this
        // simulator frozen forever. Abandon only this lease; never fabricate SOURCE_RELEASED.
        pending.timeout = Runnable { abandonNativeFinish(owner) }
            .also { handler.postDelayed(it, FAILURE_TIMEOUT_MS) }
        runCatching { pending.handoff.animation.forceFinish(Runnable { handler.post { nativeFinished(owner) } }) }
            .onFailure {
                log(TAG, "captured force finish failed", it)
                abandonNativeFinish(owner)
            }
    }

    private fun abandonNativeFinish(owner: HandoffOwners.Owner<Pending>) {
        val pending = owners.take(owner) ?: return
        pending.timeout?.let(handler::removeCallbacks)
        pending.handoff.source.close()
        cancelDestination(pending)
        // UP continuation and snapshot were already discarded when native finish was requested.
        // Removing the owner rejects late finish/commit callbacks without touching newer UI.
        logError(TAG, "native finish abandoned task=${pending.handoff.taskId} handoff=${pending.handoff.id}")
    }

    private fun nativeFinished(owner: HandoffOwners.Owner<Pending>) {
        if (!owners.owns(owner) || owner.value.nativeFinished) return
        val pending = owners.take(owner) ?: return
        pending.nativeFinished = true
        pending.timeout?.let(handler::removeCallbacks)
        pending.handoff.source.close()
        val status = pending.sourceRetired ?: pending.handoff.retireSource()
        log(TAG) { "native finished source=$status task=${pending.handoff.taskId} handoff=${pending.handoff.id}" }
        if (pending.cancelDestination || status != CommandStatus.DONE) cancelDestination(pending)
        else pending.context.sendBroadcast(Intent(Protocol.ACTION_SOURCE_RELEASED).setPackage("android")
            .putExtra(Protocol.EXTRA_TASK_ID, pending.handoff.taskId)
            .putExtra(Protocol.EXTRA_HANDOFF_ID, pending.handoff.id))
    }

    private fun abortBeforeFinish(owner: HandoffOwners.Owner<Pending>, reason: String) {
        val pending = owners.take(owner) ?: return
        pending.timeout?.let(handler::removeCallbacks)
        pending.handoff.source.close()
        pending.pixels?.close()
        pending.pixels = null
        cancelDestination(pending)
        val resume = pending.handoff.ownsCurrentController()
        logWarning(TAG, "$reason task=${pending.handoff.taskId} handoff=${pending.handoff.id}; resumeNative=$resume")
        runCatching { pending.gestureEnd.finish(resume = resume) }
            .onFailure {
                log(TAG, "resume native UP failed", it)
                runCatching { pending.handoff.animation.forceFinish(Runnable {}) }
                pending.handoff.cleanupConsumer()
            }
    }

    private fun cancelDestination(pending: Pending) {
        runCatching {
            pending.context.sendBroadcast(Intent(Protocol.ACTION_CANCEL_RECENTS_ENTRY).setPackage("android")
                .putExtra(Protocol.EXTRA_TASK_ID, pending.handoff.taskId)
                .putExtra(Protocol.EXTRA_HANDOFF_ID, pending.handoff.id))
        }.onFailure { log(TAG, "cancel bridge failed", it) }
    }

    /** Only this captured handler's end decision is deferred. TIS and onMotionEvent still run UP,
     * acknowledge input, clear velocity tracking and gestureStartNavMode (LoS TIS:476-720 /
     * OtherActivityInputConsumer:476-546). Never skip the native input event entry point.
     */
    private fun installEndGate(swipeHandler: Any): Boolean = runCatching {
        val method = generateSequence(swipeHandler.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.first {
                it.name == "onGestureEnded" && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType,
                        PointF::class.java, Boolean::class.javaPrimitiveType))
            }
        if (method !in hookedEnds) {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = owners.all().firstOrNull { it.value.handoff.swipeHandler === param.thisObject } ?: return
                    val pending = owner.value
                    val originalMethod = param.method
                    val receiver = param.thisObject
                    val arguments = param.args.clone()
                    val velocity = PointF(param.args[1] as PointF)
                    arguments[1] = PointF(velocity)
                    if (pending.nativeFinishRequested || pending.gestureEnd.defer { XposedBridge.invokeOriginalMethod(originalMethod, receiver, arguments) }) {
                        log(TAG) { "native UP deferred task=${pending.handoff.taskId} handoff=${pending.handoff.id}" }
                        param.result = null
                        if (pending.endReceived) return
                        pending.endReceived = true
                        // LoS OtherActivityInputConsumer:497 has updated the final UP displacement.
                        // Its px/ms velocity is copied before cleanupAfterGesture recycles the tracker.
                        runCatching {
                            check(pending.handoff.ownsCurrentController())
                            pending.handoff.source.freeze { frame ->
                                if (!owners.owns(owner)) return@freeze
                                if (!pending.handoff.ownsCurrentController()) {
                                    abortBeforeFinish(owner, "source controller replaced")
                                    return@freeze
                                }
                                val vx = (velocity.x * 1000f).takeIf { it.isFinite() } ?: 0f
                                val vy = (velocity.y * 1000f).takeIf { it.isFinite() } ?: 0f
                                val snapshot = RecentsEntryCapture.capture(frame, pending.handoff.taskId, vx, vy)
                                if (snapshot == null) abortBeforeFinish(owner, "source capture failed")
                                else dispatchBridge(owner, snapshot)
                            }
                            log(TAG) { "source pose frozen task=${pending.handoff.taskId} handoff=${pending.handoff.id}; wait native transform commit" }
                        }.onFailure {
                            logWarning(TAG, "final source synchronization failed task=${pending.handoff.taskId}", it)
                            abortBeforeFinish(owner, "source synchronization failed")
                        }
                    }
                }
            })
            hookedEnds.add(method)
        }
        true
    }.onFailure { logWarning(TAG, "native UP capability unavailable", it) }.getOrDefault(false)

    fun prepare(anchor: LauncherInputAnchor, owner: Any, event: MotionEvent, taskId: Int): Handoff? = runCatching {
        if (!bridgeSupported) return null
        val gesture = anchor.gestureStateOf(owner, event) ?: return null
        val container = containerOf(gesture) ?: return null
        val recentsView = XposedHelpers.callMethod(container, "getOverviewPanel") ?: return null
        val retirement = LauncherSourceRetirement.probe(XposedHelpers.callMethod(container, "getStateManager")) ?: return null
        val consumer = anchor.inputConsumerOf(owner, event) ?: return null
        val active = runCatching { XposedHelpers.callMethod(consumer, "getActiveConsumerInHierarchy") }.getOrNull() ?: consumer
        val swipe = XposedHelpers.getObjectField(active, "mInteractionHandler") ?: return null
        val displayId = XposedHelpers.callMethod(event, "getDisplayId") as Int
        val controller = anchor.animationControllerOf(owner, displayId)
            ?: XposedHelpers.callMethod(recentsView, "getRecentsAnimationController")
        val animation = CapturedRecentsAnimation.probe(controller) ?: return null
        // LoS RecentsOrientedState:250-287 distinguishes fixed Home from the touch/display
        // rotation. Do not assume every Launcher is portrait or follows the source application.
        val launcherRotation = runCatching {
            val oriented = XposedHelpers.callMethod(recentsView, "getPagedViewOrientedState")
            XposedHelpers.callMethod(oriented, "getRecentsActivityRotation") as Int
        }.onFailure { logWarning(TAG, "Home rotation unavailable task=$taskId", it) }
            .getOrDefault(-1).takeIf { it in 0..3 } ?: -1
        if (!installEndGate(swipe)) return null
        val source = RecentsEntryCapture.source(swipe, taskId) ?: return null
        Handoff(taskId, displayId, animation, retirement, swipe, active, {
            anchor.animationControllerOf(owner, displayId) ?: runCatching {
                XposedHelpers.callMethod(recentsView, "getRecentsAnimationController")
            }.getOrNull()
        }, source, launcherRotation)
    }.onFailure { logWarning(TAG, "recents bridge unavailable; preserve native gesture", it) }.getOrNull()

    fun isSourceVisible(gesture: Any): Boolean = runCatching {
        val container = containerOf(gesture) ?: return false
        (runCatching { XposedHelpers.callMethod(container, "isStarted") }.getOrNull() as? Boolean) == true ||
            (runCatching { XposedHelpers.callMethod(container, "isShowing") }.getOrNull() as? Boolean) == true
    }.getOrDefault(false)

    private fun containerOf(gesture: Any): Any? {
        val containerInterface = runCatching { XposedHelpers.callMethod(gesture, "getContainerInterface") }
            .getOrElse { XposedHelpers.callMethod(gesture, "getActivityInterface") } ?: return null
        return runCatching { XposedHelpers.callMethod(containerInterface, "getCreatedContainer") }
            .getOrElse { XposedHelpers.callMethod(containerInterface, "getCreatedActivity") }
    }
}
