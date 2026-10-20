package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import org.junit.Assert.*
import org.junit.Test

class FullscreenTransitionTest {
    private val metrics = SceneMetrics(1264, 2780, 130, 80)
    private val normal = SceneRect(180f, 700f, 898f, 1139f)
    private val style = SceneStyle(3.2f, 20f, 12f)

    private inner class Driver(scale: Float = 1f) {
        val scene = WindowSceneController(1, style.copy(animatorScale = scale), metrics, normal)
        val effects = mutableListOf<WindowSceneEffect>()
        val frames = mutableListOf<WindowSceneEffect.Present>()
        var requested: Long? = null
        var time = 0L
        var autoCommit = true

        fun send(event: WindowSceneEvent) {
            val result = scene.onEvent(event)
            effects += result
            result.forEach { effect -> when (effect) {
                is WindowSceneEffect.Present -> {
                    frames += effect
                    if (autoCommit) commit(effect)
                }
                is WindowSceneEffect.RequestFrame -> requested = effect.generation
                else -> Unit
            } }
        }
        fun commit(frame: WindowSceneEffect.Present = frames.last(), status: CommandStatus = CommandStatus.DONE) =
            send(WindowSceneEvent.FrameCommitted(frame.frameId, status))
        fun input(event: WindowInputEvent) = send(WindowSceneEvent.Input(event))
        fun ready() {
            send(WindowSceneEvent.TaskAvailable)
            val id = effects.filterIsInstance<WindowSceneEffect.Prepare>().last().commandId
            send(WindowSceneEvent.Prepared(id, CommandStatus.DONE))
            settle()
            effects.clear(); frames.clear()
        }
        fun press() = input(WindowInputEvent.Press(1, time, RegionId.BAR_BOTTOM, 500f, 1900f))
        fun move(dy: Float) = input(WindowInputEvent.Move(1, time, RegionId.BAR_BOTTOM, 500f, 1900f + dy))
        fun up(dy: Float) = input(WindowInputEvent.Up(1, time, RegionId.BAR_BOTTOM, 500f, 1900f + dy))
        fun tick(dt: Long = 16L) {
            val generation = requested ?: return
            requested = null
            time += dt
            send(WindowSceneEvent.Tick(generation, time))
        }
        fun settle() {
            repeat(250) { tick() }
            assertNull("motion must stop requesting frames", requested)
        }
        fun handoffs() = effects.filterIsInstance<WindowSceneEffect.HandoffFullscreen>()
        fun exit() { press(); move(500f); up(500f); settle() }
        fun result(id: Long, status: CommandStatus, released: Boolean) =
            send(WindowSceneEvent.FullscreenHandoff(id, FullscreenHandoffResult(status, released)))
    }

    @Test fun pullHasDiminishingGrowthAndKeepsItsTopAndAspect() {
        val scales = listOf(0f, 0.5f, 1f, 2f, 4f).map(FullscreenAnimPolicy::pullScale)
        assertEquals(1f, scales.first(), 0f)
        assertTrue(scales.zipWithNext().all { (a, b) -> b > a })
        assertTrue(scales[2] - scales[1] < scales[1] - scales[0])
        assertTrue(FullscreenAnimPolicy.pullScale(10000f) <= 1.1f)
        val preview = FullscreenAnimPolicy.previewContent(normal, 2f, metrics, style.margin)
        assertEquals(normal.top, preview.top, 0f)
        assertEquals(normal.width / normal.height, preview.width / preview.height, 0.0001f)
        assertTrue(preview.right <= metrics.width - style.margin + 0.001f)
    }

    @Test fun pointerUpdatesRetargetTheSpringWithoutResettingItsClockOrJumping() {
        val d = Driver(); d.ready(); d.press(); d.move(500f)
        assertEquals(normal, d.scene.snapshot().content)
        repeat(30) { d.tick(); d.move(500f + it) }
        val frame = d.scene.snapshot()
        assertEquals(normal.top, frame.content.top, 1f)
        assertTrue(frame.content.width > normal.width * 1.04f)
        assertTrue(frame.content.width < normal.width * 1.11f)
        assertTrue(frame.fullscreen!!.maskAlpha > 0.9f)
        assertFalse(frame.acceptsContent)
        assertTrue(d.handoffs().isEmpty())
    }

    @Test fun repeatedPullAndRetractDoesNotReleaseOrChangeRememberedNormalBounds() {
        val d = Driver(); d.ready(); d.press()
        repeat(4) {
            d.move(600f); repeat(20) { d.tick() }
            d.move(80f); repeat(20) { d.tick() }
        }
        d.up(80f); d.settle()
        assertTrue(d.handoffs().isEmpty())
        assertEquals(normal, d.scene.snapshot().content)
        assertNull(d.scene.snapshot().fullscreen)
        assertTrue(d.scene.snapshot().acceptsContent)
        d.input(WindowInputEvent.RequestMini(1, d.time)); d.settle()
        d.input(WindowInputEvent.TapConfirmed(1, d.time, RegionId.BODY_MASK, 1100f, 200f)); d.settle()
        assertEquals(normal, d.scene.snapshot().content)
    }

