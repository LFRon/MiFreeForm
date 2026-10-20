package com.buildsession.betterYAMF.window.runtime

import com.buildsession.betterYAMF.window.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WindowRuntimeTest {
    private val fixtures = mutableListOf<Fixture>()
    private var nextId = 10000
    @After fun cleanUp() { fixtures.forEach { it.handle.close(false) } }
    private fun fixture(autoPrepare: Boolean = true, autoCommit: Boolean = true, taskId: Int? = null): Fixture =
        Fixture(SessionKey(17, ++nextId), autoPrepare, autoCommit, taskId).also { fixtures += it }

    private class Clock : RuntimeScheduler {
        data class Work(val action: () -> Unit, var cancelled: Boolean = false)
        var owner = true
        var now = 1000L
        val posts = ArrayDeque<() -> Unit>()
        val frames = ArrayDeque<Work>()
        val deadlines = mutableListOf<Work>()
        override fun isCurrentThread() = owner
        override fun post(work: () -> Unit) { posts.addLast(work) }
        override fun after(delayMs: Long, work: () -> Unit): WindowOperation {
            val pending = Work(work); deadlines += pending
            return WindowOperation { pending.cancelled = true }
        }
        override fun nextFrame(work: (Long) -> Unit): WindowOperation {
            val pending = Work(action = { work(now) }); frames.addLast(pending)
            return WindowOperation { pending.cancelled = true }
        }
        fun step() {
            now += 16
            val pending = frames.toList(); frames.clear()
            pending.filterNot { it.cancelled }.forEach { it.action() }
            drain()
        }
        fun drain() { while (posts.isNotEmpty()) posts.removeFirst()() }
    }

    private class Fixture(val key: SessionKey, val autoPrepare: Boolean, val autoCommit: Boolean, taskId: Int?) {
        val clock = Clock()
        val metrics = SceneMetrics(1264, 2780, 130, 80)
        val frames = mutableListOf<FrameSubmission>()
        val commits = mutableListOf<(CommandStatus) -> Unit>()
        val acknowledgements = mutableListOf<CommandStatus>()
        val failures = mutableListOf<WindowFailure>()
        val finished = mutableListOf<Pair<Boolean, Boolean>>()
        val adopted = mutableListOf<TaskLayout>()
        val preparations = mutableListOf<TaskLayout>()
        var existing: WindowRect? = null
        var prepareResult: ((CommandStatus) -> Unit)? = null
        var prepareCancelled = 0
        var throwOnPrepareCancel = false
        var prepareCancelStatus: CommandStatus? = null
        var throwOnSourceAck = false
        var duringSourceAck: (() -> Unit)? = null
        var destinationReady: ((SceneMetrics) -> Unit)? = null
        var destinationResult: ((DestinationResult) -> Unit)? = null
        var destinationCancelled = 0
        var releasedImages = 0
        var raises = 0
        var adoptionAccepted = true
        var immediateResourceCallback = false
        var duringPresent: (() -> Unit)? = null
        var duringFrameRate: ((Boolean) -> Unit)? = null
        var adopting = false
        var releaseNotifications = 0
        var releasedCallback: (() -> Unit)? = null
        var fullscreenResult: ((FullscreenHandoffResult) -> Unit)? = null
        val handle: WindowRuntimeHandle = WindowRuntimes.createWindow(key,
            WindowOptions(SceneStyle(3.2f, 20f, 12f)), metrics, clock,
            WindowPorts(object : TaskSessionPort {
                override fun existingViewport() = existing
                override fun adopt(layout: TaskLayout): Boolean {
                    adopting = true
                    adopted += layout
                    if (immediateResourceCallback) handle.post(WindowEvent.TaskAvailable)
                    adopting = false
                    return adoptionAccepted
                }
                override fun prepare(commandId: Long, layout: TaskLayout, result: (CommandStatus) -> Unit): WindowOperation {
                    assertFalse("Preparation must leave the adoption stack", adopting)
                    preparations += layout; prepareResult = result
                    if (autoPrepare) result(CommandStatus.DONE)
                    return WindowOperation {
                        prepareCancelled++
                        prepareCancelStatus?.let(result)
                        if (throwOnPrepareCancel) error("broken adapter cancellation")
                    }
                }
                override fun handoffFullscreen(commandId: Long, released: () -> Unit,
                    result: (FullscreenHandoffResult) -> Unit): WindowOperation {
                    releasedCallback = released; fullscreenResult = result
                    return WindowOperation { result(FullscreenHandoffResult(CommandStatus.SUPERSEDED, false)) }
                }
                override fun raise(): Boolean { raises++; return true }
            }, object : ScenePresenter {
                override fun seed(frame: WindowSceneFrame) {}
                override fun present(submission: FrameSubmission, result: (CommandStatus) -> Unit) {
                    frames += submission; commits += result
                    duringPresent?.invoke()
                    if (autoCommit) result(CommandStatus.DONE)
                }
                override fun highFrameRate(enabled: Boolean) { duringFrameRate?.invoke(enabled) }
                override fun dropTarget(visual: DropTargetVisual?) {}
                override fun releaseEntryImage() { releasedImages++ }
            }, object : SourceHandoffPort {
                override fun acknowledge(status: CommandStatus) {
                    acknowledgements += status
                    duringSourceAck?.invoke()
                    if (throwOnSourceAck) error("source peer unavailable")
                }
                override fun awaitDestination(result: (DestinationResult) -> Unit): WindowOperation {
                    destinationResult = result
                    destinationReady = { result(DestinationResult.Done(it)) }
                    return WindowOperation {
                        destinationCancelled++
                        result(DestinationResult.Superseded)
                    }
                }
            }, object : WindowLifecyclePort {
                override fun taskReleased() { releaseNotifications++ }
                override fun failed(reason: WindowFailure, cause: Throwable?) { failures += reason }
                override fun finish(releaseTask: Boolean, restoreToFront: Boolean) { finished += releaseTask to restoreToFront }
            }))
        init { assertTrue(handle.bindTask(taskId ?: key.windowId)) }
        fun retained(id: Long = 73) {
            handle.post(WindowEvent.Entry(EntryRequest(handoffId = id, source = SceneRect(493f, 113f, 429f, 942f),
                awaitSourceRelease = true, retainedVisual = true, remoteSourceOwner = true)))
            handle.post(WindowEvent.VisualAvailable)
        }
        fun ordinary() { assertTrue(handle.activateTask()); handle.post(WindowEvent.TaskAvailable) }
        fun settle() { repeat(150) { clock.step() } }
    }

    @Test fun synchronousPreparationDoesNotCancelAnAlreadyCompletedOperation() {
        val f = fixture(); f.immediateResourceCallback = true
        assertTrue(f.handle.activateTask())
        f.settle()
        assertEquals(1, f.preparations.size)
        assertEquals(0, f.prepareCancelled)
        assertTrue(f.handle.snapshot().acceptsContent)
    }

    @Test fun nativeSourceCannotRetireBeforeTheProducedBridgeFrameCommits() {
        val f = fixture(autoCommit = false); f.retained()
        assertTrue(f.acknowledgements.isEmpty())
        assertTrue(f.adopted.isEmpty())
        f.commits.first()(CommandStatus.DONE)
        assertEquals(listOf(CommandStatus.DONE), f.acknowledgements)
        assertTrue(f.adopted.isEmpty())
        f.handle.post(WindowEvent.SourceReleased(73))
        assertTrue(f.adopted.isEmpty())
        f.destinationReady!!(f.metrics)
        assertEquals(1, f.adopted.size)
    }

    @Test fun wrongHandoffAndLateDestinationResultsCannotActivateAnotherSession() {
        val f = fixture()
        f.handle.post(WindowEvent.SourceReleased(0)) // No source operation owns the initial default id.
        f.retained()
        f.handle.post(WindowEvent.SourceReleased(72))
        assertNull(f.destinationReady)
        f.handle.post(WindowEvent.SourceReleased(73))
        val late = f.destinationReady!!
        f.handle.close(false)
        late(f.metrics)
        assertTrue(f.adopted.isEmpty())
        assertEquals(1, f.destinationCancelled)
    }

    @Test fun aReplacedTaskSessionRejectsItsOldPreparationAndFrameCallbacks() {
        val first = fixture(autoPrepare = false, autoCommit = false)
        first.ordinary()
        val callback = first.prepareResult!!
        val replacement = fixture(taskId = first.key.windowId)
        assertTrue(first.handle.destroyed)
        callback(CommandStatus.DONE)
        assertEquals(0, first.frames.size)
        assertEquals(listOf(false to false), first.finished)
        replacement.ordinary(); replacement.settle()
        assertTrue(replacement.handle.snapshot().acceptsContent)
    }

    @Test fun aClosingSessionCannotRebindAndRetireItsOwnReplacementFromACancelCallback() {
        val first = fixture(autoCommit = false); first.retained()
        first.duringSourceAck = { first.handle.bindTask(first.key.windowId) }
        val replacement = fixture(taskId = first.key.windowId)
        assertTrue(first.handle.destroyed)
        assertFalse(replacement.handle.destroyed)
        replacement.ordinary(); replacement.settle()
        assertTrue(replacement.handle.snapshot().acceptsContent)
        assertTrue(first.failures.isEmpty())
    }

    @Test fun cancellationCompletesCleanupOnceEvenWhenTheAdapterCallsBackLate() {
        val f = fixture(autoPrepare = false); f.ordinary()
        val result = f.prepareResult!!
        f.handle.close(true); f.handle.close(true)
        result(CommandStatus.FAILED); result(CommandStatus.DONE)
        assertEquals(1, f.prepareCancelled)
        assertEquals(listOf(true to false), f.finished)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun aBrokenAdapterCancellationCannotPreventNativeHostDisposal() {
        val f = fixture(autoPrepare = false); f.ordinary()
        f.throwOnPrepareCancel = true
        f.handle.close(true); f.handle.close(true)
        f.prepareResult!!(CommandStatus.DONE)
        assertTrue(f.handle.destroyed)
        assertEquals(1, f.prepareCancelled)
        assertEquals(listOf(true to false), f.finished)
        assertEquals(listOf(WindowFailure.EXECUTION), f.failures)
        assertTrue(f.frames.isEmpty())
    }

    @Test fun unavailableSourceTransportCannotLeaveTheDestinationInputHostAlive() {
        val f = fixture(autoCommit = false); f.retained()
        f.throwOnSourceAck = true
        f.handle.close(false)
        assertTrue(f.handle.destroyed)
        assertEquals(listOf(false to false), f.finished)
        assertEquals(listOf(WindowFailure.EXECUTION), f.failures)
    }

    @Test fun sourceCancellationOnlyAbortsItsMatchingUnreleasedEntry() {
        val f = fixture(); f.retained()
        f.handle.post(WindowEvent.EntryCancelled(72))
        assertFalse(f.handle.destroyed)
        f.handle.post(WindowEvent.EntryCancelled(73))
        f.handle.post(WindowEvent.EntryCancelled(73))
        assertTrue(f.handle.destroyed)
        assertEquals(listOf(CommandStatus.DONE, CommandStatus.SUPERSEDED), f.acknowledgements)
        assertEquals(listOf(false to true), f.finished)
        assertTrue(f.adopted.isEmpty())
    }

    @Test fun rotatedDestinationIsSelectedBeforeNativeAdoption() {
        val f = fixture(); f.retained(); f.handle.post(WindowEvent.SourceReleased(73))
        f.destinationReady!!(SceneMetrics(2780, 1264, 100, 70, rotation = 1))
        val actual = f.adopted.single()
        assertEquals(1973f, actual.normalContent.width, 0.1f)
        assertEquals(518f, actual.normalContent.height, 0.1f)
        assertEquals(2780, actual.logicalViewport.width)
    }

    @Test fun queuedMetricsCannotMixAnotherDisplayWidthIntoTheChosenAdoptionLayout() {
        val f = fixture(); f.retained(); f.handle.post(WindowEvent.SourceReleased(73))
        var changed = false
        f.duringPresent = {
            if (!changed) {
                changed = true
                f.handle.post(WindowEvent.Metrics(f.metrics))
            }
        }
        f.destinationReady!!(SceneMetrics(2780, 1264, 100, 70, rotation = 1))
        assertTrue(changed)
        val layout = f.adopted.single()
        assertEquals(1973f, layout.normalContent.width, 0.1f)
        assertEquals(2780, layout.logicalViewport.width)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun frameRateCallbackFactsAreDrainedWithoutWaitingForAnotherExternalEvent() {
        val f = fixture()
        var requested = false
        f.duringFrameRate = { enabled ->
            if (!enabled && !requested && f.handle.snapshot().acceptsContent) {
                requested = true
                f.handle.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(true, false, false, false)))
            }
        }
        f.ordinary(); f.settle()
        assertTrue(requested)
        assertEquals(1, f.raises)
    }

    @Test fun replacementPreservesAnAlreadyAppliedNativeLogicalViewport() {
        val f = fixture()
        f.existing = WindowRect(0, 0, 1264, 1900)
        f.ordinary(); f.settle()
        assertEquals(f.existing, f.adopted.single().logicalViewport)
        assertEquals(f.existing, f.preparations.single().logicalViewport)
    }

    @Test fun aSourceTimeoutAbortsWithoutInventingSourceReleaseOrAdoptingTheTask() {
        val f = fixture(); f.retained()
        f.clock.deadlines.filterNot { it.cancelled }.forEach { it.action() }
        assertTrue(f.handle.destroyed)
        assertTrue(f.adopted.isEmpty())
        assertEquals(listOf(WindowFailure.SOURCE_TIMEOUT), f.failures)
        assertEquals(listOf(false to true), f.finished)
    }

    @Test fun destinationFailureOrSupersessionTerminatesWithoutAdoptionAndIgnoresLateSuccess() {
        for (outcome in listOf(DestinationResult.Failed, DestinationResult.Superseded)) {
            val f = fixture(); f.retained(); f.handle.post(WindowEvent.SourceReleased(73))
            val report = f.destinationResult!!
            report(outcome)
            report(DestinationResult.Done(f.metrics))
            assertTrue(f.handle.destroyed)
            assertTrue(f.adopted.isEmpty())
            assertEquals(listOf(false to true), f.finished)
            assertEquals(if (outcome == DestinationResult.Failed) listOf(WindowFailure.DESTINATION) else emptyList<WindowFailure>(),
                f.failures)
        }
    }

    @Test fun offThreadFactsEnterTheBoundPumpAndAreIgnoredAfterDestruction() {
        val f = fixture()
        f.clock.owner = false
        f.handle.post(WindowEvent.TaskColor(123))
        f.clock.owner = true
        f.handle.close(false)
        f.clock.drain()
        assertTrue(f.frames.isEmpty())
    }

    @Test fun homeMaintenanceUsesCoreInputAndNativeOwnershipFacts() {
        val f = fixture(); f.ordinary(); f.settle()
        f.handle.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(true, false, true, false)))
        f.handle.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(true, true, false, false)))
        f.handle.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(true, false, false, true)))
        assertEquals(0, f.raises)
        f.handle.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(true, false, false, false)))
        assertEquals(1, f.raises)
    }

    @Test fun ordinaryAndRetainedEntriesShareOnePreparedViewportAndCompleteSceneContract() {
        val f = fixture(); f.retained(); f.handle.post(WindowEvent.SourceReleased(73)); f.destinationReady!!(f.metrics)
        f.handle.post(WindowEvent.TaskAvailable); f.settle()
        assertEquals(1, f.releasedImages)
        assertFalse(f.handle.snapshot().showEntryImage)
        assertTrue(f.frames.all { it.session == f.key && it.frame.coordinateSpace == f.metrics })
        val final = f.handle.snapshot()
        assertEquals(final.outer, final.input.bounds)
        assertNull(final.input.passThrough) // mini still owns its whole input area
        assertEquals(1, f.preparations.size)
    }

    @Test fun syncAndPostedAdaptersProduceIdenticalScenesForEquivalentFactsAndFrameTimes() {
        val sync = fixture()
        val posted = fixture(autoPrepare = false)
        sync.ordinary(); posted.ordinary()
        posted.clock.owner = false
        posted.prepareResult!!(CommandStatus.DONE)
        posted.clock.owner = true
        posted.clock.drain()
        sync.settle(); posted.settle()
        assertEquals(sync.frames.map { it.frame }, posted.frames.map { it.frame })
    }

    @Test fun aDuplicateTerminalFrameCallbackCannotDestroyAReadyWindow() {
        val f = fixture(); f.ordinary(); f.settle()
        f.commits.last()(CommandStatus.FAILED)
        assertFalse(f.handle.destroyed)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun cancelledSourceDeadlinesAndFrameClocksCannotAffectTheCurrentOperation() {
        val f = fixture(); f.retained()
        val timeout = f.clock.deadlines.single()
        assertFalse(f.handle.activateTask()) // A caller cannot bypass CORE's source/layout barrier.
        f.handle.post(WindowEvent.SourceReleased(73))
        assertFalse(f.handle.activateTask())
        f.destinationReady!!(f.metrics)
        timeout.action() // Simulate a cancellation racing a queued callback.
        assertFalse(f.handle.destroyed)
        assertEquals(1, f.adopted.size)
        f.handle.post(WindowEvent.TaskAvailable); f.settle()
        assertTrue(f.failures.isEmpty())
    }

    @Test fun ownershipLossCancelsPreparationAndLateResultsCannotReleaseTheNewOwnersTask() {
        val f = fixture(autoPrepare = false)
        f.ordinary()
        f.prepareCancelStatus = CommandStatus.FAILED
        val oldResult = requireNotNull(f.prepareResult)
        val count = f.frames.size
        f.handle.post(WindowEvent.TaskOwnershipLost)
        assertTrue(f.handle.destroyed)
        assertEquals(listOf(false to false), f.finished)
        assertEquals(1, f.prepareCancelled)
        assertEquals(1, f.releaseNotifications)
        oldResult(CommandStatus.DONE)
        oldResult(CommandStatus.FAILED)
        f.settle()
        assertEquals(count, f.frames.size)
        assertEquals(listOf(false to false), f.finished)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun synchronousOwnershipLossDuringPresentationStopsRemainingExecutionInThatTurn() {
        val f = fixture()
        f.duringPresent = { f.handle.post(WindowEvent.TaskOwnershipLost) }
        f.ordinary()
        f.settle()
        assertTrue(f.handle.destroyed)
        assertEquals(1, f.frames.size)
        assertEquals(listOf(false to false), f.finished)
        assertEquals(1, f.releaseNotifications)
        assertFalse(f.handle.activateTask())
        assertFalse(f.handle.bindTask(f.key.windowId))
        assertTrue(f.failures.isEmpty())
    }

    @Test fun ownershipLossDuringFullscreenWaitSupersedesTheCommandWithoutAnotherRelease() {
        val f = fixture(); f.ordinary(); f.settle()
        val c = f.handle.snapshot().content
        val x = c.left + 100f; val y = c.bottom + 10f
        f.handle.post(WindowEvent.Input(WindowInputEvent.Press(f.key.windowId, f.clock.now, RegionId.BAR_BOTTOM, x, y)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Move(f.key.windowId, f.clock.now + 10, RegionId.BAR_BOTTOM, x, y + 500)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Up(f.key.windowId, f.clock.now + 20, RegionId.BAR_BOTTOM, x, y + 500)))
        f.settle()
        val oldResult = requireNotNull(f.fullscreenResult)
        f.handle.post(WindowEvent.TaskOwnershipLost)
        oldResult(FullscreenHandoffResult(CommandStatus.FAILED, false, false))
        assertTrue(f.handle.destroyed)
        assertEquals(listOf(false to false), f.finished)
        assertEquals(1, f.releaseNotifications)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun fullscreenReleaseAndCompletionAreCorrelatedAndNeverReleaseTheTaskTwice() {
        val f = fixture(); f.ordinary(); f.settle()
        val c = f.handle.snapshot().content
        val x = c.left + 100f; val y = c.bottom + 10f
        f.handle.post(WindowEvent.Input(WindowInputEvent.Press(f.key.windowId, f.clock.now, RegionId.BAR_BOTTOM, x, y)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Move(f.key.windowId, f.clock.now + 10, RegionId.BAR_BOTTOM, x, y + 500)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Up(f.key.windowId, f.clock.now + 20, RegionId.BAR_BOTTOM, x, y + 500)))
        f.settle()
        assertNotNull(f.fullscreenResult)
        val complete = f.fullscreenResult!!; val released = f.releasedCallback!!
        released(); complete(FullscreenHandoffResult(CommandStatus.DONE, true))
        f.settle()
        released(); complete(FullscreenHandoffResult(CommandStatus.FAILED, false))
        assertEquals(1, f.releaseNotifications)
        assertEquals(listOf(false to true), f.finished)
        assertTrue(f.failures.isEmpty())
    }
}
