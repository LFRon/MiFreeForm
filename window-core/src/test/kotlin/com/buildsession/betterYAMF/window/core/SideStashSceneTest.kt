package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*
import org.junit.Assert.*
import org.junit.Test

class SideStashSceneTest {
    private val portrait = SceneMetrics(1264, 2780, 130, 80)
    private val normal = SceneRect(180f, 700f, 898f, 1139f)

    private inner class Driver(scale: Float = 1f) {
        val scene = WindowSceneController(1, SceneStyle(3.2f, 20f, 12f, animatorScale = scale), portrait, normal)
        var metrics = portrait
        var time = 1000L
        var requested: Long? = null
        var autoCommit = true
        val frames = mutableListOf<WindowSceneEffect.Present>()
        val effects = mutableListOf<WindowSceneEffect>()
        val frame get() = scene.snapshot()
        init {
            val prepare = send(WindowSceneEvent.TaskAvailable).filterIsInstance<WindowSceneEffect.Prepare>().single()
            send(WindowSceneEvent.Prepared(prepare.commandId, CommandStatus.DONE))
            settle()
        }
        fun send(event: WindowSceneEvent): List<WindowSceneEffect> {
            val out = scene.onEvent(event)
            effects += out
            out.forEach {
                when (it) {
                    is WindowSceneEffect.RequestFrame -> requested = it.generation
                    is WindowSceneEffect.Present -> {
                        frames += it
                        if (autoCommit) send(WindowSceneEvent.FrameCommitted(it.frameId, CommandStatus.DONE))
                    }
                    else -> Unit
                }
            }
            return out
        }
        fun input(event: WindowInputEvent) = send(WindowSceneEvent.Input(event))
        fun tick() {
            val generation = requested ?: return
            requested = null
            time += 16L
            send(WindowSceneEvent.Tick(generation, time))
        }
        fun settle() {
            repeat(300) { if (requested != null) tick() }
            assertNull("A stationary icon must not keep requesting frames", requested)
        }
        fun stash(side: WindowSide) {
            val r = frame.outer
            val x = r.left + r.width / 2f
            val y = r.top + r.height / 2f
            val target = if (side == WindowSide.LEFT) 1f else metrics.width - 1f
            input(WindowInputEvent.Press(1, time, RegionId.HANDLE, x, y))
            input(WindowInputEvent.Move(1, time + 1000L, RegionId.HANDLE, target, metrics.height / 2f))
            input(WindowInputEvent.Up(1, time + 2000L, RegionId.HANDLE, target, metrics.height / 2f))
            time += 2000L // Deliberately slow, with no Fling event.
        }
        fun pressIcon(): Pair<Float, Float> {
            val r = requireNotNull(frame.input.bounds)
            val x = r.left + r.width / 2f; val y = r.top + r.height / 2f
            assertEquals(RegionId.STASH_HANDLE, frame.input.regionAt(x, y))
            input(WindowInputEvent.Press(1, time, RegionId.STASH_HANDLE, x, y))
            return x to y
        }
        fun tapIcon() {
            val (x, y) = pressIcon()
            input(WindowInputEvent.Up(1, time + 10, RegionId.STASH_HANDLE, x, y))
        }
        fun rotate(metrics: SceneMetrics) {
            this.metrics = metrics
            send(WindowSceneEvent.Metrics(metrics))
            settle()
        }
        fun commitLatest(status: CommandStatus = CommandStatus.DONE) =
            send(WindowSceneEvent.FrameCommitted(frames.last().frameId, status))
    }