    @Test fun cancelAlwaysReturnsEvenAfterArmingAndReturnCanBeGrabbedAgain() {
        val d = Driver(); d.ready(); d.press(); d.move(600f); repeat(15) { d.tick() }
        d.input(WindowInputEvent.Cancel(1, d.time, RegionId.BAR_BOTTOM, 500f, 2500f))
        repeat(4) { d.tick() }
        val before = d.scene.snapshot().outer
        d.press()
        assertEquals(before, d.scene.snapshot().outer)
        d.move(80f); d.up(80f); d.settle()
        assertTrue(d.handoffs().isEmpty())
        assertEquals(normal, d.scene.snapshot().content)
        assertTrue(d.scene.snapshot().acceptsContent)
    }

    @Test fun releaseRequiresBothHiddenContentAndFullDisplayBridgeCommitFences() {
        val d = Driver(0f); d.ready(); d.autoCommit = false; d.press(); d.move(500f)
        val preview = d.frames.last()
        d.up(500f)
        val hidden = d.frames.last()
        assertEquals(FullscreenStage.HIDING, hidden.frame.fullscreen!!.stage)
        assertEquals(1f, hidden.frame.fullscreen!!.maskAlpha, 0f)
        assertEquals(0f, hidden.frame.taskAlpha, 0f)
        assertTrue(hidden.frame.presentsLiveContent)
        assertTrue(d.handoffs().isEmpty())
        d.commit(preview)
        assertTrue(d.handoffs().isEmpty())
        d.commit(hidden)
        val full = d.frames.last()
        assertFalse(full.frame.presentsLiveContent)
        assertEquals(SceneRect(0f, 0f, 1264f, 2780f), full.frame.outer)
        assertTrue(d.handoffs().isEmpty())
        d.commit(full)
        assertEquals(1, d.handoffs().size)
        d.commit(full)
        assertEquals(1, d.handoffs().size)
    }

    @Test fun hiddenShellCannotAdvanceTheNativeOwnershipHandoff() {
        val d = Driver(0f); d.ready(); d.autoCommit = false; d.exit()
        d.send(WindowSceneEvent.Visibility(false))
        d.commit()
        assertTrue(d.handoffs().isEmpty())
        assertEquals(FullscreenStage.HIDING, d.scene.snapshot().fullscreen!!.stage)
        d.send(WindowSceneEvent.Visibility(true)); d.commit()
        d.commit()
        assertEquals(1, d.handoffs().size)
    }

    @Test fun expansionContinuesFromPreviewAndOnlyMovesTheBridge() {
        val d = Driver(); d.ready(); d.press(); d.move(600f); d.settle()
        val before = d.scene.snapshot().outer
        d.up(600f)
        assertEquals(before, d.scene.snapshot().outer)
        d.settle()
        val expanded = d.frames.filter { it.frame.fullscreen?.stage == FullscreenStage.EXPANDING }
        assertTrue(expanded.size > 5)
        assertTrue(expanded.all { !it.frame.presentsLiveContent && it.frame.fullscreen!!.maskAlpha == 1f })
        assertTrue(expanded.any { it.frame.outer.width > before.width && it.frame.outer.width < metrics.width })
        assertEquals(1, d.handoffs().size)
        assertEquals(FullscreenStage.WAITING, d.scene.snapshot().fullscreen!!.stage)
        assertFalse(d.scene.snapshot().acceptsContent)
        assertEquals(normal, d.scene.normalViewport())
    }

    @Test fun newFullscreenDrawAcknowledgementPrecedesRevealAndFinalCleanupHasItsOwnFence() {
        val d = Driver(0f); d.ready(); d.exit()
        val id = d.handoffs().single().commandId
        d.result(id + 1, CommandStatus.DONE, true)
        assertEquals(1f, d.scene.snapshot().fullscreen!!.maskAlpha, 0f)
        assertFalse(d.effects.contains(WindowSceneEffect.FinishFullscreen))
        d.autoCommit = false
        val offset = d.frames.size
        d.result(id, CommandStatus.DONE, true)
        assertTrue(d.frames.drop(offset).all { !it.frame.presentsLiveContent })
        assertEquals(0f, d.frames.last().frame.fullscreen!!.maskAlpha, 0f)
        assertFalse(d.effects.contains(WindowSceneEffect.FinishFullscreen))
        d.commit()
        assertEquals(1, d.effects.count { it == WindowSceneEffect.FinishFullscreen })
        d.result(id, CommandStatus.DONE, true)
        assertEquals(1, d.effects.count { it == WindowSceneEffect.FinishFullscreen })
    }

