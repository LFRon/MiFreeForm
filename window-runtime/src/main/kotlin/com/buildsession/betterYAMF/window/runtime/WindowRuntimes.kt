package com.buildsession.betterYAMF.window.runtime

import com.buildsession.betterYAMF.window.api.*
import com.buildsession.betterYAMF.window.core.*
import com.buildsession.betterYAMF.window.execution.LatestOperation
import java.util.ArrayDeque

/** The only construction boundary. No public signature exposes a CORE implementation type. */
object WindowRuntimes {
    private val registry = WindowShellRegistry()
    private val stashSlots = SideStashRegistry()
    private val windows = mutableSetOf<WindowRuntime>()

    /** Synchronous, stateless policy query, safe on a platform task-selection thread. It neither
     * reads the owner-thread registry nor schedules/waits for it. The adapter supplies current
     * ownership for EACH candidate under its native lock and retains the native traversal order,
     * permission checks and direct-focus path. This does not change a scene or acquire focus.
     */
    fun acceptsTaskFocus(facts: TaskFocusFacts): Boolean = WindowSessionPolicy.acceptsTaskFocus(facts)
    fun acceptsNotificationEntry(facts: NotificationEntryFacts): Boolean = NotificationEntryPolicy.accepts(facts)
    /** Display policy recheck only; this does not replace click/permission validation. */
    fun acceptsNotificationDisplay(metrics: SceneMetrics): Boolean =
        NotificationEntryPolicy.acceptsDisplay(metrics.width > metrics.height)

    fun createWindow(session: SessionKey, options: WindowOptions, metrics: SceneMetrics,
        scheduler: RuntimeScheduler, ports: WindowPorts): WindowRuntimeHandle {
        check(scheduler.isCurrentThread())
        check(windows.all { it.isOwnerThread() }) { "Window sessions must share one serial owner" }
        require(windows.none { it.session.windowId == session.windowId }) { "Window id must be process-unique" }
        require(metrics.width > 0 && metrics.height > 0 && options.style.density.isFinite() && options.style.density > 0f)
        return WindowRuntime(session, options, metrics, scheduler, ports, registry, stashSlots,
            { current, taskId -> windows.toList().filter { it !== current &&
                WindowSessionPolicy.replaces(taskId, it.boundTaskId) }.forEach { it.close(false) } },
            { windows.remove(it) },
            { current -> windows.toList().filter { it !== current }.forEach { it.stashSlotsChanged() } })
            .also { windows.add(it) }
    }

    /** Called on the same serial owner as creation, before assigning native resources to a new host. */
    fun prepareOpenTask(taskId: Int) {
        check(windows.all { it.isOwnerThread() })
        windows.toList().filter { WindowSessionPolicy.replaces(taskId, it.boundTaskId) }
            .forEach { it.close(false) }
    }
}