    @Test fun eitherSideCollapsesToAPartlyHiddenIconAndRestoresTheSamePreparedViewport() {
        for (side in WindowSide.entries) {
            val d = Driver()
            val logical = d.scene.normalLayout().logicalViewport
            d.stash(side); d.settle()
            val f = d.frame
            val icon = requireNotNull(f.sideStash)
            assertEquals(side, icon.side)
            assertEquals(1f, icon.alpha, 0f)
            assertEquals(0f, f.taskAlpha, 0f)
            assertEquals(0f, f.shellAlpha, 0f)
            assertFalse(f.taskVisible)
            assertFalse(f.taskFocusable)
            assertFalse(f.acceptsContent)
            assertEquals(ShellMode.STASHED, d.scene.shellSnapshot().mode)
            val input = requireNotNull(f.input.bounds)
            assertTrue(input.left >= 0f && input.right <= portrait.width)
            assertTrue(input.width < icon.bounds.width && input.width > 20f)
            assertNull(f.input.passThrough)
            assertNull(f.input.regionAt(portrait.width / 2f, portrait.height / 2f))
            if (side == WindowSide.LEFT) assertTrue(icon.bounds.left < 0f)
            else assertTrue(icon.bounds.right > portrait.width)
            val before = f.content
            d.tapIcon()
            assertEquals("Restoration starts at the current pose", before, d.frame.content)
            assertFalse(d.frame.taskFocusable)
            d.settle()
            assertNull(d.frame.sideStash)
            assertTrue(d.frame.taskVisible && d.frame.taskFocusable && d.frame.acceptsContent)
            assertEquals(normal.width, d.frame.content.width, 0f)
            assertEquals(normal.height, d.frame.content.height, 0f)
            assertEquals(logical, d.scene.normalLayout().logicalViewport)
            assertEquals(1, d.effects.count { it is WindowSceneEffect.Prepare })
            assertTrue(d.effects.none { it is WindowSceneEffect.HandoffFullscreen || it == WindowSceneEffect.FinishClose })
        }
    }

    @Test fun cornerMiniCanBeStashedAndTheIconRestoresAnOrdinaryWindow() {
        val d = Driver()
        d.input(WindowInputEvent.RequestMini(1, d.time)); d.settle()
        assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
        assertNull(d.frame.sideStash)
        d.stash(WindowSide.RIGHT); d.settle(); d.tapIcon(); d.settle()
        assertEquals(ShellMode.NORMAL, d.scene.shellSnapshot().mode)
        assertEquals(normal.width, d.frame.content.width, 0f)
        assertTrue(d.frame.acceptsContent)
    }

    @Test fun pressingAndCancellingDuringCollapseDoesNotFreezeTheAnimation() {
        val d = Driver(); d.stash(WindowSide.RIGHT)
        repeat(4) { d.tick() }
        val before = d.frame.content
        val (x, y) = d.pressIcon()
        repeat(4) { d.tick() }
        assertNotEquals(before, d.frame.content)
        d.input(WindowInputEvent.Cancel(1, d.time, RegionId.STASH_HANDLE, x, y))
        d.settle()
        assertFalse(d.frame.taskVisible)
        assertEquals(ShellMode.STASHED, d.scene.shellSnapshot().mode)
    }

    @Test fun anEarlyIconTapReversesFromTheCurrentPoseAndRepeatedTapsDoNotRestartIt() {
        val d = Driver(); d.stash(WindowSide.LEFT)
        repeat(7) { d.tick() }
        val before = d.frame.content
        d.tapIcon()
        assertEquals(before, d.frame.content)
        repeat(3) { d.tick() }
        val moving = d.frame.content
        d.tapIcon()
        assertEquals(moving, d.frame.content)
        d.settle()
        assertTrue(d.frame.acceptsContent)
        assertNull(d.frame.sideStash)
    }

    @Test fun restorationKeepsTheInputShieldAndFocusSuppressionUntilItsOwnFrameCommits() {
        val d = Driver(); d.stash(WindowSide.RIGHT); d.settle()
        d.autoCommit = false
        d.tapIcon(); d.settle()
        assertFalse(d.frame.acceptsContent)
        assertFalse(d.frame.taskFocusable)
        assertNotNull(d.frame.sideStash)
        val old = d.frames.last().frameId
        d.send(WindowSceneEvent.Visibility(false))
        d.send(WindowSceneEvent.FrameCommitted(old, CommandStatus.DONE))
        d.commitLatest()
        assertNotNull(d.frame.sideStash)
        assertFalse(d.frame.taskFocusable)
        d.send(WindowSceneEvent.Visibility(true))
        d.commitLatest()
        assertNull(d.frame.sideStash)
        assertTrue(d.frame.acceptsContent && d.frame.taskFocusable)
    }

