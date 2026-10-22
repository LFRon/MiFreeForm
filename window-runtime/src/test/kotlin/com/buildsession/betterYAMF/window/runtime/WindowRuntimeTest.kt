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
        val activations = mutableListOf<Pair<Long, CommandStatus>>()
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
                override fun activated(requestId: Long, status: CommandStatus) { activations += requestId to status }
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

    @Test fun notificationEntryCompletionRequiresACommittedInteractiveLiveFrame() {
        val f = fixture(autoCommit = false)
        f.handle.post(WindowEvent.Entry(EntryRequest(origin = EntryOrigin.NOTIFICATION)))
        f.handle.post(WindowEvent.Activate(901))
        f.ordinary()
        f.settle()
        assertTrue(f.activations.isEmpty())
        assertFalse(f.handle.snapshot().acceptsContent)
        f.commits.last()(CommandStatus.DONE) // Terminal geometry only reopens input in the NEXT frame.
        assertTrue(f.handle.snapshot().acceptsContent)
        assertTrue(f.activations.isEmpty())
        f.commits.last()(CommandStatus.DONE)
        assertEquals(listOf(901L to CommandStatus.DONE), f.activations)
        assertEquals(1, f.raises)
    }

    @Test fun reactivationHidesBeforePreparingAndOldCompletionCannotFinishTheNextRequest() {
        val f = fixture(autoPrepare = false, autoCommit = false)
        f.ordinary(); f.prepareResult!!(CommandStatus.DONE); f.settle()
        f.commits.last()(CommandStatus.DONE)
        val oldFrame = f.commits.last()
        f.handle.post(WindowEvent.Activate(11))
        assertFalse(f.handle.snapshot().acceptsContent)
        assertEquals(0f, f.handle.snapshot().taskAlpha, 0f)
        assertEquals(1, f.preparations.size)
        oldFrame(CommandStatus.DONE)
        assertEquals(1, f.preparations.size)
        f.commits.last()(CommandStatus.DONE)
        assertEquals(2, f.preparations.size)
        f.handle.post(WindowEvent.Activate(12))
        f.handle.post(WindowEvent.CancelActivation(11))
        assertEquals(listOf(11L to CommandStatus.SUPERSEDED), f.activations)
        f.prepareResult!!(CommandStatus.DONE); f.settle()
        f.commits.last()(CommandStatus.DONE)
        assertTrue(f.handle.snapshot().acceptsContent)
        assertEquals(1, f.activations.size)
        f.commits.last()(CommandStatus.DONE)
        f.commits.last()(CommandStatus.FAILED) // Duplicate terminal callback is discarded.
        assertEquals(listOf(11L to CommandStatus.SUPERSEDED, 12L to CommandStatus.DONE), f.activations)
        assertFalse(f.handle.destroyed)
    }

    @Test fun activationFailureAndCancellationNeverReportLaunchAcceptanceAsSuccess() {
        val f = fixture(autoPrepare = false)
        f.ordinary(); f.handle.post(WindowEvent.Activate(31))
        f.prepareResult!!(CommandStatus.FAILED)
        assertEquals(listOf(31L to CommandStatus.FAILED), f.activations)
        assertTrue(f.handle.destroyed)
        assertEquals(listOf(true to false), f.finished)
        val other = fixture()
        other.handle.post(WindowEvent.Activate(32))
        other.handle.post(WindowEvent.CancelActivation(32))
        other.ordinary(); other.settle()
        assertEquals(listOf(32L to CommandStatus.SUPERSEDED), other.activations)
        assertTrue(other.handle.snapshot().acceptsContent)
        assertFalse(other.handle.destroyed)
    }

    @Test fun notificationFailuresReleaseInBackgroundWhileRecentsStillRestoresTheOriginalApp() {
        for (origin in EntryOrigin.entries) for (adopted in listOf(false, true)) {
            val f = fixture(autoPrepare = false)
            f.handle.post(WindowEvent.Entry(EntryRequest(origin = origin)))
            if (adopted) {
                f.ordinary()
                f.prepareResult!!(CommandStatus.FAILED)
            } else {
                f.adoptionAccepted = false
                assertFalse(f.handle.activateTask())
            }
            assertTrue(f.handle.destroyed)
            assertEquals(listOf(true to (origin == EntryOrigin.RECENTS)), f.finished)
        }
    }

    @Test fun notificationPresenterExceptionsCannotBringTheFailedAppToForeground() {
        val f = fixture()
        f.handle.post(WindowEvent.Entry(EntryRequest(origin = EntryOrigin.NOTIFICATION)))
        f.duringPresent = { error("native presenter failed") }
        f.ordinary()
        assertTrue(f.handle.destroyed)
        assertEquals(listOf(true to false), f.finished)
    }

    @Test fun activationRestoresAnEdgeIconWithoutCreatingASecondSessionOrViewport() {
        val f = fixture(); f.ordinary(); f.settle()
        val id = f.key.windowId
        f.handle.post(WindowEvent.Input(WindowInputEvent.Press(id, 1, RegionId.HANDLE, 500f, 1300f)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Move(id, 2, RegionId.HANDLE, 1260f, 1300f)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Up(id, 3, RegionId.HANDLE, 1260f, 1300f)))
        f.settle()
        assertNotNull(f.handle.snapshot().sideStash)
        val viewport = f.adopted.single().logicalViewport
        f.handle.post(WindowEvent.Activate(77)); f.settle()
        assertNull(f.handle.snapshot().sideStash)
        assertTrue(f.handle.snapshot().acceptsContent)
        assertEquals(listOf(77L to CommandStatus.DONE), f.activations)
        assertEquals(1, f.adopted.size)
        assertEquals(viewport, f.preparations.last().logicalViewport)
        assertTrue(f.finished.isEmpty())
    }

    @Test fun activationDuringIconRestorationContinuesTheRestoreAcrossPreparation() {
        for (waitingForCommit in listOf(false, true)) {
            val f = fixture(autoPrepare = false, autoCommit = false)
            f.ordinary(); f.prepareResult!!(CommandStatus.DONE); f.settle()
            f.commits.last()(CommandStatus.DONE)
            val id = f.key.windowId
            f.handle.post(WindowEvent.Input(WindowInputEvent.Press(id, f.clock.now, RegionId.HANDLE, 500f, 1300f)))
            f.handle.post(WindowEvent.Input(WindowInputEvent.Move(id, f.clock.now + 10, RegionId.HANDLE, 1260f, 1300f)))
            f.handle.post(WindowEvent.Input(WindowInputEvent.Up(id, f.clock.now + 20, RegionId.HANDLE, 1260f, 1300f)))
            f.settle(); f.commits.last()(CommandStatus.DONE)
            val icon = checkNotNull(f.handle.snapshot().input.bounds)
            val x = icon.left + icon.width / 2f; val y = icon.top + icon.height / 2f
            f.handle.post(WindowEvent.Input(WindowInputEvent.Press(id, f.clock.now, RegionId.STASH_HANDLE, x, y)))
            f.handle.post(WindowEvent.Input(WindowInputEvent.Up(id, f.clock.now + 10, RegionId.STASH_HANDLE, x, y)))
            if (waitingForCommit) f.settle()
            assertNotNull(f.handle.snapshot().sideStash)
            assertFalse(f.handle.snapshot().acceptsContent)

            f.handle.post(WindowEvent.Activate(78))
            assertEquals(0f, f.handle.snapshot().taskAlpha, 0f)
            f.commits.last()(CommandStatus.DONE)
            assertEquals(2, f.preparations.size)
            f.prepareResult!!(CommandStatus.DONE); f.settle()
            f.commits.last()(CommandStatus.DONE)
            assertNull("Restoring icon must not be stashed again", f.handle.snapshot().sideStash)
            assertTrue(f.handle.snapshot().acceptsContent)
            assertTrue(f.activations.isEmpty())
            f.commits.last()(CommandStatus.DONE)
            assertEquals(listOf(78L to CommandStatus.DONE), f.activations)
            assertEquals(1, f.adopted.size)
            assertTrue(f.failures.isEmpty())
        }
    }

    @Test fun anActivationCannotWaitOnOrReacquireATaskAlreadyBeingHandedToFullscreen() {
        val f = fixture(); f.ordinary(); f.settle()
        val c = f.handle.snapshot().content
        val x = c.left + 100f; val y = c.bottom + 10f
        f.handle.post(WindowEvent.Input(WindowInputEvent.Press(f.key.windowId, f.clock.now, RegionId.BAR_BOTTOM, x, y)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Move(f.key.windowId, f.clock.now + 10, RegionId.BAR_BOTTOM, x, y + 500)))
        f.handle.post(WindowEvent.Input(WindowInputEvent.Up(f.key.windowId, f.clock.now + 20, RegionId.BAR_BOTTOM, x, y + 500)))
        f.settle()
        assertNotNull(f.fullscreenResult)
        val prepares = f.preparations.size
        f.handle.post(WindowEvent.Activate(88))
        assertEquals(listOf(88L to CommandStatus.SUPERSEDED), f.activations)
        assertEquals(prepares, f.preparations.size)
        f.fullscreenResult!!(FullscreenHandoffResult(CommandStatus.DONE, true)); f.settle()
        assertTrue(f.handle.destroyed)
        assertEquals(1, f.activations.size)
    }

    @Test fun synchronousPreparationDoesNotCancelAnAlreadyCompletedOperation() {
        val f = fixture(); f.immediateResourceCallback = true
        assertTrue(f.handle.activateTask())
        f.settle()
        assertEquals(1, f.preparations.size)
        assertEquals(0, f.prepareCancelled)
        assertTrue(f.handle.snapshot().acceptsContent)
    }

    @Test fun sideIconsUseTheSameSessionAndRestorationFenceWithSyncOrAsyncPresenters() {
        for (auto in listOf(true, false)) {
            val f = fixture(autoCommit = auto)
            f.ordinary(); f.settle()
            if (!auto) f.commits.last()(CommandStatus.DONE)
            val id = f.key.windowId
            fun input(event: WindowInputEvent) = f.handle.post(WindowEvent.Input(event))
            input(WindowInputEvent.Press(id, f.clock.now, RegionId.HANDLE, 600f, 1300f))
            f.clock.now += 1000
            input(WindowInputEvent.Move(id, f.clock.now, RegionId.HANDLE, 1260f, 1300f))
            f.clock.now += 1000
            input(WindowInputEvent.Up(id, f.clock.now, RegionId.HANDLE, 1260f, 1300f))
            f.settle()
            if (!auto) f.commits.last()(CommandStatus.DONE)
            val hidden = f.handle.snapshot()
            assertFalse(hidden.taskVisible || hidden.taskFocusable || hidden.acceptsContent)
            val rect = requireNotNull(hidden.input.bounds)
            val x = rect.left + rect.width / 2f; val y = rect.top + rect.height / 2f
            val old = f.commits.last()
            input(WindowInputEvent.Press(id, f.clock.now, RegionId.STASH_HANDLE, x, y))
            input(WindowInputEvent.Up(id, f.clock.now + 10, RegionId.STASH_HANDLE, x, y))
            f.settle()
            if (!auto) {
                old(CommandStatus.DONE)
                assertFalse(f.handle.snapshot().taskFocusable)
                assertFalse(f.handle.snapshot().acceptsContent)
                f.commits.last()(CommandStatus.DONE)
            }
            assertNull(f.handle.snapshot().sideStash)
            assertTrue(f.handle.snapshot().taskVisible && f.handle.snapshot().taskFocusable && f.handle.snapshot().acceptsContent)
            assertEquals(1, f.adopted.size)
            assertEquals(1, f.preparations.size)
            assertTrue(f.finished.isEmpty() && f.failures.isEmpty())
            assertTrue(f.frames.all { it.session == f.key })
        }
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
        assertTrue(actual.normalContent.width < actual.normalContent.height)
        assertEquals(1264, actual.logicalViewport.width)
        assertEquals(ContentOrientation.PORTRAIT, actual.contentOrientation)
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
        assertTrue(layout.normalContent.width < layout.normalContent.height)
        assertEquals(1264, layout.logicalViewport.width)
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

    @Test fun automaticFocusQueriesCannotRaiseOrMutateAnyOfSeveralLiveWindowSessions() {
        val first = fixture(); val second = fixture(); val third = fixture()
        val all = listOf(first, second, third)
        all.forEach { it.ordinary(); it.settle() }
        val before = all.map { it.handle.snapshot() }
        // The native task-selection lock cannot wait for the serial scene owner. This query
        // consumes only its supplied current fact and is safe without entering any scene pump.
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val caller = Thread {
            try {
                all.forEach {
                    assertFalse(WindowRuntimes.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC, true)))
                }
                assertTrue(WindowRuntimes.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC, false)))
                assertTrue(WindowRuntimes.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.DIRECT, true)))
            } catch (failure: Throwable) { error.set(failure) }
        }
        caller.start(); caller.join(5000)
        assertFalse(caller.isAlive)
        error.get()?.let { throw it }
        all.forEach { it.handle.post(WindowEvent.HomeForeground(HomeMaintenanceFacts(true, true, false, false))) }
        assertEquals(before, all.map { it.handle.snapshot() })
        assertTrue(all.all { it.raises == 0 && it.finished.isEmpty() && it.failures.isEmpty() })
    }

    @Test fun fullscreenAndLateCallbacksInOneWindowCannotCancelOrPrepareAnotherWindow() {
        val first = fixture(); val second = fixture(autoPrepare = false)
        first.ordinary(); first.settle()
        second.ordinary()
        val secondReady = requireNotNull(second.prepareResult)
        val c = first.handle.snapshot().content
        val x = c.left + 100f; val y = c.bottom + 10f
        first.handle.post(WindowEvent.Input(WindowInputEvent.Press(first.key.windowId, first.clock.now, RegionId.BAR_BOTTOM, x, y)))
        first.handle.post(WindowEvent.Input(WindowInputEvent.Move(first.key.windowId, first.clock.now + 10, RegionId.BAR_BOTTOM, x, y + 500)))
        first.handle.post(WindowEvent.Input(WindowInputEvent.Up(first.key.windowId, first.clock.now + 20, RegionId.BAR_BOTTOM, x, y + 500)))
        first.settle()
        requireNotNull(first.releasedCallback).invoke()
        requireNotNull(first.fullscreenResult).invoke(FullscreenHandoffResult(CommandStatus.DONE, true))
        first.settle()
        assertTrue(first.handle.destroyed)
        assertFalse(second.handle.destroyed)
        secondReady(CommandStatus.DONE); second.settle()
        val secondFrame = second.handle.snapshot()
        first.commits.last()(CommandStatus.FAILED)
        first.handle.post(WindowEvent.TaskOwnershipLost)
        assertEquals(secondFrame, second.handle.snapshot())
        assertTrue(secondFrame.acceptsContent)
        assertEquals(1, second.preparations.size)
        assertTrue(second.finished.isEmpty() && second.failures.isEmpty())
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
