package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowShellControllerTest {

    private val registration = WindowRegistration(windowId = 1, density = 2f)

    private fun controller(top: Boolean = true): WindowShellController {
        val c = WindowShellController(registration, CoreConfig(), FakeTopPort(top))
        c.onScreenMetricsChanged(1000, 2000)
        return c
    }

    private fun WindowShellController.feed(vararg events: WindowInputEvent): List<WindowCommand> =
        events.flatMap { onInput(it) }

    @Test
    fun dragFollowsFingerAndClampsOnRelease() {
        val c = controller()
        assertEquals(emptyList<WindowCommand>(), c.feed(press(RegionId.BODY_MASK, 100f, 100f)))
        assertEquals(
            listOf<WindowCommand>(
                WindowCommand.MoveBy(1, 400f, 900f),
                WindowCommand.DropZoneShow(1),
                WindowCommand.DropHover(1, null, false)
            ),
            c.feed(move(RegionId.BODY_MASK, 500f, 1000f))
        )
        assertEquals(
            listOf<WindowCommand>(
                WindowCommand.ClampToScreen(1, animated = true),
                WindowCommand.DropZoneHide(1)
            ),
            c.feed(up(RegionId.BODY_MASK, 500f, 1000f))
        )
        assertFalse(c.snapshot().dragging)
    }

    @Test
    fun moveReportsDeltaFromPressOrigin() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 100f, 100f))
        c.feed(move(RegionId.BODY_MASK, 300f, 400f))
        val commands = c.feed(move(RegionId.BODY_MASK, 700f, 500f))
        assertEquals(WindowCommand.MoveBy(1, 600f, 400f), commands.first())
    }

    @Test
    fun raiseOnUpWhenNotTop() {
        val c = controller(top = false)
        c.feed(press(RegionId.CONTENT, 100f, 100f))
        assertEquals(
            listOf(WindowCommand.RaiseWindow(1)),
            c.feed(up(RegionId.CONTENT))
        )
        assertEquals(emptyList<WindowCommand>(), c.feed(up(RegionId.CONTENT)))
    }

    @Test
    fun raiseHappensBeforeReleaseDecision() {
        val c = controller(top = false)
        c.feed(press(RegionId.BODY_MASK, 100f, 100f))
        c.feed(move(RegionId.BODY_MASK, 500f, 1000f))
        assertEquals(
            listOf<WindowCommand>(
                WindowCommand.RaiseWindow(1),
                WindowCommand.ClampToScreen(1, animated = true),
                WindowCommand.DropZoneHide(1)
            ),
            c.feed(up(RegionId.BODY_MASK, 500f, 1000f))
        )
    }

    @Test
    fun cornerDropEntersMini() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(
            listOf<WindowCommand>(
                WindowCommand.MoveBy(1, 0f, 0f),
                WindowCommand.DropZoneShow(1),
                WindowCommand.DropHover(1, DropKind.CORNER_TL, true)
            ),
            c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        )
        val released = c.feed(up(RegionId.BODY_MASK, 10f, 10f))
        val enter = released.filterIsInstance<WindowCommand.EnterMini>().single()
        assertEquals(DropKind.CORNER_TL, enter.corner)
        assertTrue(enter.cmdId > 0L)
        assertEquals(WindowCommand.DropZoneHide(1), released.last())
        assertEquals(ShellMode.MINI, c.snapshot().mode)
    }

    @Test
    fun sideDropEntersSideMiniAndTapConfirmedExpands() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 1000f))
        c.feed(move(RegionId.BODY_MASK, 10f, 1000f))
        val released = c.feed(up(RegionId.BODY_MASK, 10f, 1000f))
        val enter = released.filterIsInstance<WindowCommand.EnterMini>().single()
        assertEquals(DropKind.SIDE_LEFT, enter.corner)
        assertTrue(enter.cmdId > 0L)
        assertEquals(WindowCommand.DropZoneHide(1), released.last())
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        val exit = c.feed(tapConfirmed()).single() as WindowCommand.ExitMini
        assertTrue(exit.cmdId > 0L)
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
    }

    @Test
    fun miniTapConfirmedExitsMini() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        c.feed(up(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        val exit = c.feed(tapConfirmed()).single() as WindowCommand.ExitMini
        assertTrue(exit.cmdId > 0L)
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
    }

    @Test
    fun tapConfirmedInNormalDoesNothing() {
        val c = controller()
        assertEquals(emptyList<WindowCommand>(), c.feed(tapConfirmed()))
    }

    @Test
    fun miniAllowsSideDropButNotCornerDrop() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        c.feed(up(RegionId.BODY_MASK, 10f, 10f))

        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        val cornerAttempt = c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(
            WindowCommand.DropHover(1, DropKind.SIDE_LEFT, false),
            cornerAttempt.last()
        )
        c.feed(up(RegionId.BODY_MASK, 10f, 10f))

        c.feed(press(RegionId.BODY_MASK, 10f, 1000f))
        c.feed(move(RegionId.BODY_MASK, 10f, 1000f))
        val released = c.feed(up(RegionId.BODY_MASK, 10f, 1000f))
        assertEquals(DropKind.SIDE_LEFT, released.filterIsInstance<WindowCommand.EnterMini>().single().corner)
        assertEquals(ShellMode.MINI, c.snapshot().mode)
    }

    @Test
    fun invalidScreenMetricsSkipsHoverComputation() {
        val c = WindowShellController(registration, CoreConfig(), FakeTopPort())
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(
            listOf<WindowCommand>(
                WindowCommand.MoveBy(1, 0f, 0f),
                WindowCommand.DropZoneShow(1)
            ),
            c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        )
    }

    @Test
    fun barSwipeUpBeyondThresholdCloses() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        val preview = c.feed(move(RegionId.BAR_BOTTOM, 500f, 1738f)).single() as WindowCommand.VerticalDragPreview
        assertEquals(1f, preview.progress, 0.0001f)
        assertFalse(preview.downward)
        assertFalse(preview.showFullscreenHint)
        assertEquals(
            listOf(WindowCommand.RequestClose(1, CloseOrigin.DRAG_UP_BAR)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1738f))
        )
    }

    @Test
    fun barSwipeUpBelowThresholdResets() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        val preview = c.feed(move(RegionId.BAR_BOTTOM, 500f, 1742f)).single() as WindowCommand.VerticalDragPreview
        assertEquals(0.9875f, preview.progress, 0.0001f)
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1742f))
        )
    }

    @Test
    fun barSwipeDownBeyondThresholdRequestsFullscreen() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        val preview = c.feed(move(RegionId.BAR_BOTTOM, 500f, 2062f)).single() as WindowCommand.VerticalDragPreview
        assertEquals(1.0125f, preview.progress, 0.0001f)
        assertTrue(preview.downward)
        assertTrue(preview.showFullscreenHint)
        assertEquals(
            listOf(WindowCommand.RequestFullscreen(1, FullscreenOrigin.DRAG_DOWN_BAR)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 2062f))
        )
    }

    @Test fun fullscreenArmingHasHysteresisAndRetractionDisarmsIt() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        c.feed(move(RegionId.BAR_BOTTOM, 500f, 2100f))
        val near = c.feed(move(RegionId.BAR_BOTTOM, 500f, 2050f)).single() as WindowCommand.VerticalDragPreview
        assertTrue(near.showFullscreenHint)
        val back = c.feed(move(RegionId.BAR_BOTTOM, 500f, 2020f)).single() as WindowCommand.VerticalDragPreview
        assertFalse(back.showFullscreenHint)
        assertEquals(listOf(WindowCommand.ClearVerticalDragPreview(1)), c.feed(up(RegionId.BAR_BOTTOM, 500f, 2020f)))
    }

    @Test fun returningADragToItsOriginDoesNotBecomeADoubleTap() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1000L))
        c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1010L))
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1100L))
        c.feed(move(RegionId.BAR_BOTTOM, 500f, 2100f))
        c.feed(move(RegionId.BAR_BOTTOM, 500f, 1900f))
        assertEquals(listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1200L)))
    }

    @Test
    fun barDownHintRequiresStrictlyBeyondThreshold() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        val preview = c.feed(move(RegionId.BAR_BOTTOM, 500f, 2060f)).single() as WindowCommand.VerticalDragPreview
        assertEquals(1f, preview.progress, 0.0001f)
        assertFalse(preview.showFullscreenHint)
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 2060f))
        )
    }

    @Test
    fun barSwipeWorksOnSideBarToo() {
        val c = controller()
        c.feed(press(RegionId.BAR_SIDE, 950f, 1000f))
        assertEquals(
            listOf<WindowCommand>(
                WindowCommand.VerticalDragPreview(1, 1f, downward = false, showFullscreenHint = false)
            ),
            c.feed(move(RegionId.BAR_SIDE, 950f, 838f))
        )
        assertEquals(
            listOf(WindowCommand.RequestClose(1, CloseOrigin.DRAG_UP_BAR)),
            c.feed(up(RegionId.BAR_SIDE, 950f, 838f))
        )
    }

    @Test
    fun barCancelRestoresPreviewWithoutClosingOrRaising() {
        val c = controller(top = false)
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        c.feed(move(RegionId.BAR_BOTTOM, 500f, 1738f))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(cancel(RegionId.BAR_BOTTOM, 500f, 1738f))
        )
        assertFalse(c.snapshot().dragging)
    }

    @Test
    fun bodyCancelDiscardsHoveredMiniTargetWithoutRaising() {
        val c = controller(top = false)
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        val released = c.feed(cancel(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(listOf(WindowCommand.ClampToScreen(1, true), WindowCommand.DropZoneHide(1)), released)
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
        assertFalse(c.snapshot().dragging)
    }

    @Test
    fun downwardBarCancelDoesNotEnterFullscreen() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        c.feed(move(RegionId.BAR_BOTTOM, 500f, 2200f))
        assertEquals(listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(cancel(RegionId.BAR_BOTTOM, 500f, 2200f)))
    }

    @Test
    fun cancellationBreaksAnEarlierDoubleTapCandidate() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1000L))
        c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1010L))
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1100L))
        c.feed(cancel(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1110L))
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1200L))
        assertEquals(listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1210L)))
    }

    @Test
    fun firstBarTapNearClockOriginIsNotADoubleTap() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 10L))
        assertEquals(listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 20L)))
    }

    @Test
    fun resizeCancelCommitsLikeUp() {
        val c = controller()
        c.feed(press(RegionId.RESIZE_HANDLE, 900f, 1900f))
        assertEquals(
            listOf(WindowCommand.ResizePreviewCommit(1)),
            c.feed(cancel(RegionId.RESIZE_HANDLE, 900f, 1900f))
        )
        assertFalse(c.snapshot().resizing)
    }

    @Test
    fun barDoubleTapClosesWindowAcrossBothBars() {
        val c = controller()
        // 第一次点按：复位预览并计时，不关闭
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1000L))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1010L))
        )
        // 双击窗口内第二次点按（跨栏共享计时）：请求关闭
        c.feed(press(RegionId.BAR_SIDE, 950f, 1000f, timeMs = 1200L))
        assertEquals(
            listOf(
                WindowCommand.ClearVerticalDragPreview(1),
                WindowCommand.RequestClose(1, CloseOrigin.DOUBLE_TAP_BAR)
            ),
            c.feed(up(RegionId.BAR_SIDE, 950f, 1000f, timeMs = 1210L))
        )
        // 超时后重新计时：不再关闭
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1600L))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1610L))
        )
    }

    @Test
    fun barTapBeyondSlopIsNotATap() {
        val c = controller()
        // 位移超过 slop（8dp × density2 = 16px）：两次都不计数、不关闭
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1000L))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 530f, 1900f, timeMs = 1010L))
        )
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1100L))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1930f, timeMs = 1110L))
        )
    }

    @Test
    fun barCancelDoesNotCountAsTap() {
        val c = controller()
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1000L))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(cancel(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1010L))
        )
        // Cancel 不计数：随后一次 Up 不构成双击
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1100L))
        assertEquals(
            listOf(WindowCommand.ClearVerticalDragPreview(1)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 1900f, timeMs = 1110L))
        )
    }

    @Test
    fun failedMiniCommandRevertsModeViaResult() {
        val c = controller()
        val enter = c.feed(requestMini()).single() as WindowCommand.EnterMini
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        assertEquals(emptyList<WindowCommand>(), c.feed(result(enter.cmdId, CommandStatus.FAILED)))
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
        // 回退后单击不再误发 ExitMini
        assertEquals(emptyList<WindowCommand>(), c.feed(tapConfirmed()))
    }

    @Test
    fun staleOrDoneResultsConvergeWithoutModeChange() {
        val c = controller()
        val enter = c.feed(requestMini()).single() as WindowCommand.EnterMini
        // 陈旧结果（非当前未决命令）：忽略
        assertEquals(emptyList<WindowCommand>(), c.feed(result(enter.cmdId + 41L, CommandStatus.FAILED)))
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        // Done：收敛未决登记，模式保持
        assertEquals(emptyList<WindowCommand>(), c.feed(result(enter.cmdId, CommandStatus.DONE)))
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        // 已收敛后的重复回灌：忽略
        assertEquals(emptyList<WindowCommand>(), c.feed(result(enter.cmdId, CommandStatus.FAILED)))
        assertEquals(ShellMode.MINI, c.snapshot().mode)
    }

    @Test
    fun exitMiniResultFailureRevertsToMini() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        c.feed(up(RegionId.BODY_MASK, 10f, 10f))
        val exit = c.feed(tapConfirmed()).single() as WindowCommand.ExitMini
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
        assertEquals(emptyList<WindowCommand>(), c.feed(result(exit.cmdId, CommandStatus.FAILED)))
        assertEquals(ShellMode.MINI, c.snapshot().mode)
    }

    @Test
    fun screenMetricsChangedEventFeedsDropHover() {
        val c = WindowShellController(registration, CoreConfig(), FakeTopPort())
        c.feed(WindowInputEvent.ScreenMetricsChanged(1, 0L, 1000, 2000))
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        val commands = c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(WindowCommand.DropHover(1, DropKind.CORNER_TL, true), commands.last())
    }

    @Test
    fun resizeFlow() {
        val c = controller()
        assertEquals(
            listOf(WindowCommand.ResizePreviewStart(1)),
            c.feed(press(RegionId.RESIZE_HANDLE, 900f, 1900f))
        )
        assertTrue(c.snapshot().resizing)
        assertEquals(
            listOf(WindowCommand.ResizePreviewUpdate(1, 30f, 20f)),
            c.feed(move(RegionId.RESIZE_HANDLE, 930f, 1920f))
        )
        assertEquals(
            listOf(WindowCommand.ResizePreviewUpdate(1, 130f, 120f)),
            c.feed(move(RegionId.RESIZE_HANDLE, 1030f, 2020f))
        )
        assertEquals(
            listOf(WindowCommand.ResizePreviewCommit(1)),
            c.feed(up(RegionId.RESIZE_HANDLE, 1030f, 2020f))
        )
        assertFalse(c.snapshot().resizing)
    }

    @Test
    fun flingIsPassedThrough() {
        val c = controller()
        assertEquals(
            listOf(WindowCommand.FlingBy(1, 1200f, -800f)),
            c.feed(fling(1200f, -800f))
        )
    }

    @Test
    fun eventsForOtherWindowsAreIgnored() {
        val c = controller()
        assertEquals(emptyList<WindowCommand>(), c.feed(press(RegionId.BODY_MASK, 1f, 1f, windowId = 2)))
        assertEquals(emptyList<WindowCommand>(), c.feed(up(RegionId.CONTENT, windowId = 2)))
    }

    @Test
    fun moveWithoutPressIsIgnored() {
        val c = controller()
        assertEquals(emptyList<WindowCommand>(), c.feed(move(RegionId.BAR_BOTTOM, 500f, 1000f)))
    }

    @Test
    fun upAndCancelWithoutPressAreIgnored() {
        val c = controller(top = false)
        assertEquals(emptyList<WindowCommand>(), c.feed(up(RegionId.BAR_BOTTOM, 500f, 1738f)))
        assertEquals(emptyList<WindowCommand>(), c.feed(cancel(RegionId.BODY_MASK, 10f, 10f)))
    }

    @Test
    fun moveForDifferentRegionThanPressIsIgnored() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 100f, 100f))
        assertEquals(emptyList<WindowCommand>(), c.feed(move(RegionId.BAR_BOTTOM, 500f, 1000f)))
    }

    @Test
    fun contentUpOnlyRaises() {
        val c = controller()
        assertEquals(emptyList<WindowCommand>(), c.feed(press(RegionId.CONTENT, 100f, 100f)))
        assertEquals(emptyList<WindowCommand>(), c.feed(up(RegionId.CONTENT, 100f, 100f)))
    }

    @Test
    fun requestMiniEntersCornerMiniAndTapExpands() {
        val c = controller()
        val enter = c.feed(requestMini()).single() as WindowCommand.EnterMini
        assertEquals(DropKind.CORNER_TR, enter.corner)
        assertTrue(enter.cmdId > 0L)
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        assertEquals(emptyList<WindowCommand>(), c.feed(requestMini()))
        val exit = c.feed(tapConfirmed()).single() as WindowCommand.ExitMini
        assertTrue(exit.cmdId > 0L)
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
    }
}
