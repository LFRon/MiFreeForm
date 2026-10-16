package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPolicyTest {

    private val screenW = 1264
    private val screenH = 2780
    private val origW = 1000
    private val origH = 2000
    private val margin = 8
    private val topInset = 140
    private val bottomInset = 120

    private val miniW = screenW * 285 / 1000
    private val miniH = screenH * 200 / 1000

    private fun enter(corner: DropKind, currentTop: Int? = null) = MiniPolicy.enterTarget(
        corner = corner,
        screenWidth = screenW,
        screenHeight = screenH,
        marginPx = margin,
        topInsetPx = topInset,
        bottomInsetPx = bottomInset,
        currentTop = currentTop
    )

    @Test
    fun cornerTargetsUseScreenProportionalSize() {
        assertEquals(
            WindowRect(margin, topInset + margin, margin + miniW, topInset + margin + miniH),
            enter(DropKind.CORNER_TL)
        )
        assertEquals(
            WindowRect(margin, screenH - bottomInset - margin - miniH, margin + miniW, screenH - bottomInset - margin),
            enter(DropKind.CORNER_BL)
        )
        assertEquals(
            WindowRect(
                screenW - margin - miniW, screenH - bottomInset - margin - miniH,
                screenW - margin, screenH - bottomInset - margin
            ),
            enter(DropKind.CORNER_BR)
        )
    }

    @Test
    fun cornerTrAndUnknownFallBackToTopRight() {
        val expected = WindowRect(
            screenW - margin - miniW, topInset + margin,
            screenW - margin, topInset + margin + miniH
        )
        assertEquals(expected, enter(DropKind.CORNER_TR))
    }

    @Test
    fun sideTargetsUseSameSizeAndKeepCurrentTopClamped() {
        assertEquals(
            WindowRect(margin, 500, margin + miniW, 500 + miniH),
            enter(DropKind.SIDE_LEFT, currentTop = 500)
        )
        assertEquals(
            WindowRect(screenW - margin - miniW, 500, screenW - margin, 500 + miniH),
            enter(DropKind.SIDE_RIGHT, currentTop = 500)
        )
    }

    @Test
    fun sideTopIsClampedToSafeBand() {
        val maxY = screenH - miniH - margin
        assertEquals(margin, enter(DropKind.SIDE_LEFT, currentTop = 0).top)
        assertEquals(maxY, enter(DropKind.SIDE_LEFT, currentTop = 99999).top)
        assertEquals(margin, enter(DropKind.SIDE_LEFT, currentTop = null).top)
    }

    @Test
    fun sideTopClampsIntoBandOnTinyScreen() {
        // 小屏 100x100：mini (28x20) 可放下；有效值保持、越界值夹回安全带
        val top = { currentTop: Int? ->
            MiniPolicy.enterTarget(
                corner = DropKind.SIDE_LEFT,
                screenWidth = 100,
                screenHeight = 100,
                marginPx = margin,
                topInsetPx = topInset,
                bottomInsetPx = bottomInset,
                currentTop = currentTop
            ).top
        }
        assertEquals(50, top(50))
        assertEquals(margin, top(0))
    }

    @Test
    fun sizeUsesIntegerDivisionOnScreenProportion() {
        val out = MiniPolicy.enterTarget(
            corner = DropKind.SIDE_LEFT,
            screenWidth = 1001,
            screenHeight = 2002,
            marginPx = margin,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            currentTop = 0
        )
        assertEquals(1001 * 285 / 1000, out.width)
        assertEquals(2002 * 200 / 1000, out.height)
    }

    @Test
    fun miniSizeMatchesReferenceProportions() {
        // 参考澎湃OS 5.jpg：mini ≈ 屏宽 28.5% x 屏高 20%（真机 1264x2780 → 360x556）
        val out = enter(DropKind.SIDE_RIGHT, currentTop = 500)
        assertEquals(360, out.width)
        assertEquals(556, out.height)
        // 比例对齐参考图（≈0.65），与屏比/内容比无关
        assertEquals(0.653, out.width.toDouble() / out.height, 0.01)
    }

    @Test
    fun shellInsetsLerpInterpolatesAllSides() {
        val from = MiniPolicy.ShellInsets(2, 10, 4, 6)
        val to = MiniPolicy.ShellInsets(20, 30, 40, 50)
        assertEquals(MiniPolicy.ShellInsets(11, 20, 22, 28), MiniPolicy.ShellInsets.lerp(from, to, 0.5f))
        assertEquals(from, MiniPolicy.ShellInsets.lerp(from, to, 0f))
        assertEquals(to, MiniPolicy.ShellInsets.lerp(from, to, 1f))
    }

    @Test
    fun exitWithMemoryClampsRememberedRect() {
        val out = MiniPolicy.exitTarget(
            remembered = WindowRect(-50, 3000, 950, 5000),
            current = WindowRect(500, 100, 833, 766),
            originalWidth = origW,
            originalHeight = origH,
            screenWidth = screenW,
            screenHeight = screenH,
            marginPx = margin
        )
        // left=-50→8（下界）；top=3000→772（上界 screenH-origH-margin）
        assertEquals(WindowRect(8, 772, 8 + origW, 772 + origH), out)
    }

    @Test
    fun exitWithoutMemoryExpandsAroundCurrentCenterThenClamps() {
        val out = MiniPolicy.exitTarget(
            remembered = null,
            current = WindowRect(500, 100, 833, 766),
            originalWidth = origW,
            originalHeight = origH,
            screenWidth = screenW,
            screenHeight = screenH,
            marginPx = margin
        )
        // cx=666,cy=433 → left=166（有效）、top=-567→8（下界）
        assertEquals(WindowRect(166, 8, 1166, 2008), out)
    }

    @Test
    fun clampToScreenKeepsInsideBand() {
        assertEquals(
            WindowRect(100, 100, 300, 400),
            MiniPolicy.clampToScreen(100, 100, 200, 300, screenW, screenH, margin)
        )
        assertEquals(
            WindowRect(1056, 2472, 1256, 2772),
            MiniPolicy.clampToScreen(99999, 99999, 200, 300, screenW, screenH, margin)
        )
    }
}