private class WindowRuntime(
    override val session: SessionKey,
    private val options: WindowOptions,
    metrics: SceneMetrics,
    private val scheduler: RuntimeScheduler,
    private val ports: WindowPorts,
    private val registry: WindowShellRegistry,
    stashSlots: SideStashRegistry,
    private val binding: (WindowRuntime, Int) -> Unit,
    private val retired: (WindowRuntime) -> Unit,
    private val slotsChanged: (WindowRuntime) -> Unit
) : WindowRuntimeHandle {
    private val shell = registry.register(WindowRegistration(session.windowId, options.style.density))
    private val size = WindowLayoutPolicy.size(options, metrics)
    private val scene = WindowSceneController(session.windowId, options.style, metrics,
        WindowSceneController.initialContent(metrics, options.style, size.first, size.second,
            WindowLayoutPolicy.preferredTop(options, metrics)), shell, stashSlots)
    @Volatile private var published = scene.snapshot()
    private val events = ArrayDeque<WindowSceneEvent>()
    private val pendingFrames = mutableSetOf<Long>()
    private var draining = false
    @Volatile override var destroyed = false
        private set
    private var closing = false
    private var entry = EntryRequest()
    private var entryStarted = false
    private var entryActivationAuthorized = false
    private var sourceReleased = false
    private var adopted = false
    private var prepared = false
    private var fullscreenReleased = false
    private var ownershipLost = false
    private var lastVisibility: Boolean? = null
    private var framePending = false
    private var frameGeneration = -1L
    private var fixedViewport: WindowRect? = null
    var boundTaskId = -1
        private set
    private val preparation = LatestOperation<WindowOperation> { it.cancel() }
    private val fullscreen = LatestOperation<WindowOperation> { it.cancel() }
    private val frames = LatestOperation<WindowOperation> { it.cancel() }
    private val sourceDeadline = LatestOperation<WindowOperation> { it.cancel() }

    init {
        try { ports.presenter.seed(published) }
        catch (error: Throwable) { registry.unregister(shell); throw error }
    }

    fun isOwnerThread() = scheduler.isCurrentThread()
    fun stashSlotsChanged() = enqueue(WindowSceneEvent.StashSlotsChanged)
    override fun snapshot(): WindowSceneFrame = published
    override fun bindTask(taskId: Int): Boolean {
        check(scheduler.isCurrentThread())
        if (destroyed || closing || ownershipLost || taskId <= 0 || boundTaskId > 0 && boundTaskId != taskId) return false
        boundTaskId = taskId
        binding(this, taskId)
        return true
    }
    override fun normalLayout(): TaskLayout {
        check(scheduler.isCurrentThread())
        return scene.normalLayout(fixedViewport ?: ports.tasks.existingViewport())
    }

    override fun post(event: WindowEvent) = onOwner {
        if (destroyed || closing) return@onOwner
        when (event) {
            is WindowEvent.Input -> enqueue(WindowSceneEvent.Input(event.value))
            is WindowEvent.Entry -> {
                check(!entryStarted && !adopted) { "Entry belongs to a fresh, unadopted window session" }
                val request = event.request
                require(!request.retainedVisual || request.awaitSourceRelease &&
                    request.source?.let { it.width > 0f && it.height > 0f } == true) {
                    "Retained entry requires source geometry and an ownership release barrier"
                }
                entryStarted = true
                entry = request
                enqueue(WindowSceneEvent.OpenAsMini(entry.source, entry.dropX, entry.dropY,
                    entry.awaitSourceRelease, entry.sourceCornerRadius, entry.velocityX, entry.velocityY,
                    entry.sourceImageAspect, entry.origin))
            }
            WindowEvent.VisualAvailable -> enqueue(WindowSceneEvent.VisualAvailable)
            WindowEvent.TaskAvailable -> if (adopted) enqueue(WindowSceneEvent.TaskAvailable)
            WindowEvent.TaskOwnershipLost -> {
                ownershipLost = true
                if (!fullscreenReleased) {
                    fullscreenReleased = true
                    ports.lifecycle.taskReleased()
                }
                enqueue(WindowSceneEvent.TaskOwnershipLost)
            }
            is WindowEvent.SourceReleased -> if (entryStarted && entry.awaitSourceRelease &&
                event.handoffId == entry.handoffId && !sourceReleased) {
                sourceReleased = true
                if (entry.retainedVisual && !adopted) {
                    // An implementation may report readiness before returning its cancel handle.
                    val ticket = destinationWait.begin()
                    val operation = ports.source.awaitDestination { result -> onOwner {
                        if (destroyed || !destinationWait.complete(ticket)) return@onOwner
                        when (result) {
                            is DestinationResult.Done -> {
                                val actual = result.metrics
                                val selected = WindowLayoutPolicy.size(options, actual)
                                enqueue(WindowSceneEvent.EntryLayout(actual, selected.first, selected.second,
                                    WindowLayoutPolicy.preferredTop(options, actual)))
                            }
                            DestinationResult.Failed -> enqueue(WindowSceneEvent.EntryAbort(WindowFailure.DESTINATION))
                            DestinationResult.Superseded -> enqueue(WindowSceneEvent.EntryAbort())
                        }
                    } }
                    destinationWait.attach(ticket, operation)
                } else {
                    sourceDeadline.cancel()
                    enqueue(WindowSceneEvent.SourceReleased)
                }
            }
            is WindowEvent.EntryCancelled -> if (event.handoffId == entry.handoffId && entry.retainedVisual && !sourceReleased)
                enqueue(WindowSceneEvent.EntryAbort())
            is WindowEvent.Metrics -> enqueue(WindowSceneEvent.Metrics(event.value,
                if (adopted) fixedViewport else null))
            is WindowEvent.KeyboardOcclusion -> enqueue(WindowSceneEvent.KeyboardOcclusion(event.bottomInset))
            is WindowEvent.Activate -> enqueue(WindowSceneEvent.Activate(event.requestId, event.origin))
            is WindowEvent.CancelActivation -> enqueue(WindowSceneEvent.CancelActivation(event.requestId))
            is WindowEvent.Visibility -> if (!fullscreenReleased && prepared && lastVisibility != event.visible) {
                lastVisibility = event.visible
                enqueue(WindowSceneEvent.Visibility(event.visible))
            }
            is WindowEvent.TaskColor -> enqueue(WindowSceneEvent.TaskColor(event.color))
            is WindowEvent.HomeForeground -> {
                val facts = event.facts
                if (adopted && !fullscreenReleased)
                    enqueue(WindowSceneEvent.HomeForeground(facts))
            }
            WindowEvent.PresentationInvalidated -> enqueue(WindowSceneEvent.PresentationInvalidated)
            WindowEvent.ResetPosition -> enqueue(WindowSceneEvent.ResetPosition)
        }
    }

    private val destinationWait = LatestOperation<WindowOperation> { it.cancel() }

    override fun activateTask(): Boolean {
        check(scheduler.isCurrentThread()) { "Task activation must run on its bound serial thread" }
        if (destroyed || closing || ownershipLost || boundTaskId <= 0) return false
        if (adopted) return true
        if (entry.retainedVisual && !entryActivationAuthorized) return false
        val wasDraining = draining
        draining = true
        return try {
            val layout = normalLayout()
            fixedViewport = layout.logicalViewport
            adopted = true // Register ownership before an adapter can synchronously return a callback.
            val accepted = ports.tasks.adopt(layout)
            if (!accepted) close(true, scene.restoreToFrontOnFailure())
            accepted
        } catch (error: Throwable) {
            try { ports.lifecycle.failed(WindowFailure.EXECUTION, error) }
            finally { close(true, scene.restoreToFrontOnFailure()) }
            false
        } finally {
            draining = wasDraining
            if (!wasDraining && !destroyed) drainEvents()
        }
    }

    private fun onOwner(work: () -> Unit) {
        fun run() {
            try { work() }
            catch (error: Throwable) {
                if (!destroyed && !closing) {
                    try { ports.lifecycle.failed(WindowFailure.EXECUTION, error) }
                    finally { close(true, scene.restoreToFrontOnFailure()) }
                }
            }
        }
        if (scheduler.isCurrentThread()) run() else scheduler.post { if (!destroyed) run() }
    }

    /** Both callbacks and effects use this pump, including synchronously completed commands. */
    private fun enqueue(event: WindowSceneEvent) {
        if (destroyed) return
        events.addLast(event)
        drainEvents()
    }

    private fun drainEvents() {
        if (draining) return
        draining = true
        try {
            var appliedHint: Boolean? = null
            while (!destroyed) {
                while (events.isNotEmpty() && !destroyed) scene.onEvent(events.removeFirst()).forEach(::execute)
                if (destroyed) break
                val desiredHint = scene.prefersHighFrameRate()
                if (appliedHint != desiredHint) {
                    appliedHint = desiredHint
                    ports.presenter.highFrameRate(desiredHint)
                }
                // A synchronous hint callback may append facts after the first drain. Process
                // those now; repeating an unchanged hint in this turn could feed itself forever.
                if (events.isEmpty()) break
            }
        } catch (error: Throwable) {
            try { ports.lifecycle.failed(WindowFailure.EXECUTION, error) }
            finally { close(true, scene.restoreToFrontOnFailure()) }
        } finally { draining = false }
    }

    private fun execute(effect: WindowSceneEffect) {
        if (destroyed) return
        if (ownershipLost && (effect is WindowSceneEffect.Present || effect is WindowSceneEffect.Prepare ||
            effect is WindowSceneEffect.RequestFrame || effect is WindowSceneEffect.SystemCommand ||
            effect is WindowSceneEffect.HandoffFullscreen || effect == WindowSceneEffect.ActivateEntry)) return
        when (effect) {
            WindowSceneEffect.StashSlotsChanged -> slotsChanged(this)
            is WindowSceneEffect.ActivationResult -> ports.lifecycle.activated(effect.requestId, effect.status)
            is WindowSceneEffect.AbortEntry -> {
                effect.failure?.let { ports.lifecycle.failed(it) }
                close(true, scene.restoreToFrontOnFailure())
            }
            WindowSceneEffect.ActivateEntry -> if (!adopted && sourceReleased) {
                entryActivationAuthorized = true
                sourceDeadline.cancel()
                if (activateTask()) enqueue(WindowSceneEvent.SourceReleased)
            }
            is WindowSceneEffect.Prepare -> {
                val ticket = preparation.begin()
                val layout = normalLayout().copy(normalContent = effect.normalContent)
                fixedViewport = layout.logicalViewport
                val operation = ports.tasks.prepare(effect.commandId, layout) { status -> onOwner {
                    if (!destroyed && preparation.complete(ticket)) {
                        prepared = status == CommandStatus.DONE
                        enqueue(WindowSceneEvent.Prepared(effect.commandId, status))
                    }
                } }
                preparation.attach(ticket, operation)
            }
            is WindowSceneEffect.Present -> {
                published = effect.frame
                pendingFrames.add(effect.frameId)
                ports.presenter.present(FrameSubmission(session, effect.frameId, effect.frame)) { status -> onOwner {
                    if (!destroyed && pendingFrames.remove(effect.frameId))
                        enqueue(WindowSceneEvent.FrameCommitted(effect.frameId, status))
                } }
            }
            is WindowSceneEffect.RequestFrame -> {
                if (!framePending || frameGeneration != effect.generation) {
                    val ticket = frames.begin()
                    framePending = true
                    frameGeneration = effect.generation
                    val operation = scheduler.nextFrame { time ->
                        onOwner {
                            if (!destroyed && frames.complete(ticket)) {
                                framePending = false
                                enqueue(WindowSceneEvent.Tick(effect.generation, time))
                            }
                        }
                    }
                    frames.attach(ticket, operation)
                }
            }
            is WindowSceneEffect.SourceHandoffResult -> acknowledge(effect.status)
            is WindowSceneEffect.CancelSourceHandoff -> acknowledge(effect.reason)
            WindowSceneEffect.ReleaseEntryImage -> ports.presenter.releaseEntryImage()
            is WindowSceneEffect.SystemCommand -> when (val command = effect.command) {
                is WindowCommand.DropZoneShow -> ports.presenter.dropTarget(
                    scene.dropTargetVisual(null, false))
                is WindowCommand.DropHover -> ports.presenter.dropTarget(
                    scene.dropTargetVisual(command.hover, command.active))
                is WindowCommand.DropZoneHide -> ports.presenter.dropTarget(null)
                is WindowCommand.RaiseWindow -> ports.tasks.raise()
                else -> Unit
            }
            is WindowSceneEffect.HandoffFullscreen -> {
                val ticket = fullscreen.begin()
                val operation = ports.tasks.handoffFullscreen(effect.commandId, {
                    onOwner { if (!destroyed && fullscreen.owns(ticket) && !fullscreenReleased) {
                        fullscreenReleased = true; ports.lifecycle.taskReleased()
                    } }
                }) { result -> onOwner {
                    if (!destroyed && fullscreen.complete(ticket)) {
                        if (result.ownershipKnown && result.taskReleased && !fullscreenReleased) {
                            fullscreenReleased = true; ports.lifecycle.taskReleased()
                        }
                        enqueue(WindowSceneEvent.FullscreenHandoff(effect.commandId, result))
                    }
                } }
                fullscreen.attach(ticket, operation)
            }
            WindowSceneEffect.FinishFullscreen -> close(false, true)
            WindowSceneEffect.FinishClose -> close(true, false)
            WindowSceneEffect.FinishOwnershipLoss -> close(false, false)
            is WindowSceneEffect.Failed -> {
                ports.lifecycle.failed(effect.reason)
                close(true, scene.restoreToFrontOnFailure())
            }
        }
    }

    private fun acknowledge(status: CommandStatus) {
        sourceDeadline.cancel()
        if (status == CommandStatus.DONE && !closing) {
            val ticket = sourceDeadline.begin()
            val operation = scheduler.after(5000L) {
                onOwner {
                    if (!destroyed && sourceDeadline.complete(ticket)) {
                        enqueue(WindowSceneEvent.EntryAbort(WindowFailure.SOURCE_TIMEOUT))
                    }
                }
            }
            sourceDeadline.attach(ticket, operation)
        }
        ports.source.acknowledge(status)
        if (status == CommandStatus.DONE && !entry.remoteSourceOwner)
            post(WindowEvent.SourceReleased(entry.handoffId))
    }

    override fun close(releaseTask: Boolean, restoreToFront: Boolean) = onOwner {
        if (destroyed || closing) return@onOwner
        closing = true
        var cleanupFailure: Throwable? = null
        fun clean(work: () -> Unit) {
            try { work() } catch (error: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error
                else if (previous !== error) previous.addSuppressed(error)
            }
        }
        try { clean { scene.onEvent(WindowSceneEvent.Destroy).forEach { effect -> clean { execute(effect) } } } }
        finally {
            destroyed = true
            clean { published = scene.snapshot() }
            clean { registry.unregister(shell) }
            events.clear()
            pendingFrames.clear()
            clean { preparation.cancel() }
            clean { fullscreen.cancel() }
            clean { destinationWait.cancel() }
            clean { sourceDeadline.cancel() }
            clean { frames.cancel() }
            framePending = false
            clean { retired(this) }
            // A faulty cancellation must never prevent native input-window/resource disposal.
            clean { ports.lifecycle.finish(releaseTask && adopted && !fullscreenReleased, restoreToFront) }
            cleanupFailure?.let { error -> clean { ports.lifecycle.failed(WindowFailure.EXECUTION, error) } }
        }
    }
}
