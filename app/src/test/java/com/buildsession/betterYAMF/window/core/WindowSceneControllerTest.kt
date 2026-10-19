package com.buildsession.betterYAMF.window.core

import org.junit.Assert.*
import org.junit.Test

class WindowSceneControllerTest {
    private val metrics = SceneMetrics(1264, 2780, 130, 80)
    private val normal = SceneRect(180f, 700f, 898f, 1139f)
    private val style = SceneStyle(3.2f, 20f, 12f)

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

    private inner class Driver(animatorScale: Float = 1f, coloredController: Boolean = false) {
        val scene = WindowSceneController(1, style.copy(animatorScale = animatorScale, coloredController = coloredController), metrics, normal)
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

    @Test fun fullscreenReleaseWaitsUntilTheLastPreviewFrameIsCommitted() {
        val d = Driver(0f); d.ready(); d.autoPresent = false
        d.press(RegionId.BAR_BOTTOM, 300f, 1500f)
        d.input(WindowInputEvent.Move(1, d.time, RegionId.BAR_BOTTOM, 300f, 2000f))
        d.input(WindowInputEvent.Up(1, d.time, RegionId.BAR_BOTTOM, 300f, 2000f))
        assertTrue(d.effects.filterIsInstance<WindowSceneEffect.SystemCommand>().none { it.command is WindowCommand.RequestFullscreen })
        d.send(WindowSceneEvent.FrameCommitted(d.presentations.last().frameId, CommandStatus.DONE))
        assertEquals(1, d.effects.filterIsInstance<WindowSceneEffect.SystemCommand>().count { it.command is WindowCommand.RequestFullscreen })
    }
}
