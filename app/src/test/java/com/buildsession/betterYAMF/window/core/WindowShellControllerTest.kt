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
        assertEquals(
            listOf(WindowCommand.EnterMini(1, DropKind.CORNER_TL), WindowCommand.DropZoneHide(1)),
            c.feed(up(RegionId.BODY_MASK, 10f, 10f))
        )
        assertEquals(ShellMode.MINI, c.snapshot().mode)
    }

    @Test
    fun sideDropEntersSideMiniAndTapConfirmedExpands() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 1000f))
        c.feed(move(RegionId.BODY_MASK, 10f, 1000f))
        assertEquals(
            listOf(WindowCommand.EnterMini(1, DropKind.SIDE_LEFT), WindowCommand.DropZoneHide(1)),
            c.feed(up(RegionId.BODY_MASK, 10f, 1000f))
        )
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        assertEquals(listOf<WindowCommand>(WindowCommand.ExitMini(1)), c.feed(tapConfirmed()))
        assertEquals(ShellMode.NORMAL, c.snapshot().mode)
    }

    @Test
    fun miniTapConfirmedExitsMini() {
        val c = controller()
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        c.feed(up(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(ShellMode.MINI, c.snapshot().mode)
        assertEquals(listOf<WindowCommand>(WindowCommand.ExitMini(1)), c.feed(tapConfirmed()))
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
        assertEquals(
            listOf(WindowCommand.EnterMini(1, DropKind.SIDE_LEFT), WindowCommand.DropZoneHide(1)),
            c.feed(up(RegionId.BODY_MASK, 10f, 1000f))
        )
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
        assertEquals(1f, preview.progress, 0.0001f)
        assertTrue(preview.downward)
        assertTrue(preview.showFullscreenHint)
        assertEquals(
            listOf(WindowCommand.RequestFullscreen(1, FullscreenOrigin.DRAG_DOWN_BAR)),
            c.feed(up(RegionId.BAR_BOTTOM, 500f, 2062f))
        )
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
    fun barCancelBehavesLikeUpButNeverRaises() {
        val c = controller(top = false)
        c.feed(press(RegionId.BAR_BOTTOM, 500f, 1900f))
        c.feed(move(RegionId.BAR_BOTTOM, 500f, 1738f))
        assertEquals(
            listOf(WindowCommand.RequestClose(1, CloseOrigin.DRAG_UP_BAR)),
            c.feed(cancel(RegionId.BAR_BOTTOM, 500f, 1738f))
        )
    }

    @Test
    fun bodyCancelBehavesLikeUpButNeverRaises() {
        val c = controller(top = false)
        c.feed(press(RegionId.BODY_MASK, 10f, 10f))
        c.feed(move(RegionId.BODY_MASK, 10f, 10f))
        assertEquals(
            listOf(WindowCommand.EnterMini(1, DropKind.CORNER_TL), WindowCommand.DropZoneHide(1)),
            c.feed(cancel(RegionId.BODY_MASK, 10f, 10f))
        )
        assertEquals(ShellMode.MINI, c.snapshot().mode)
    }

    @Test
    fun resizeCancelIsIgnored() {
        val c = controller()
        c.feed(press(RegionId.RESIZE_HANDLE, 900f, 1900f))
        assertEquals(emptyList<WindowCommand>(), c.feed(cancel(RegionId.RESIZE_HANDLE, 900f, 1900f)))
    }

    @Test
    fun barDoubleTapSharesTimerAcrossBothBars() {
        val c = controller()
        assertEquals(emptyList<WindowCommand>(), c.feed(tap(RegionId.BAR_BOTTOM, timeMs = 1000L)))
        assertEquals(
            listOf(WindowCommand.RequestClose(1, CloseOrigin.DOUBLE_TAP_BAR)),
            c.feed(tap(RegionId.BAR_SIDE, timeMs = 1299L))
        )
        assertEquals(emptyList<WindowCommand>(), c.feed(tap(RegionId.BAR_BOTTOM, timeMs = 1600L)))
        assertEquals(
            listOf(WindowCommand.RequestClose(1, CloseOrigin.DOUBLE_TAP_BAR)),
            c.feed(tap(RegionId.BAR_SIDE, timeMs = 1899L))
        )
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
}
