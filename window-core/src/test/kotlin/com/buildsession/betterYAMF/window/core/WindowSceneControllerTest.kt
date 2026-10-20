package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import org.junit.Assert.*
import org.junit.Test

class WindowSceneControllerTest {
    private val metrics = SceneMetrics(1264, 2780, 130, 80)
    private val normal = SceneRect(180f, 700f, 898f, 1139f)
    private val style = SceneStyle(3.2f, 20f, 12f)

    @Test fun aHiddenFirstBridgeCannotRetireTheNativeSourceBeforeAVisibleReplacementCommits() {
        val d = Driver().apply { autoPresent = false }
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        val original = d.presentations.last()
        d.send(WindowSceneEvent.Visibility(false))
        d.send(WindowSceneEvent.FrameCommitted(original.frameId, CommandStatus.DONE))
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertTrue(d.effects.none { it is WindowSceneEffect.SourceHandoffResult })
        d.send(WindowSceneEvent.Visibility(true))
        assertTrue(d.effects.none { it is WindowSceneEffect.SourceHandoffResult })
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.count { it is WindowSceneEffect.SourceHandoffResult })
    }

    @Test fun retainedSourcePresentsBeforeAdoptionWithItsActualCornersAndWaitsForNativeFinish() {
        val d = Driver()
        val source = SceneRect(493f, 113f, 429f, 942f)
        d.send(WindowSceneEvent.OpenAsMini(source, awaitSourceRelease = true, sourceCornerRadius = 29f))
        d.send(WindowSceneEvent.VisualAvailable)
        val first = d.presentations.single().frame
        assertEquals(source, first.content)
        assertEquals(source, first.outer)
        assertEquals(29f, first.contentRadius, 0f)
        assertEquals(29f, first.outerRadius, 0f)
        assertEquals(1f, first.alpha, 0f)
        assertEquals(0f, first.controlsAlpha, 0f)
        assertFalse(first.acceptsContent)
        assertNull(d.requested)
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().size)
        d.send(WindowSceneEvent.SourceReleased)
        d.settle() // The live application can still be drawing its new viewport.
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
        assertEquals(style.miniCorner - style.bezel, d.scene.snapshot().contentRadius, 0f)
        val beforeInput = d.scene.snapshot()
        d.tap()
        assertEquals(beforeInput, d.scene.snapshot())
        d.ready()
        d.settle() // A late application draw still replaces the retained pixels before input opens.
        d.tap()
        d.settle()
        assertEquals(normal, d.scene.snapshot().content)
        assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun livePreparationDuringTheRetainedFlightDoesNotRestartOrRecenterIt() {
        val d = Driver()
        val source = SceneRect(493f, 113f, 429f, 942f)
        d.send(WindowSceneEvent.OpenAsMini(source, awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.SourceReleased)
        repeat(5) { d.tick() }
        val pose = d.scene.snapshot()
        d.ready()
        assertEquals(pose.content, d.scene.snapshot().content)
        assertEquals(pose.miniFraction, d.scene.snapshot().miniFraction, 0f)
        d.settle()
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
        assertTrue(d.presentations.none { it.frame.content == normal })
    }

    @Test fun aRetainedSourceSeedsItsNativeReleaseVelocity() {
        fun position(velocity: Float): Float {
            val d = Driver()
            d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f),
                awaitSourceRelease = true, velocityX = velocity))
            d.send(WindowSceneEvent.VisualAvailable)
            d.send(WindowSceneEvent.SourceReleased)
            d.tick(); d.tick()
            return d.scene.snapshot().content.left
        }
        assertTrue(position(2000f) > position(0f) + 5f)
    }

    @Test fun landscapeEntryChoosesFinalPortraitViewportBeforeAdoptingTheApplication() {
        val d = Driver(initialMetrics = SceneMetrics(2780, 1264, 100, 70),
            initialContent = SceneRect(400f, 300f, 1973f, 518f))
        val source = SceneRect(493f, 113f, 429f, 942f)
        d.send(WindowSceneEvent.OpenAsMini(source, awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.Metrics(metrics))
        // The former path kept this short landscape aspect even after returning to portrait.
        assertTrue(d.scene.normalViewport().height < 400f)
        val effects = d.send(WindowSceneEvent.EntryLayout(metrics, 898, 1139))
        val viewport = d.scene.normalViewport()
        assertEquals(898f, viewport.width, 0.01f)
        assertEquals(1139f, viewport.height, 0.01f)
        assertEquals(source, d.scene.snapshot().content)
        assertEquals(1, effects.count { it == WindowSceneEffect.ActivateEntry })
        assertNull(d.requested)
        val preparation = d.send(WindowSceneEvent.TaskAvailable).filterIsInstance<WindowSceneEffect.Prepare>().single()
        assertEquals(viewport, preparation.normalContent)
        d.send(WindowSceneEvent.Prepared(preparation.commandId, CommandStatus.DONE))
        assertNull(d.requested)
        d.send(WindowSceneEvent.SourceReleased)
        d.settle(); d.tap(); d.settle()
        assertEquals(viewport.rounded(), d.scene.snapshot().content.rounded())
        assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun duplicateDestinationReadinessCannotAdoptOrChangeTheViewportTwice() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.EntryLayout(metrics, 898, 1139))
        val selected = d.scene.normalViewport()
        assertTrue(d.send(WindowSceneEvent.EntryLayout(metrics, 500, 300)).isEmpty())
        assertEquals(selected, d.scene.normalViewport())
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ActivateEntry })
    }

    @Test fun aRotatingHomeSelectsTheLandscapeViewportInsteadOfForcingPortrait() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(300f, 200f, 700f, 400f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.EntryLayout(SceneMetrics(2780, 1264, 100, 70), 1973, 518))
        val preparation = d.send(WindowSceneEvent.TaskAvailable).filterIsInstance<WindowSceneEffect.Prepare>().single()
        assertEquals(1973f, preparation.normalContent.width, 0.01f)
        assertEquals(518f, preparation.normalContent.height, 0.01f)
        assertFalse(d.scene.snapshot().portrait)
        assertNull(d.requested)
    }

    @Test fun disabledAnimationsStillAdoptTheFinalViewportAndWaitForSourceRelease() {
        val d = Driver(animatorScale = 0f)
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        val result = d.send(WindowSceneEvent.EntryLayout(metrics, 898, 1139))
        assertEquals(1, result.count { it == WindowSceneEffect.ActivateEntry })
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
        assertFalse(d.scene.snapshot().acceptsContent)
        d.ready()
        d.tap()
        assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
        d.send(WindowSceneEvent.SourceReleased)
        d.tap()
        assertEquals(898f, d.scene.snapshot().content.width, 0.01f)
        assertEquals(1139f, d.scene.snapshot().content.height, 0.01f)
        assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun destinationCannotReshapeAnAlreadyPreparingTask() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        val preparation = d.send(WindowSceneEvent.TaskAvailable).filterIsInstance<WindowSceneEffect.Prepare>().single()
        assertTrue(d.send(WindowSceneEvent.EntryLayout(metrics, 500, 300)).isEmpty())
        assertEquals(normal, d.scene.normalViewport())
        d.send(WindowSceneEvent.Prepared(preparation.commandId, CommandStatus.DONE))
        assertTrue(d.send(WindowSceneEvent.EntryLayout(metrics, 500, 300)).isEmpty())
        assertEquals(normal, d.scene.normalViewport())
    }

    @Test fun aLateDestinationAfterCancellationCannotAcquireTheTask() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.Destroy)
        assertTrue(d.send(WindowSceneEvent.EntryLayout(metrics, 898, 1139)).isEmpty())
        assertTrue(d.effects.none { it == WindowSceneEffect.ActivateEntry })
    }

    @Test fun destinationLayoutDoesNotAffectAnOrdinaryWindow() {
        val d = Driver(); d.ready(); d.settle()
        val before = d.scene.snapshot()
        assertTrue(d.send(WindowSceneEvent.EntryLayout(metrics, 500, 300)).isEmpty())
        assertEquals(before, d.scene.snapshot())
    }

    @Test fun oversizedInitialWindowFitsItsDestinationIncludingBothOrientationsChrome() {
        for (m in listOf(metrics, SceneMetrics(2780, 1264, 100, 70))) {
            val c = WindowSceneController.initialContent(m, style, 10000, 8000, 99999)
            val portrait = m.height >= m.width
            val rightBar = if (portrait) 0f else style.sideBar
            val bottomBar = if (portrait) style.bottomBar else 0f
            assertEquals(10000f / 8000f, c.width / c.height, 0.001f)
            assertTrue(c.left >= style.margin - 0.01f)
            assertTrue(c.right + rightBar <= m.width - style.margin + 0.01f)
            assertTrue(c.top - style.topBar >= m.topInset + style.margin - 0.01f)
            assertTrue(c.bottom + bottomBar <= m.height - m.bottomInset - style.margin + 0.01f)
        }
    }

    @Test fun rotatingBackRestoresThePreferredNormalSizeIncludingWhileMini() {
        for (mini in listOf(false, true)) {
            val d = Driver(); d.ready(); d.settle()
            if (mini) { d.enter(); d.settle() }
            repeat(3) {
                d.send(WindowSceneEvent.Metrics(SceneMetrics(2780, 1264, 100, 70)))
                d.settle()
                assertTrue(d.scene.normalViewport().height < normal.height)
                d.send(WindowSceneEvent.Metrics(metrics))
                d.settle()
                assertEquals(normal.width, d.scene.normalViewport().width, 0.01f)
                assertEquals(normal.height, d.scene.normalViewport().height, 0.01f)
            }
            if (mini) { d.tap(); d.settle() }
            assertEquals(normal.width, d.scene.snapshot().content.width, 0.01f)
            assertEquals(normal.height, d.scene.snapshot().content.height, 0.01f)
        }
    }

    @Test fun anExplicitResizeUpdatesTheSizeRememberedAcrossDisplayConstraints() {
        val d = Driver(); d.ready(); d.settle()
        d.press(RegionId.RESIZE_HANDLE, normal.right, normal.bottom)
        d.input(WindowInputEvent.Move(1, d.time, RegionId.RESIZE_HANDLE, normal.right - 90f, normal.bottom - 120f))
        d.input(WindowInputEvent.Up(1, d.time, RegionId.RESIZE_HANDLE, normal.right - 90f, normal.bottom - 120f))
        d.settle()
        val resized = d.scene.normalViewport()
        assertTrue(resized.width < normal.width)
        d.send(WindowSceneEvent.Metrics(SceneMetrics(2780, 1264, 100, 70)))
        d.settle()
        d.send(WindowSceneEvent.Metrics(metrics))
        d.settle()
        assertEquals(resized.width, d.scene.normalViewport().width, 0.01f)
        assertEquals(resized.height, d.scene.normalViewport().height, 0.01f)
    }

    private fun entryWaitingForLiveCommit(): Driver {
        val d = Driver(animatorScale = 0f)
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.ready()
        assertTrue(d.scene.snapshot().showEntryImage)
        assertEquals(0f, d.scene.snapshot().taskAlpha, 0f)
        d.autoPresent = false
        d.send(WindowSceneEvent.SourceReleased)
        return d
    }

    @Test fun sourcePixelsAndInputWaitForTheExactLiveReplacementCommit() {
        val d = entryWaitingForLiveCommit()
        val replacement = d.presentations.last()
        assertFalse(replacement.frame.showEntryImage)
        assertEquals(1f, replacement.frame.taskAlpha, 0f)
        assertTrue(d.scene.isAnimating())
        d.press(RegionId.BODY_MASK)
        d.tap()
        assertEquals(replacement.frame, d.scene.snapshot())
        assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
        assertFalse(d.effects.contains(WindowSceneEffect.ReleaseEntryImage))
        d.send(WindowSceneEvent.FrameCommitted(replacement.frameId - 1, CommandStatus.DONE))
        assertFalse(d.effects.contains(WindowSceneEffect.ReleaseEntryImage))
        d.send(WindowSceneEvent.FrameCommitted(replacement.frameId, CommandStatus.DONE))
        d.send(WindowSceneEvent.FrameCommitted(replacement.frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
        assertFalse(d.scene.isAnimating())
        d.tap()
        assertEquals(ShellMode.NORMAL, d.scene.shellSnapshot().mode)
    }

    @Test fun coalescedReplacementMustCommitItsCurrentFrameBeforeReleasingTheImage() {
        val d = entryWaitingForLiveCommit()
        val old = d.presentations.last()
        d.send(WindowSceneEvent.PresentationInvalidated)
        val current = d.presentations.last()
        d.send(WindowSceneEvent.FrameCommitted(old.frameId, CommandStatus.DONE))
        assertFalse(d.effects.contains(WindowSceneEffect.ReleaseEntryImage))
        d.send(WindowSceneEvent.FrameCommitted(current.frameId, CommandStatus.SUPERSEDED))
        val retry = d.presentations.last()
        assertTrue(retry.frameId > current.frameId)
        d.send(WindowSceneEvent.FrameCommitted(current.frameId, CommandStatus.DONE))
        assertFalse(d.effects.contains(WindowSceneEffect.ReleaseEntryImage))
        d.send(WindowSceneEvent.FrameCommitted(retry.frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
    }

    @Test fun aHiddenReplacementCannotReleaseTheImageOrOpenInput() {
        val d = entryWaitingForLiveCommit()
        val old = d.presentations.last()
        d.send(WindowSceneEvent.Visibility(false))
        val hidden = d.presentations.last()
        assertEquals(0f, hidden.frame.taskAlpha, 0f)
        d.send(WindowSceneEvent.FrameCommitted(old.frameId, CommandStatus.DONE))
        d.send(WindowSceneEvent.FrameCommitted(hidden.frameId, CommandStatus.DONE))
        assertFalse(d.effects.contains(WindowSceneEffect.ReleaseEntryImage))
        d.tap()
        assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
        d.send(WindowSceneEvent.Visibility(true))
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
    }

    @Test fun failedOrDestroyedReplacementCannotBeResurrectedByALateCommit() {
        for (fail in listOf(false, true)) {
            val d = entryWaitingForLiveCommit()
            val replacement = d.presentations.last()
            if (fail) {
                val result = d.send(WindowSceneEvent.FrameCommitted(replacement.frameId, CommandStatus.FAILED))
                assertTrue(result.any { it is WindowSceneEffect.Failed })
                d.tap()
                assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
            }
            d.send(WindowSceneEvent.Destroy)
            assertTrue(d.send(WindowSceneEvent.FrameCommitted(replacement.frameId, CommandStatus.DONE)).isEmpty())
            assertTrue(d.send(WindowSceneEvent.Destroy).isEmpty())
            assertFalse(d.effects.contains(WindowSceneEffect.ReleaseEntryImage))
        }
    }

    @Test fun livePreparationKeepsTheTaskHiddenAndInputClosedThroughoutTheRetainedFlight() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.ready()
        d.send(WindowSceneEvent.SourceReleased)
        repeat(5) { d.tick() }
        val moving = d.scene.snapshot()
        assertTrue(moving.showEntryImage)
        assertEquals(0f, moving.taskAlpha, 0f)
        d.tap()
        assertEquals(moving, d.scene.snapshot())
        d.settle()
        assertFalse(d.scene.snapshot().showEntryImage)
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
    }

    @Test fun sourceCoversTheChangingViewportAndPreservesItsHeaderWithoutLetterboxing() {
        val d = Driver()
        val source = SceneRect(493f, 113f, 429f, 942f)
        d.send(WindowSceneEvent.OpenAsMini(source, awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.SourceReleased)
        d.settle()
        d.presentations.forEach { presentation ->
            val frame = presentation.frame
            val image = requireNotNull(frame.entryImage)
            assertEquals(source.width / source.height, image.bounds.width / image.bounds.height, 0.0001f)
            assertTrue(image.bounds.left <= frame.content.left + 0.01f)
            assertEquals(frame.content.top, image.bounds.top, 0f)
            assertTrue(image.bounds.right >= frame.content.right - 0.01f)
            assertTrue(image.bounds.bottom >= frame.content.bottom - 0.01f)
            assertEquals(frame.content, image.clip)
            assertEquals(0f, image.featherPx, 0f)
            assertEquals(ContentAlignment.TOP, frame.contentAlignment)
        }
        assertEquals(0f, d.scene.snapshot().taskAlpha, 0f)
    }

    @Test fun sourceProjectionUsesPixelAspectRatherThanRoundedCardAspect() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f),
            awaitSourceRelease = true, sourceImageAspect = 0.5f))
        d.send(WindowSceneEvent.VisualAvailable)
        assertEquals(0.5f, d.scene.snapshot().entryImage!!.bounds.let { it.width / it.height }, 0.0001f)
    }

    @Test fun anUndrawnFirstBridgeRetriesWithoutAcknowledgingTheSourceOrReleasingPixels() {
        val d = Driver().apply { autoPresent = false }
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        val first = d.presentations.last()
        d.send(WindowSceneEvent.FrameCommitted(first.frameId, CommandStatus.SUPERSEDED))
        val retry = d.presentations.last()
        assertTrue(retry.frameId > first.frameId)
        assertEquals(first.frame, retry.frame)
        assertTrue(d.effects.none { it is WindowSceneEffect.SourceHandoffResult || it == WindowSceneEffect.ReleaseEntryImage })
        d.send(WindowSceneEvent.FrameCommitted(retry.frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.count { it is WindowSceneEffect.SourceHandoffResult })
        assertTrue(d.effects.none { it == WindowSceneEffect.ReleaseEntryImage })
    }

    @Test fun preparedContentReplacesOnlyAnEdgeDuringTheFlightWithoutRestartingGeometryOrReflowing() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.SourceReleased)
        d.ready()
        repeat(50) {
            if (d.scene.snapshot().entryImage?.clip == d.scene.snapshot().content) d.tick()
        }
        val replacing = d.scene.snapshot()
        val image = requireNotNull(replacing.entryImage)
        assertTrue(image.clip.height > 0f && image.clip.height < replacing.content.height)
        assertTrue(image.featherPx > 0f && image.featherPx <= 2f)
        assertEquals(replacing.content.top, image.clip.top, 0f)
        assertEquals(replacing.content.width, image.clip.width, 0f)
        assertEquals(ContentAlignment.TOP, replacing.contentAlignment)
        assertEquals(1f, replacing.taskAlpha, 0f) // Opaque source and task outside the narrow seam.
        assertTrue(replacing.miniFraction < 1f)
        val beforeInput = d.scene.snapshot()
        d.tap()
        assertEquals(beforeInput, d.scene.snapshot())
        d.tick()
        assertTrue(d.scene.snapshot().content.left > replacing.content.left)
        assertEquals(normal, d.scene.normalViewport())
        d.settle()
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
        assertEquals(ContentAlignment.CENTER, d.scene.snapshot().contentAlignment)
    }

    @Test fun latePreparationReplacesAtTheSettledPoseAndSchedulesItsOwnFrames() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.SourceReleased)
        d.settle()
        val settled = d.scene.snapshot().content
        d.ready()
        assertNotNull(d.requested)
        assertEquals(settled, d.scene.snapshot().entryImage!!.clip)
        repeat(5) { d.tick() }
        assertTrue(d.scene.snapshot().entryImage!!.clip.height < settled.height)
        assertEquals(settled, d.scene.snapshot().content)
        d.settle()
        assertFalse(d.scene.snapshot().showEntryImage)
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
        d.tap(); d.settle()
        assertEquals(normal, d.scene.snapshot().content)
    }

    @Test fun replacementNeverUncoversAnUnpreparedTaskOrReversesItsEdge() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.SourceReleased)
        repeat(18) { d.tick() }
        assertEquals(d.scene.snapshot().content, d.scene.snapshot().entryImage!!.clip)
        assertEquals(0f, d.scene.snapshot().entryImage!!.featherPx, 0f)
        assertEquals(0f, d.scene.snapshot().taskAlpha, 0f)
        d.ready()
        var previous = 1f
        repeat(100) {
            val f = d.scene.snapshot()
            val remaining = f.entryImage?.let { it.clip.height / f.content.height } ?: 0f
            assertTrue(remaining <= previous + 0.0001f)
            previous = remaining
            d.tick()
        }
        assertEquals(0f, previous, 0f)
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.Prepare>().size)
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
    }

    @Test fun aCommittedReplacementIsNotStarvedByNewerPositionFrames() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(80f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        d.send(WindowSceneEvent.SourceReleased)
        d.ready()
        d.autoPresent = false
        repeat(100) { if (d.scene.snapshot().showEntryImage) d.tick() }
        val replacement = d.presentations.last()
        assertFalse(replacement.frame.showEntryImage)
        assertTrue(d.effects.none { it == WindowSceneEffect.ReleaseEntryImage })
        d.tick()
        assertTrue(d.presentations.last().frameId > replacement.frameId)
        d.send(WindowSceneEvent.FrameCommitted(replacement.frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.count { it == WindowSceneEffect.ReleaseEntryImage })
        assertTrue(d.scene.isAnimating()) // Image ownership and geometry completion are distinct.
        val pose = d.scene.snapshot()
        d.tap()
        assertEquals(pose, d.scene.snapshot())
        d.autoPresent = true
        d.settle()
    }

    @Test fun cancellationAfterTheBridgeAckIsASeparateOperationWithOneTerminalResult() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(SceneRect(493f, 113f, 429f, 942f), awaitSourceRelease = true))
        d.send(WindowSceneEvent.VisualAvailable)
        val prepare = d.send(WindowSceneEvent.TaskAvailable).single() as WindowSceneEffect.Prepare
        val failure = d.send(WindowSceneEvent.Prepared(prepare.commandId, CommandStatus.FAILED))
        assertEquals(WindowSceneEffect.CancelSourceHandoff(CommandStatus.FAILED), failure.first())
        d.send(WindowSceneEvent.Destroy)
        assertEquals(listOf(CommandStatus.DONE),
            d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().map { it.status })
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.CancelSourceHandoff>().size)
    }

    private inner class Driver(animatorScale: Float = 1f, coloredController: Boolean = false,
        initialMetrics: SceneMetrics = metrics, initialContent: SceneRect = normal) {
        val scene = WindowSceneController(1, style.copy(animatorScale = animatorScale, coloredController = coloredController), initialMetrics, initialContent)
        var requested: Long? = null
        var time = 0L
        var autoPresent = true
        val presentations = mutableListOf<WindowSceneEffect.Present>()
        val effects = mutableListOf<WindowSceneEffect>()

        fun send(event: WindowSceneEvent): List<WindowSceneEffect> {
            val result = scene.onEvent(event)
            effects += result
            result.forEach {
                when (it) {
                    is WindowSceneEffect.RequestFrame -> requested = it.generation
                    is WindowSceneEffect.Present -> {
                        presentations += it
                        if (autoPresent) send(WindowSceneEvent.FrameCommitted(it.frameId, CommandStatus.DONE))
                    }
                    else -> Unit
                }
            }
            return result
        }
        fun ready() {
            val prepare = send(WindowSceneEvent.TaskAvailable).filterIsInstance<WindowSceneEffect.Prepare>().single()
            send(WindowSceneEvent.Prepared(prepare.commandId, CommandStatus.DONE))
        }
        fun input(event: WindowInputEvent) = send(WindowSceneEvent.Input(event))
        fun tick() {
            val generation = requested ?: return
            requested = null
            time += 16L
            send(WindowSceneEvent.Tick(generation, time))
        }
        fun settle() {
            repeat(200) { if (requested != null) tick() }
            assertNull("animation must stop scheduling", requested)
        }
        fun enter() = input(WindowInputEvent.RequestMini(1, time))
        fun tap() = input(WindowInputEvent.TapConfirmed(1, time, RegionId.BODY_MASK, 1100f, 220f))
        fun press(region: RegionId = RegionId.HANDLE, x: Float = 200f, y: Float = 700f) =
            input(WindowInputEvent.Press(1, time, region, x, y))
    }

    @Test fun preparationIsInvisibleAndIdempotentUntilAcknowledged() {
        val d = Driver()
        assertEquals(0f, d.scene.snapshot().alpha, 0f)
        val command = d.send(WindowSceneEvent.TaskAvailable).single() as WindowSceneEffect.Prepare
        assertTrue(d.send(WindowSceneEvent.TaskAvailable).isEmpty())
        assertTrue(d.presentations.isEmpty())
        d.send(WindowSceneEvent.Prepared(command.commandId + 1, CommandStatus.DONE))
        assertTrue(d.presentations.isEmpty())
        d.send(WindowSceneEvent.Prepared(command.commandId, CommandStatus.DONE))
        d.settle()
        assertEquals(1f, d.scene.snapshot().alpha, 0f)
        assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun missingSourceGeometryAcknowledgesTransparentSeedWithoutWaitingForVisibility() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(null, 1100, 250, awaitSourceRelease = true))
        d.ready()
        assertEquals(0f, d.presentations.last().frame.alpha, 0f)
        assertNull(d.requested)
        assertEquals(listOf(CommandStatus.DONE),
            d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().map { it.status })
        d.send(WindowSceneEvent.SourceReleased)
        d.settle()
        assertEquals(1f, d.scene.snapshot().alpha, 0f)
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().size)
    }

    @Test fun sourceWaitOnlyAcknowledgesCommittedFrames() {
        val d = Driver().apply { autoPresent = false }
        d.send(WindowSceneEvent.OpenAsMini(null, 1100, 250, awaitSourceRelease = true))
        d.ready()
        val frame = d.presentations.last()
        d.send(WindowSceneEvent.FrameCommitted(frame.frameId, CommandStatus.SUPERSEDED))
        assertTrue(d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().isEmpty())
        d.send(WindowSceneEvent.PresentationInvalidated)
        d.send(WindowSceneEvent.FrameCommitted(frame.frameId, CommandStatus.DONE))
        assertTrue(d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().isEmpty())
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().size)
        assertNull(d.requested)
    }

    @Test fun failedPreparationEndsSourceHandoffBeforeFailureRollback() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(null, 1100, 250, awaitSourceRelease = true))
        val prepare = d.send(WindowSceneEvent.TaskAvailable).single() as WindowSceneEffect.Prepare
        val effects = d.send(WindowSceneEvent.Prepared(prepare.commandId, CommandStatus.FAILED))
        assertEquals(WindowSceneEffect.SourceHandoffResult(CommandStatus.FAILED), effects.first())
        assertTrue(effects.last() is WindowSceneEffect.Failed)
        d.send(WindowSceneEvent.Destroy)
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.SourceHandoffResult>().size)
    }

    @Test fun destroyCancelsSourceWaitAndRejectsLatePreparation() {
        val d = Driver()
        d.send(WindowSceneEvent.OpenAsMini(null, 1100, 250, awaitSourceRelease = true))
        val prepare = d.send(WindowSceneEvent.TaskAvailable).single() as WindowSceneEffect.Prepare
        val effects = d.send(WindowSceneEvent.Destroy)
        assertEquals(WindowSceneEffect.SourceHandoffResult(CommandStatus.SUPERSEDED), effects.single())
        assertTrue(d.send(WindowSceneEvent.Prepared(prepare.commandId, CommandStatus.DONE)).isEmpty())
        assertTrue(d.presentations.isEmpty())
    }

    @Test fun shrinkProgressDoesNotJumpToTerminalChrome() {
        val d = Driver(); d.ready(); d.settle(); d.enter()
        d.tick(); d.tick(); d.tick()
        val frame = d.scene.snapshot()
        assertTrue(frame.content.width < normal.width)
        assertTrue(frame.miniFraction > 0f && frame.miniFraction < 1f)
        assertTrue(frame.controlsAlpha > 0f && frame.controlsAlpha < 1f)
        assertTrue(frame.content.left - frame.outer.left > 0f)
        assertTrue(frame.content.left - frame.outer.left < style.bezel)
    }

    @Test fun expansionKeepsControlsAndDecorationOnEveryFrame() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); d.settle()
        d.presentations.clear(); d.tap(); d.settle()
        val growing = d.presentations.map { it.frame }.filter { it.miniFraction > 0f && it.miniFraction < 1f }
        assertTrue(growing.isNotEmpty())
        growing.forEach {
            assertTrue(it.outer.top < it.content.top)
            assertTrue(it.outer.bottom > it.content.bottom)
            assertTrue(it.controlsAlpha > 0f)
            assertFalse(it.acceptsContent)
        }
        assertEquals(normal, d.scene.snapshot().content)
        assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun miniEndpointPreservesNormalContentAspectAndStaysInsideSafeBands() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); d.settle()
        val f = d.scene.snapshot()
        assertEquals(normal.width / normal.height, f.content.width / f.content.height, 0.005f)
        assertTrue(f.outer.left >= style.margin - 1f)
        assertTrue(f.outer.right <= metrics.width - style.margin + 1f)
        assertTrue(f.outer.top >= metrics.topInset + style.margin - 1f)
        assertEquals(1f, f.miniFraction, 0f)
        assertEquals(0f, f.controlsAlpha, 0f)
    }

    @Test fun reverseRetargetStartsFromExactCurrentPose() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); repeat(9) { d.tick() }
        val before = d.scene.snapshot()
        d.tap()
        val after = d.scene.snapshot()
        assertEquals(before.content, after.content)
        assertEquals(before.miniFraction, after.miniFraction, 0f)
        assertEquals(before.outer, after.outer)
        d.settle()
        assertEquals(normal, d.scene.snapshot().content)
        assertEquals(ShellMode.NORMAL, d.scene.shellSnapshot().mode)
    }

    @Test fun repeatedReverseDoesNotLoseNormalPositionMemory() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); repeat(8) { d.tick() }
        d.tap(); repeat(5) { d.tick() }; d.enter(); d.settle(); d.tap(); d.settle()
        assertEquals(normal, d.scene.snapshot().content)
    }

    @Test fun pressingDuringMotionCancelsWithoutSnappingOrAcceptingStaleTicks() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); repeat(8) { d.tick() }
        val oldGeneration = d.requested!!
        val before = d.scene.snapshot().content
        d.press(RegionId.BODY_MASK)
        assertEquals(before, d.scene.snapshot().content)
        assertTrue(d.send(WindowSceneEvent.Tick(oldGeneration, d.time + 100L)).isEmpty())
        assertEquals(before, d.scene.snapshot().content)
    }

    @Test fun miniDraggingMovesRingAndContentByTheSameDelta() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); d.settle()
        val before = d.scene.snapshot()
        d.press(RegionId.BODY_MASK, 1100f, 200f)
        d.input(WindowInputEvent.Move(1, d.time, RegionId.BODY_MASK, 1065f, 260f))
        val after = d.scene.snapshot()
        assertEquals(-35f, after.content.left - before.content.left, 0.001f)
        assertEquals(-35f, after.outer.left - before.outer.left, 0.001f)
        assertEquals(60f, after.content.top - before.content.top, 0.001f)
        assertEquals(60f, after.outer.top - before.outer.top, 0.001f)
    }

    @Test fun draggingMiniDoesNotOverwriteReturnPosition() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); d.settle()
        d.press(RegionId.BODY_MASK, 1100f, 200f)
        d.input(WindowInputEvent.Move(1, d.time, RegionId.BODY_MASK, 900f, 500f))
        d.input(WindowInputEvent.Up(1, d.time, RegionId.BODY_MASK, 900f, 500f))
        d.settle(); d.tap(); d.settle()
        assertEquals(normal, d.scene.snapshot().content)
    }

    @Test fun terminalCommandWaitsForItsFrameAcknowledgement() {
        val d = Driver(0f); d.ready(); d.autoPresent = false
        d.enter()
        val terminal = d.presentations.last()
        assertTrue(d.scene.isAnimating())
        d.send(WindowSceneEvent.FrameCommitted(terminal.frameId - 1, CommandStatus.DONE))
        assertTrue(d.scene.isAnimating())
        d.send(WindowSceneEvent.FrameCommitted(terminal.frameId, CommandStatus.DONE))
        assertFalse(d.scene.isAnimating())
    }

    @Test fun disabledAnimationsStillAcknowledgeExactFinalGeometry() {
        val d = Driver(0f); d.ready(); d.enter()
        assertNull(d.requested)
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
        assertFalse(d.scene.isAnimating())
        d.tap()
        assertEquals(normal, d.scene.snapshot().content)
        assertEquals(0f, d.scene.snapshot().miniFraction, 0f)
    }

    @Test fun recentsEntryUsesActualSourceWithoutAnIntermediateNormalWindow() {
        val d = Driver()
        val source = SceneRect(790f, 150f, 340f, 700f)
        d.send(WindowSceneEvent.OpenAsMini(source, 1100, 200, awaitSourceRelease = true))
        d.ready()
        val first = d.presentations.single().frame
        assertEquals(source, first.content)
        assertEquals(source, first.outer)
        assertEquals(0f, first.controlsAlpha, 0f)
        assertNull("source must release before destination moves", d.requested)
        d.send(WindowSceneEvent.SourceReleased)
        assertNotNull(d.requested)
        d.settle()
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
        assertTrue(d.presentations.all { it.frame.controlsAlpha == 0f })
    }

    @Test fun stalePresentationCannotEndAReplacementAnimation() {
        val d = Driver(); d.ready(); d.settle(); d.autoPresent = false
        d.enter(); val old = d.presentations.last().frameId
        repeat(4) { d.tick() }; d.tap()
        d.send(WindowSceneEvent.FrameCommitted(old, CommandStatus.DONE))
        assertTrue(d.scene.isAnimating())
        assertEquals(ShellMode.NORMAL, d.scene.shellSnapshot().mode)
    }

    @Test fun closeWaitsForPresentationAndCancelledCloseCannotDestroyReplacement() {
        val d = Driver(0f); d.ready(); d.autoPresent = false
        d.press(RegionId.BAR_BOTTOM, 300f, 2000f)
        d.input(WindowInputEvent.Up(1, d.time, RegionId.BAR_BOTTOM, 300f, 1500f))
        val closingFrame = d.presentations.last().frameId
        assertTrue(d.effects.none { it is WindowSceneEffect.FinishClose })
        d.press()
        d.send(WindowSceneEvent.FrameCommitted(closingFrame, CommandStatus.DONE))
        assertTrue(d.effects.none { it is WindowSceneEffect.FinishClose })
    }

    @Test fun prepareFailureDoesNotRevealAnUnpreparedTask() {
        val d = Driver()
        val command = d.send(WindowSceneEvent.TaskAvailable).single() as WindowSceneEffect.Prepare
        val result = d.send(WindowSceneEvent.Prepared(command.commandId, CommandStatus.FAILED))
        assertTrue(result.single() is WindowSceneEffect.Failed)
        assertEquals(0f, d.scene.snapshot().alpha, 0f)
        assertTrue(d.presentations.isEmpty())
    }

    @Test fun contentPassThroughIsOnlyEnabledForSettledNormalMode() {
        val d = Driver(); d.ready(); d.settle()
        assertTrue(d.scene.snapshot().acceptsContent)
        d.enter(); assertFalse(d.scene.snapshot().acceptsContent)
        d.settle(); assertFalse(d.scene.snapshot().acceptsContent)
        d.tap(); assertFalse(d.scene.snapshot().acceptsContent)
        d.settle(); assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun hiddenSceneKeepsGeometryAndRestoresOnVisibilityEvent() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); d.settle()
        val before = d.scene.snapshot().content
        d.send(WindowSceneEvent.Visibility(false))
        assertEquals(0f, d.scene.snapshot().alpha, 0f)
        d.send(WindowSceneEvent.Visibility(true))
        assertEquals(before, d.scene.snapshot().content)
        assertEquals(1f, d.scene.snapshot().alpha, 0f)
    }

    @Test fun destroyedSceneIgnoresLateCallbacks() {
        val d = Driver(); d.ready(); d.enter()
        val gen = d.requested!!
        d.send(WindowSceneEvent.Destroy)
        assertTrue(d.send(WindowSceneEvent.Tick(gen, 9999L)).isEmpty())
        assertTrue(d.send(WindowSceneEvent.TaskAvailable).isEmpty())
    }

    @Test fun refreshRateOnlyDisplayEventDoesNotRetargetMotion() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); repeat(5) { d.tick() }
        val generation = d.requested
        val before = d.scene.snapshot()
        assertTrue(d.send(WindowSceneEvent.Metrics(metrics)).isEmpty())
        assertEquals(generation, d.requested)
        assertEquals(before, d.scene.snapshot())
    }

    @Test fun foreignWindowInputCannotCancelThisWindow() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); repeat(5) { d.tick() }
        d.input(WindowInputEvent.Press(2, d.time, RegionId.HANDLE, 200f, 300f))
        assertTrue(d.scene.isAnimating())
        d.settle()
        assertEquals(1f, d.scene.snapshot().miniFraction, 0f)
    }

    @Test fun invalidationTransfersTerminalAcknowledgementToItsReplacementFrame() {
        val d = Driver(0f); d.ready(); d.autoPresent = false; d.enter()
        val old = d.presentations.last().frameId
        d.send(WindowSceneEvent.PresentationInvalidated)
        val current = d.presentations.last().frameId
        d.send(WindowSceneEvent.FrameCommitted(old, CommandStatus.SUPERSEDED))
        assertTrue(d.scene.isAnimating())
        d.send(WindowSceneEvent.FrameCommitted(current, CommandStatus.DONE))
        assertFalse(d.scene.isAnimating())
        assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
    }

    @Test fun normalInputDoesNotOpenBeforeTheFinalFrameCommits() {
        val d = Driver(0f); d.ready(); d.enter(); d.autoPresent = false; d.tap()
        val finalFrame = d.presentations.last()
        assertFalse(finalFrame.frame.acceptsContent)
        d.send(WindowSceneEvent.FrameCommitted(finalFrame.frameId, CommandStatus.DONE))
        assertTrue(d.presentations.last().frame.acceptsContent)
    }

    @Test fun cancellingCloseRestoresTheOriginalNormalSizeOnRelease() {
        val d = Driver(); d.ready(); d.settle()
        d.press(RegionId.BAR_BOTTOM, 300f, 2000f)
        d.input(WindowInputEvent.Up(1, d.time, RegionId.BAR_BOTTOM, 300f, 1500f))
        repeat(6) { d.tick() }
        assertTrue(d.scene.snapshot().content.width < normal.width)
        d.press(RegionId.BODY_MASK, 200f, 300f)
        d.input(WindowInputEvent.Up(1, d.time, RegionId.BODY_MASK, 200f, 300f))
        d.settle()
        assertEquals(normal, d.scene.snapshot().content)
        assertTrue(d.effects.none { it is WindowSceneEffect.FinishClose })
    }

    @Test fun rotationFitsTheRememberedNormalWindowWithoutChangingAspect() {
        val d = Driver(); d.ready(); d.settle(); d.enter(); d.settle()
        d.send(WindowSceneEvent.Metrics(SceneMetrics(2780, 1264, 100, 70)))
        d.settle(); d.tap(); d.settle()
        val f = d.scene.snapshot()
        assertTrue(f.outer.top >= 100f)
        assertTrue(f.outer.bottom <= 1264f - 70f)
        assertEquals(normal.width / normal.height, f.content.width / f.content.height, 0.001f)
    }

    @Test fun appControllerColorIsPolicyAndSurvivesMiniRoundTrip() {
        val d = Driver(coloredController = true); d.ready(); d.settle()
        d.send(WindowSceneEvent.TaskColor(0xFF102030.toInt()))
        assertEquals(0xFF102030.toInt(), d.scene.snapshot().controllerColor)
        assertEquals(0xFFFFFFFF.toInt(), d.scene.snapshot().controlsColor)
        d.enter(); d.settle(); d.tap(); d.settle()
        assertEquals(0xFF102030.toInt(), d.scene.snapshot().controllerColor)
        val plain = Driver(); plain.ready(); plain.send(WindowSceneEvent.TaskColor(0xFF102030.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), plain.scene.snapshot().controllerColor)
    }

    @Test fun landscapeDoesNotRoundOffUncoveredBottomAppCorners() {
        val d = Driver(); d.ready(); d.settle()
        d.send(WindowSceneEvent.Metrics(SceneMetrics(2780, 1264, 100, 70))); d.settle()
        assertEquals(0f, d.scene.snapshot().outerBottomRadius, 0f)
        d.enter(); d.settle()
        assertEquals(style.miniCorner, d.scene.snapshot().outerBottomRadius, 0f)
    }

    @Test fun highVelocityReleaseCannotFlingASettledWindowOutOfScreen() {
        val d = Driver(); d.ready(); d.settle(); d.presentations.clear()
        d.input(WindowInputEvent.Fling(1, d.time, 12000f, -12000f)); d.settle()
        d.presentations.forEach {
            assertTrue(it.frame.outer.left >= style.margin - 1f)
            assertTrue(it.frame.outer.right <= metrics.width - style.margin + 1f)
            assertTrue(it.frame.outer.top >= metrics.topInset + style.margin - 1f)
        }
    }

    @Test fun fullscreenReleaseWaitsForTheHiddenTaskAndFullscreenBridgeFrames() {
        val d = Driver(0f); d.ready(); d.autoPresent = false
        d.press(RegionId.BAR_BOTTOM, 300f, 1500f)
        d.input(WindowInputEvent.Move(1, d.time, RegionId.BAR_BOTTOM, 300f, 2000f))
        d.input(WindowInputEvent.Up(1, d.time, RegionId.BAR_BOTTOM, 300f, 2000f))
        assertTrue(d.effects.filterIsInstance<WindowSceneEffect.HandoffFullscreen>().isEmpty())
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertTrue(d.effects.filterIsInstance<WindowSceneEffect.HandoffFullscreen>().isEmpty())
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.HandoffFullscreen>().size)
    }
}