    @Test fun coalescedAndLateCollapseCommitsCannotCompleteANewerRestoration() {
        val d = Driver(); d.autoCommit = false
        d.stash(WindowSide.LEFT); d.settle()
        val old = d.frames.last().frameId
        d.commitLatest(CommandStatus.SUPERSEDED)
        val retry = d.frames.last().frameId
        assertTrue(retry > old)
        d.tapIcon()
        d.send(WindowSceneEvent.FrameCommitted(retry, CommandStatus.DONE))
        d.send(WindowSceneEvent.FrameCommitted(old, CommandStatus.DONE))
        assertFalse(d.frame.taskFocusable)
        d.settle()
        assertNotNull(d.frame.sideStash)
        d.commitLatest()
        assertTrue(d.frame.acceptsContent && d.frame.taskFocusable)
    }

    @Test fun disabledAnimationsStillWaitForTheRestorationCommitBeforeReopeningInput() {
        val d = Driver(0f); d.autoCommit = false
        d.stash(WindowSide.RIGHT)
        assertFalse(d.frame.taskVisible)
        d.commitLatest()
        d.tapIcon()
        assertFalse(d.frame.acceptsContent)
        assertFalse(d.frame.taskFocusable)
        d.commitLatest()
        assertTrue(d.frame.acceptsContent && d.frame.taskVisible && d.frame.taskFocusable)
        assertNull(d.requested)
    }

    @Test fun rotationsKeepTheIconOnItsChosenSideAndPreserveThePreferredWindowSize() {
        for (side in WindowSide.entries) {
            val d = Driver(); d.stash(side); d.settle()
            repeat(2) {
                d.rotate(SceneMetrics(2780, 1264, 100, 70, rotation = 1))
                val b = requireNotNull(d.frame.input.bounds)
                assertTrue(b.top >= 100f && b.bottom <= 1264 - 70f)
                assertEquals(side, d.frame.sideStash!!.side)
                assertFalse(d.frame.taskVisible)
                d.rotate(portrait)
            }
            d.tapIcon(); d.settle()
            assertEquals(normal.width, d.frame.content.width, 0f)
            assertEquals(normal.height, d.frame.content.height, 0f)
        }
    }

    @Test fun hidingAndInvalidatingThePresentationCannotUnstashTheTask() {
        val d = Driver(); d.stash(WindowSide.LEFT); d.settle()
        d.send(WindowSceneEvent.Visibility(false))
        assertNull(d.frame.input.bounds)
        assertEquals(0f, d.frame.alpha, 0f)
        d.send(WindowSceneEvent.PresentationInvalidated)
        d.send(WindowSceneEvent.Visibility(true))
        assertNotNull(d.frame.input.bounds)
        assertFalse(d.frame.taskVisible || d.frame.taskFocusable)
        assertEquals(ShellMode.STASHED, d.scene.shellSnapshot().mode)
        assertNull(d.requested)
    }

    @Test fun resetPositionRestoresACenteredWindowInsteadOfLeavingAnIconInTheMiddle() {
        val d = Driver(); d.stash(WindowSide.RIGHT); d.settle()
        d.send(WindowSceneEvent.ResetPosition); d.settle()
        assertNull(d.frame.sideStash)
        assertTrue(d.frame.acceptsContent)
        assertEquals((portrait.width - normal.width) / 2f, d.frame.content.left, 0.51f)
        assertEquals(normal.height, d.frame.content.height, 0f)
    }

    @Test fun repeatedSideChangesDoNotShrinkTheRememberedWindowOrPrepareAnotherViewport() {
        val d = Driver()
        repeat(8) {
            d.stash(if (it % 2 == 0) WindowSide.LEFT else WindowSide.RIGHT); d.settle()
            d.tapIcon(); d.settle()
            assertEquals(normal.width, d.frame.content.width, 0f)
            assertEquals(normal.height, d.frame.content.height, 0f)
            assertTrue(d.frame.acceptsContent)
        }
        assertEquals(1, d.effects.count { it is WindowSceneEffect.Prepare })
    }
}
