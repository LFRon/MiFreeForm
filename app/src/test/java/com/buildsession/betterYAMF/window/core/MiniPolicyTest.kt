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

    private fun enter(corner: DropKind, currentTop: Int? = null) = MiniPolicy.enterTarget(
        corner = corner,
        screenWidth = screenW,
        screenHeight = screenH,
        originalWidth = origW,
        originalHeight = origH,
        marginPx = margin,
        topInsetPx = topInset,
        bottomInsetPx = bottomInset,
        currentTop = currentTop
    )

    @Test
    fun cornerTargetsUseHalfSize() {
        val w = origW / 2
        val h = origH / 2
        assertEquals(WindowRect(margin, topInset + margin, margin + w, topInset + margin + h), enter(DropKind.CORNER_TL))
        assertEquals(
            WindowRect(margin, screenH - bottomInset - margin - h, margin + w, screenH - bottomInset - margin),
            enter(DropKind.CORNER_BL)
        )
        assertEquals(
            WindowRect(
                screenW - margin - w, screenH - bottomInset - margin - h,
                screenW - margin, screenH - bottomInset - margin
            ),
            enter(DropKind.CORNER_BR)
        )
    }

    @Test
    fun cornerTrAndUnknownFallBackToTopRight() {
        val expected = WindowRect(
            screenW - margin - origW / 2, topInset + margin,
            screenW - margin, topInset + margin + origH / 2
        )
        assertEquals(expected, enter(DropKind.CORNER_TR))
    }

    @Test
    fun sideTargetsUseThirdSizeAndKeepCurrentTopClamped() {
        val w = origW / 3
        val h = origH / 3
        assertEquals(
            WindowRect(margin, 500, margin + w, 500 + h),
            enter(DropKind.SIDE_LEFT, currentTop = 500)
        )
        assertEquals(
            WindowRect(screenW - margin - w, 500, screenW - margin, 500 + h),
            enter(DropKind.SIDE_RIGHT, currentTop = 500)
        )
    }

    @Test
    fun sideTopIsClampedToSafeBand() {
        val h = origH / 3
        val maxY = screenH - h - margin
        assertEquals(margin, enter(DropKind.SIDE_LEFT, currentTop = 0).top)
        assertEquals(maxY, enter(DropKind.SIDE_LEFT, currentTop = 99999).top)
        assertEquals(margin, enter(DropKind.SIDE_LEFT, currentTop = null).top)
    }

    @Test
    fun sideTopBottomLimitNeverBelowMarginOnTinyScreen() {
        val out = MiniPolicy.enterTarget(
            corner = DropKind.SIDE_LEFT,
            screenWidth = 100,
            screenHeight = 100,
            originalWidth = origW,
            originalHeight = origH,
            marginPx = margin,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            currentTop = 50
        )
        assertEquals(margin, out.top)
    }

    @Test
    fun sizeUsesIntegerDivision() {
        val out = MiniPolicy.enterTarget(
            corner = DropKind.SIDE_LEFT,
            screenWidth = screenW,
            screenHeight = screenH,
            originalWidth = 1001,
            originalHeight = 2002,
            marginPx = margin,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            currentTop = 0
        )
        assertEquals(1001 / 3, out.width)
        assertEquals(2002 / 3, out.height)
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
