package com.buildsession.betterYAMF.manager.sidebar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarControllerTest {

    private fun press(rawX: Float = 10f, rawY: Float = 100f, baseY: Int = 500) =
        SidebarInputEvent.Press(rawX, rawY, baseY)

    private fun move(rawX: Float, rawY: Float) = SidebarInputEvent.Move(rawX, rawY)

    private fun up(rawX: Float = 10f, rawY: Float = 100f) = SidebarInputEvent.Up(rawX, rawY)

    private val tapCommands = listOf(
        SidebarCommand.Haptic,
        SidebarCommand.OpenMenu,
        SidebarCommand.StopSidebar
    )

    @Test
    fun holdEntersDragWithHaptic() {
        val c = SidebarController()
        assertEquals(emptyList<SidebarCommand>(), c.onInput(press()))
        assertFalse(c.dragging)
        assertEquals(listOf(SidebarCommand.Haptic), c.onInput(SidebarInputEvent.HoldElapsed))
        assertTrue(c.dragging)
    }

    @Test
    fun holdIsIdempotent() {
        val c = SidebarController()
        c.onInput(press())
        c.onInput(SidebarInputEvent.HoldElapsed)
        assertEquals(emptyList<SidebarCommand>(), c.onInput(SidebarInputEvent.HoldElapsed))
    }

    @Test
    fun moveBeforeHoldDoesNotMoveWindow() {
        val c = SidebarController()
        c.onInput(press())
        assertEquals(emptyList<SidebarCommand>(), c.onInput(move(20f, 130f)))
    }

    @Test
    fun moveAfterHoldMovesByAnchorDeltaAndPersists() {
        val c = SidebarController()
        c.onInput(press(rawX = 10f, rawY = 50f, baseY = 100))
        c.onInput(SidebarInputEvent.HoldElapsed)
        assertEquals(
            listOf(SidebarCommand.MoveToY(140, persist = true)),
            c.onInput(move(10f, 90f))
        )
    }

    @Test
    fun moveHasNoClampAndAllowsNegativeY() {
        val c = SidebarController()
        c.onInput(press(rawX = 10f, rawY = 50f, baseY = 10))
        c.onInput(SidebarInputEvent.HoldElapsed)
        // 锚点 10 + 累计位移 -50 = -40（现状无夹取，如实行保留）
        assertEquals(
            listOf(SidebarCommand.MoveToY(-40, persist = true)),
            c.onInput(move(10f, 0f))
        )
    }

    @Test
    fun upWithoutVerticalMovementIsTap() {
        val c = SidebarController()
        c.onInput(press(rawY = 100f))
        assertEquals(tapCommands, c.onInput(up(rawY = 100f)))
        assertFalse(c.dragging)
    }

    @Test
    fun upWithVerticalMovementAndSmallSwipeIsNoop() {
        val c = SidebarController()
        c.onInput(press(rawY = 100f))
        c.onInput(move(50f, 300f))
        assertEquals(emptyList<SidebarCommand>(), c.onInput(up(50f, 300f)))
    }

    @Test
    fun upWithHorizontalSwipeOverThresholdOpensMenu() {
        val c = SidebarController()
        c.onInput(press(rawY = 100f))
        c.onInput(move(250f, 300f))
        assertEquals(tapCommands, c.onInput(up(250f, 300f)))
    }

    @Test
    fun swipeThresholdUsesAbsoluteRawXAndClearsAfterFiring() {
        val c = SidebarController()
        c.onInput(press(rawY = 100f))
        c.onInput(move(201f, 300f))
        assertEquals(tapCommands, c.onInput(up(201f, 300f)))
        // swipeX 已被滑动分支清零：下一次点按只命中点按分支（不叠加）
        c.onInput(press(rawY = 100f))
        assertEquals(tapCommands, c.onInput(up(rawY = 100f)))
    }

    @Test
    fun tapAndSwipeBranchesStackWhenBothConditionsHold() {
        val c = SidebarController()
        // 单次手势：X 划过阈值且 Y 未变 ⇒ 点按分支与滑动分支同时命中（与现状 if 顺序一致，双份命令）
        c.onInput(press(rawY = 100f))
        c.onInput(move(300f, 100f))
        assertEquals(tapCommands + tapCommands, c.onInput(up(300f, 100f)))
    }

    @Test
    fun holdAfterUpIsIgnored() {
        val c = SidebarController()
        c.onInput(press())
        c.onInput(up())
        assertEquals(emptyList<SidebarCommand>(), c.onInput(SidebarInputEvent.HoldElapsed))
        assertFalse(c.dragging)
    }

    @Test
    fun moveAfterUpOnlyUpdatesSwipeSample() {
        val c = SidebarController()
        c.onInput(press(rawY = 100f))
        c.onInput(up(rawY = 100f))
        assertEquals(emptyList<SidebarCommand>(), c.onInput(move(500f, 400f)))
    }

    @Test
    fun dragThenUpWithChangedYStopsWithoutMenu() {
        val c = SidebarController()
        c.onInput(press(rawX = 10f, rawY = 50f, baseY = 100))
        c.onInput(SidebarInputEvent.HoldElapsed)
        assertEquals(listOf(SidebarCommand.MoveToY(150, persist = true)), c.onInput(move(10f, 100f)))
        assertEquals(emptyList<SidebarCommand>(), c.onInput(up(10f, 100f)))
        assertFalse(c.dragging)
    }
}