    @Test fun preReleaseFailureRestoresWhilePostReleaseFailureNeverWritesAnOldPose() {
        val before = Driver(0f); before.ready(); before.exit()
        val oldId = before.handoffs().single().commandId
        before.result(oldId, CommandStatus.FAILED, false)
        assertEquals(normal, before.scene.snapshot().content)
        assertNull(before.scene.snapshot().fullscreen)
        assertTrue(before.scene.snapshot().acceptsContent)
        before.exit()
        val nextId = before.handoffs().last().commandId
        assertNotEquals(oldId, nextId)
        before.result(oldId, CommandStatus.DONE, true)
        assertEquals(FullscreenStage.WAITING, before.scene.snapshot().fullscreen!!.stage)
        val offset = before.frames.size
        before.result(nextId, CommandStatus.FAILED, true)
        assertTrue(before.frames.drop(offset).none { it.frame.presentsLiveContent })
        assertTrue(before.effects.contains(WindowSceneEffect.FinishFullscreen))
    }

    @Test fun taskReadyWithoutOwnershipReleaseIsNotAValidFullscreenSuccess() {
        val d = Driver(0f); d.ready(); d.exit()
        d.result(d.handoffs().single().commandId, CommandStatus.DONE, false)
        assertNull(d.scene.snapshot().fullscreen)
        assertEquals(normal, d.scene.snapshot().content)
    }

    @Test fun unknownNativeOwnershipOnlyAllowsCleanupAndNeverRestoresAnOldPose() {
        val d = Driver(0f); d.ready(); d.exit()
        val id = d.handoffs().single().commandId
        val offset = d.frames.size
        d.send(WindowSceneEvent.FullscreenHandoff(id, FullscreenHandoffResult(CommandStatus.FAILED, false, ownershipKnown = false)))
        assertTrue(d.frames.drop(offset).none { it.frame.presentsLiveContent })
        assertTrue(d.effects.any { it is WindowSceneEffect.Failed })
    }

    @Test fun destroyingTheBridgeRemovesInputAndIgnoresLateNativeReadiness() {
        val d = Driver(0f); d.ready(); d.exit()
        val id = d.handoffs().single().commandId
        d.send(WindowSceneEvent.Destroy)
        assertEquals(0f, d.scene.snapshot().alpha, 0f)
        assertFalse(d.scene.snapshot().acceptsContent)
        assertNull(d.scene.snapshot().fullscreen)
        assertFalse(d.scene.isAnimating())
        assertFalse(d.scene.prefersHighFrameRate())
        val offset = d.frames.size
        d.result(id, CommandStatus.DONE, true)
        assertEquals(offset, d.frames.size)
    }

    @Test fun previewReservesLandscapeControlsAndPortraitBottomBar() {
        val landscape = SceneMetrics(2780, 1264, 100, 70)
        val base = SceneRect(1500f, 220f, 1100f, 800f)
        val p = FullscreenAnimPolicy.previewContent(base, 100f, landscape, style.margin, rightDecoration = style.sideBar)
        assertTrue(p.right + style.sideBar + style.margin <= landscape.width + 0.001f)
        val tall = SceneRect(180f, 700f, 898f, 1850f)
        val q = FullscreenAnimPolicy.previewContent(tall, 100f, metrics, style.margin, bottomDecoration = style.bottomBar)
        assertTrue(q.bottom + style.bottomBar + style.margin <= metrics.height - metrics.bottomInset + 0.001f)
    }

    @Test fun bridgeTouchesCannotStartABodyGestureAndCommittedExitIgnoresAllGestures() {
        val d = Driver(0f); d.ready(); d.press(); d.move(500f)
        val preview = d.scene.snapshot()
        assertNull(preview.hitTest(preview.content.left + 20f, preview.content.top + 20f, style))
        assertEquals(RegionId.BAR_BOTTOM,
            preview.hitTest(preview.content.left + 20f, preview.content.bottom + 10f, style))
        d.up(500f)
        assertEquals(FullscreenStage.WAITING, d.scene.snapshot().fullscreen!!.stage)
        assertNull(d.scene.snapshot().hitTest(500f, 2700f, style))
    }

    @Test fun supersededBridgeFenceIsRetriedAndStaleInputCannotRestartTaskWrites() {
        val d = Driver(0f); d.ready(); d.autoCommit = false; d.exit()
        val first = d.frames.last()
        d.commit(first, CommandStatus.SUPERSEDED)
        assertTrue(d.frames.last().frameId > first.frameId)
        d.commit(); d.commit()
        assertEquals(1, d.handoffs().size)
        val frame = d.scene.snapshot()
        d.press(); d.move(80f); d.up(80f)
        assertEquals(frame, d.scene.snapshot())
    }

    @Test fun displayResizeDuringHandoffKeepsTheBridgeFullScreenAndDoesNotReleaseAgain() {
        val d = Driver(0f); d.ready(); d.exit()
        d.send(WindowSceneEvent.Metrics(SceneMetrics(2780, 1264, 100, 70)))
        assertEquals(SceneRect(0f, 0f, 2780f, 1264f), d.scene.snapshot().outer)
        assertFalse(d.scene.snapshot().presentsLiveContent)
        assertEquals(1, d.handoffs().size)
    }
}
