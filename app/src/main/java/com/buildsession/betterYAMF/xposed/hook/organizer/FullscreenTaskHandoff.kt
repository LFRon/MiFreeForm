package com.buildsession.betterYAMF.xposed.hook.organizer

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.SurfaceControl
import com.buildsession.betterYAMF.window.api.WindowOperation
import com.buildsession.betterYAMF.window.api.CommandStatus
import com.buildsession.betterYAMF.window.api.FullscreenHandoffResult
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logError
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * An independent, already-fullscreen bridge covers this entire operation.
 *
 * LoS 24 TaskOrganizerController:415 migrates the task SC during release. Resolve the CURRENT
 * main window and SC after release, wait for native motion, then request a fresh draw. LoS
 * WindowState:6133 can finish a draw handler on timeout with a NULL buffer transaction, so neither
 * applyWithNextDraw's callback nor a generic transaction commit alone acknowledges that request.
 * Observe a non-null client finishDrawing transaction with the requested sequence and identity,
 * then its commit. This is a client redraw acknowledgement, not native buffer inspection.
 */
internal object FullscreenTaskHandoff {
    private const val TAG = "reYAMF_Fullscreen"
    private val ids = AtomicLong()
    private val active = ConcurrentHashMap<Int, Operation>()
    private var initialized = false
    private var platform: Platform? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        runCatching {
            val api = Platform()
            api.finishDrawing.hookBefore { param ->
                if (active.isNotEmpty()) runCatching {
                    val window = param.thisObject
                    val task = api.windowTask.invoke(window) ?: return@runCatching
                    api.locked(task) { api.operationFor(task) }?.drawing(api, window,
                        param.args[0] as? SurfaceControl.Transaction, param.args[1] as Int)
                }.onFailure { log(TAG, "fullscreen draw observation failed", it) }
            }
            platform = api
        }.onFailure { logWarning(TAG, "fullscreen draw synchronization unavailable", it) }
    }

    fun start(
        taskId: Int, task: Any?, release: () -> Boolean, onReleased: () -> Unit,
        onComplete: (FullscreenHandoffResult) -> Unit
    ): WindowOperation {
        val operation = Operation(taskId, task, platform, release, onReleased, onComplete)
        check(Looper.myLooper() == Looper.getMainLooper()) { "Fullscreen handoff must start on main" }
        active[taskId]?.cancel()
        active[taskId] = operation
        operation.start()
        return operation
    }

    fun cancel(taskId: Int) { active[taskId]?.cancel() }

    private class Operation(
        private val taskId: Int, private val task: Any?, private val api: Platform?,
        private val release: () -> Boolean, private val onReleased: () -> Unit,
        private val onComplete: (FullscreenHandoffResult) -> Unit
    ) : WindowOperation {
        private val id = ids.incrementAndGet()
        private val handler = Handler(Looper.getMainLooper())
        private val executor = Executor { handler.post(it) }
        private val fence = FullscreenDrawFence()
        @Volatile private var completed = false
        private var released = false
        private var ownershipKnown = true
        private var layout: Rect? = null
        private var frameCallback: Choreographer.FrameCallback? = null
        private var stage = "release"
        private val startedAt = android.os.SystemClock.elapsedRealtime()
        private var stageAt = startedAt
        private val timeout = Runnable {
            logError(TAG, "fullscreen timeout task=$taskId operation=$id stage=$stage released=$released known=$ownershipKnown")
            finish(CommandStatus.FAILED)
        }

        fun start() {
            if (Looper.myLooper() != handler.looper) { handler.post(::start); return }
            if (completed || active[taskId] !== this) return
            val api = api ?: run { finish(CommandStatus.FAILED); return }
            val task = task ?: run { finish(CommandStatus.FAILED); return }
            handler.postDelayed(timeout, 5000L) // Failure bound; never a successful reveal timer.
            runCatching {
                ownershipKnown = false
                val accepted = release()
                released = api.locked(task) { !api.inFloatingRoot(task) }
                ownershipKnown = true
                if (released) onReleased()
                log(TAG) { "release task=$taskId operation=$id accepted=$accepted released=$released" }
                if (!released) finish(CommandStatus.FAILED) else poll()
            }.onFailure {
                log(TAG, "fullscreen release failed task=$taskId operation=$id", it)
                // A native operation can throw after already changing ownership.
                if (!ownershipKnown) {
                    runCatching { api.locked(task) { !api.inFloatingRoot(task) } }
                        .onSuccess { released = it; ownershipKnown = true; if (released) onReleased() }
                        .onFailure { logWarning(TAG, "ownership unavailable task=$taskId operation=$id", it) }
                }
                finish(CommandStatus.FAILED)
            }
        }

        private fun poll() {
            if (completed || active[taskId] !== this) return
            val api = api ?: return
            val task = task ?: return
            runCatching {
                val ready = api.locked(task) {
                    val facts = api.facts(task)
                    if (!facts.attached || api.inFloatingRoot(task)) {
                        // A new session / task removal cannot be acknowledged by this operation.
                        return@locked null
                    }
                    val window = facts.window
                    val surface = facts.surface
                    val clientSurface = facts.clientSurface
                    if (!facts.fullscreen || window == null || surface == null || !surface.isValid ||
                        clientSurface == null || !clientSurface.isValid) {
                        fence.invalidate()
                        layout = null
                        moveStage("fullscreen layout")
                        false
                    } else if (!fence.matches(window, surface, clientSurface) || layout != facts.bounds) {
                        fence.invalidate()
                        layout = null
                        moveStage("native transition")
                        if (facts.stable && api.windowSyncState.getInt(window) == 0 && !api.inRelayout.getBoolean(window)) {
                            // Empty WM-owned draw handler only requests a new buffer. It cannot
                            // complete this operation (its timeout can execute without a buffer).
                            fence.arm(window, surface, api.requestDraw(task, window), clientSurface)
                            layout = Rect(facts.bounds)
                            moveStage("fullscreen redraw commit")
                            log(TAG) { "draw armed task=$taskId operation=$id sequence=${api.sequence(window)} bounds=$layout surface=$surface" }
                        }
                        false
                    } else fence.committed && facts.stable && api.windowDrawn.invoke(window) == true
                }
                when (ready) {
                    null -> finish(CommandStatus.SUPERSEDED)
                    true -> finish(CommandStatus.DONE)
                    false -> requestFrame()
                }
            }.onFailure {
                log(TAG, "fullscreen readiness failed task=$taskId operation=$id stage=$stage", it)
                finish(CommandStatus.FAILED)
            }
        }

        fun ownsTask(candidate: Any): Boolean = candidate === task

        fun drawing(api: Platform, window: Any, transaction: SurfaceControl.Transaction?, sequence: Int) {
            val task = task ?: return
            if (completed || transaction == null || active[taskId] !== this) return
            api.locked(task) {
                val facts = api.facts(task)
                val surface = facts.surface ?: return@locked
                val clientSurface = facts.clientSurface ?: return@locked
                if (!facts.fullscreen || facts.window !== window || facts.bounds != layout ||
                    sequence < api.sequence(window) || !fence.awaitingDraw) return@locked
                val ticket = fence.drawing(window, surface, sequence, hasTransaction = true, clientSurface = clientSurface) ?: return@locked
                // Borrowed buffer transaction: add a listener, never apply/close it here.
                transaction.addTransactionCommittedListener(executor) {
                    if (!completed && active[taskId] === this) {
                        api.locked(task) { fence.commit(ticket) }
                        poll()
                    }
                }
            }
        }

        private fun requestFrame() {
            if (frameCallback != null || completed) return
            frameCallback = Choreographer.FrameCallback {
                frameCallback = null
                poll()
            }.also { Choreographer.getInstance().postFrameCallback(it) }
        }

        private fun moveStage(next: String) {
            if (stage == next) return
            val now = android.os.SystemClock.elapsedRealtime()
            log(TAG) { "wait task=$taskId operation=$id stage=$stage durationMs=${now - stageAt} next=$next elapsedMs=${now - startedAt}" }
            stage = next
            stageAt = now
        }

        private fun finish(status: CommandStatus) {
            if (completed) return
            completed = true
            active.remove(taskId, this)
            handler.removeCallbacks(timeout)
            frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            frameCallback = null
            runCatching { if (api != null && task != null) api.locked(task) { fence.invalidate() } }
                .onFailure { logWarning(TAG, "draw fence cleanup failed task=$taskId operation=$id", it) }
            log(TAG) { "result task=$taskId operation=$id status=$status released=$released known=$ownershipKnown stage=$stage stageMs=${android.os.SystemClock.elapsedRealtime() - stageAt} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}" }
            onComplete(FullscreenHandoffResult(status, released, ownershipKnown))
        }

        override fun cancel() {
            if (Looper.myLooper() == handler.looper) finish(CommandStatus.SUPERSEDED)
            else handler.post { finish(CommandStatus.SUPERSEDED) }
        }
    }

    /** All private signatures are selected once at systemReady, not searched per frame. */
    private class Platform {
        private val loader = Instances.systemContext.classLoader
        private val taskClass = loader.loadClass("com.android.server.wm.Task")
        private val windowClass = loader.loadClass("com.android.server.wm.WindowState")
        private val containerClass = loader.loadClass("com.android.server.wm.WindowContainer")
        private val wmClass = loader.loadClass("com.android.server.wm.WindowManagerService")
        val finishDrawing = method(windowClass, "finishDrawing", SurfaceControl.Transaction::class.java, Int::class.javaPrimitiveType!!)
        val windowTask = method(windowClass, "getTask")
        val taskId = field(taskClass, "mTaskId")
        val nextDraw = method(windowClass, "applyWithNextDraw", Consumer::class.java)
        val scheduleAnimation = method(containerClass, "scheduleAnimation")
        val windowDrawn = method(windowClass, "isDrawn")
        val windowSyncState = field(windowClass, "mSyncState")
        val inRelayout = field(windowClass, "mInRelayout")
        private val syncSequence = field(windowClass, "mSyncSeqId")
        private val bufferSequence = runCatching { field(windowClass, "mBufferSeqId") }.getOrNull()
        private val wmField = field(containerClass, "mWmService")
        private val globalLock = field(wmClass, "mGlobalLock")
        private val requestTraversal = method(wmClass, "requestTraversal")
        private val attached = method(containerClass, "isAttached")
        private val windowingMode = method(taskClass, "getWindowingMode")
        private val getBounds = method(containerClass, "getBounds")
        private val getDisplay = method(containerClass, "getDisplayContent")
        private val getSurface = method(containerClass, "getSurfaceControl")
        private val getWindow = method(taskClass, "getTopVisibleAppMainWindow")
        private val clientSurface: (Any) -> SurfaceControl? = selectClientSurface()
        private val getParent = method(containerClass, "getParent")
        private val animating = method(containerClass, "isAnimating")
        private val controller = method(Instances.activityTaskManager.javaClass, "getTransitionController").invoke(Instances.activityTaskManager)
        private val participant = method(controller.javaClass, "inTransition", containerClass)

        fun sequence(window: Any): Int = maxOf(syncSequence.getInt(window), bufferSequence?.getInt(window) ?: 0)

        fun inFloatingRoot(task: Any): Boolean {
            var node: Any? = task
            var hops = 0
            while (node != null) {
                check(hops++ < 64) { "Invalid task parent chain" }
                if (taskClass.isInstance(node) && SmoothTaskOrganizer.isFloatingRootTask(taskId.getInt(node))) return true
                node = getParent.invoke(node)
            }
            return false
        }

        fun operationFor(task: Any): Operation? {
            var node: Any? = task
            var hops = 0
            while (node != null) {
                check(hops++ < 64) { "Invalid draw task parent chain" }
                if (taskClass.isInstance(node)) {
                    val candidate = node
                    active[taskId.getInt(candidate)]?.let { if (it.ownsTask(candidate)) return it }
                }
                node = getParent.invoke(node)
            }
            return null
        }

        fun requestDraw(task: Any, window: Any): Int {
            nextDraw.invoke(window, Consumer<SurfaceControl.Transaction> { })
            // scheduleAnimation alone only prepares surfaces. A placement traversal is needed
            // for WindowState:updateResizingWindowIfNeeded to send the new buffer sequence.
            requestTraversal.invoke(wmField.get(task))
            scheduleAnimation.invoke(task)
            return sequence(window)
        }

        fun <T> locked(task: Any, work: () -> T): T =
            synchronized(checkNotNull(globalLock.get(checkNotNull(wmField.get(task))))) { work() }

        data class Facts(val attached: Boolean, val fullscreen: Boolean, val stable: Boolean,
            val window: Any?, val surface: SurfaceControl?, val clientSurface: SurfaceControl?, val bounds: Rect)

        fun facts(task: Any): Facts {
            val display = getDisplay.invoke(task)
            val bounds = Rect(getBounds.invoke(task) as Rect)
            val full = windowingMode.invoke(task) == 1 && display != null &&
                !bounds.isEmpty && bounds == getBounds.invoke(display)
            var stable = animating.invoke(task) == false
            var parent: Any? = task
            var hops = 0
            while (stable && parent != null) {
                check(hops++ < 64) { "Invalid transition parent chain" }
                stable = participant.invoke(controller, parent) == false
                parent = getParent.invoke(parent)
            }
            val window = getWindow.invoke(task)
            return Facts(attached.invoke(task) == true, full, stable,
                window, getSurface.invoke(task) as? SurfaceControl, window?.let(clientSurface), bounds)
        }

        private fun selectClientSurface(): (Any) -> SurfaceControl? {
            // LoS 24 owns client surfaces in WindowStateAnimator.mSurfaceControl. Older Android
            // generations keep their buffer layer in WindowSurfaceController instead.
            val getter = runCatching { method(windowClass, "getClientViewRootSurface") }.getOrNull()
            if (getter != null) return { getter.invoke(it) as? SurfaceControl }
            val animatorField = field(windowClass, "mWinAnimator")
            val animatorClass = animatorField.type
            val direct = runCatching { field(animatorClass, "mSurfaceControl") }.getOrNull()
            if (direct != null) return { direct.get(animatorField.get(it)) as? SurfaceControl }
            val controllerField = field(animatorClass, "mSurfaceController")
            val layer = field(controllerField.type, "mSurfaceControl")
            return { window -> controllerField.get(animatorField.get(window))?.let { layer.get(it) as? SurfaceControl } }
        }
    }

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
        generateSequence(type) { it.superclass }.mapNotNull {
            runCatching { it.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull() ?: error("Missing ${type.name}#$name")

    private fun field(type: Class<*>, name: String): Field =
        generateSequence(type) { it.superclass }.mapNotNull {
            runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull() ?: error("Missing ${type.name}#$name")
}
