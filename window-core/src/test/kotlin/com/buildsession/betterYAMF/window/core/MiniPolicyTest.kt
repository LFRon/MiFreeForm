package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

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

    /** 目标外壳 = mini 内衬（真机 = 纯描边宽 19px；此处用合成值校验纯逻辑）。 */
    private val insets = MiniPolicy.ShellInsets(13, 13, 13, 13)
    private val slotW = screenW * 285 / 1000
    private val slotH = screenH * 200 / 1000

    /** 内容 cx×cy 等比适配槽位后的卡片尺寸（与实现同一公式，供期望值推导）。 */
    private fun fitted(cw: Int, ch: Int): Pair<Int, Int> {
        val availW = (slotW - insets.width).coerceAtLeast(1)
        val availH = (slotH - insets.height).coerceAtLeast(1)
        val k = minOf(availW.toDouble() / cw, availH.toDouble() / ch)
        return ((cw * k).toInt().coerceAtLeast(1) + insets.width) to
                ((ch * k).toInt().coerceAtLeast(1) + insets.height)
    }

    private fun enter(corner: DropKind, currentTop: Int? = null, cw: Int = origW, ch: Int = origH) =
        MiniPolicy.enterTarget(
            corner = corner,
            screenWidth = screenW,
            screenHeight = screenH,
            marginPx = margin,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            currentTop = currentTop,
            contentWidth = cw,
            contentHeight = ch,
            insets = insets
        )

    @Test
    fun miniPreservesContentAspectRatio() {
        // 核心不变量（参考图帧_02/帧_05）：mini 与展开态宽高比一致 = 同一 UI 纯缩放
        val (w, h) = fitted(origW, origH)
        val out = enter(DropKind.CORNER_TR)
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        val contentAspect = (out.width - insets.width).toDouble() / (out.height - insets.height)
        assertEquals(origW.toDouble() / origH, contentAspect, 0.01)
    }

    @Test
    fun wideContentIsWidthLimitedAndTallContentHeightLimited() {
        // 超宽内容：受"槽位宽-外壳"约束
        val (ww, wh) = fitted(3000, 1000)
        val wide = enter(DropKind.CORNER_TL, cw = 3000, ch = 1000)
        assertEquals(ww, wide.width)
        assertEquals(wh, wide.height)
        assertEquals(slotW, wide.width)
        // 超高内容：受"槽位高-外壳"约束
        val (tw, th) = fitted(600, 3000)
        val tall = enter(DropKind.CORNER_TL, cw = 600, ch = 3000)
        assertEquals(tw, tall.width)
        assertEquals(th, tall.height)
        assertEquals(slotH, tall.height)
    }

    @Test
    fun cornerTargetsUseFittedSizeAndSnapToCorners() {
        val (w, h) = fitted(origW, origH)
        assertEquals(
            WindowRect(margin, topInset + margin, margin + w, topInset + margin + h),
            enter(DropKind.CORNER_TL)
        )
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
        val (w, h) = fitted(origW, origH)
        val expected = WindowRect(
            screenW - margin - w, topInset + margin,
            screenW - margin, topInset + margin + h
        )
        assertEquals(expected, enter(DropKind.CORNER_TR))
    }

    @Test
    fun sideTargetsUseSameSizeAndKeepCurrentTopClamped() {
        val (w, h) = fitted(origW, origH)
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
        val (_, h) = fitted(origW, origH)
        val maxY = screenH - h - margin
        assertEquals(margin, enter(DropKind.SIDE_LEFT, currentTop = 0).top)
        assertEquals(maxY, enter(DropKind.SIDE_LEFT, currentTop = 99999).top)
        assertEquals(margin, enter(DropKind.SIDE_LEFT, currentTop = null).top)
    }

    @Test
    fun sideTopClampsIntoBandOnTinyScreen() {
        val top = { currentTop: Int? ->
            MiniPolicy.enterTarget(
                corner = DropKind.SIDE_LEFT,
                screenWidth = 100,
                screenHeight = 100,
                marginPx = margin,
                topInsetPx = topInset,
                bottomInsetPx = bottomInset,
                currentTop = currentTop,
                contentWidth = 20,
                contentHeight = 40,
                insets = insets
            ).top
        }
        assertEquals(50, top(50))
        assertEquals(margin, top(0))
    }

    @Test
    fun missingContentSizeFallsBackToSlotSize() {
        val out = MiniPolicy.enterTarget(
            corner = DropKind.SIDE_LEFT,
            screenWidth = screenW,
            screenHeight = screenH,
            marginPx = margin,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            currentTop = 0,
            contentWidth = 0,
            contentHeight = 0,
            insets = insets
        )
        assertEquals(slotW, out.width)
        assertEquals(slotH, out.height)
    }

    @Test
    fun miniSizeMatchesReferenceProportions() {
        // 参考澎湃OS 帧_02/帧_05：真机 mini ≈ 360 宽（屏宽 28.5% 槽位内），比例与内容一致
        val out = enter(DropKind.SIDE_RIGHT, currentTop = 500, cw = 885, ch = 1345)
        assertEquals(360, out.width)
        val contentAspect = (out.width - insets.width).toDouble() / (out.height - insets.height)
        assertEquals(885.0 / 1345.0, contentAspect, 0.01)
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
            marginPx = margin,
            topInsetPx = topInset
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
            marginPx = margin,
            topInsetPx = topInset
        )
        // cx=666,cy=433 → left=166（有效）、top=-567→140（顶部安全带下界）
        assertEquals(WindowRect(166, 140, 1166, 2140), out)
    }

    @Test
    fun clampToScreenKeepsInsideBand() {
        // 顶边 100 < 安全带 140 → 拉到安全带下界；左界不受影响
        assertEquals(
            WindowRect(100, 140, 300, 440),
            MiniPolicy.clampToScreen(100, 100, 200, 300, screenW, screenH, margin, topInset)
        )
        assertEquals(
            WindowRect(1056, 2472, 1256, 2772),
            MiniPolicy.clampToScreen(99999, 99999, 200, 300, screenW, screenH, margin, topInset)
        )
    }

    @Test
    fun clampToScreenPullsStatusBarOverlapDownToSafeInset() {
        // 真机实锤回归：顶边 104（拖拽未夹取位置）必须拉到安全带 140（不得压状态栏 130）
        assertEquals(
            WindowRect(50, topInset, 1050, topInset + origH),
            MiniPolicy.clampToScreen(50, 104, origW, origH, screenW, screenH, margin, topInset)
        )
    }

    @Test
    fun exitRestoresStatusBarOverlappingMemoryToSafeInset() {
        // 真机实锤 bug 回归：拖拽到上方角落 mini 时记忆了未夹取的顶边（104 < 安全带 140），
        // 展开回位必须拉到安全带之下（旧实现只夹 margin=8 → 展开后小窗压上状态栏）
        // left=100 在右界内（不触发水平夹取），隔离顶边安全带行为
        val out = MiniPolicy.exitTarget(
            remembered = WindowRect(100, 104, 100 + origW, 104 + origH),
            current = WindowRect(880, 165, 1239, 632),
            originalWidth = origW,
            originalHeight = origH,
            screenWidth = screenW,
            screenHeight = screenH,
            marginPx = margin,
            topInsetPx = topInset
        )
        assertEquals(WindowRect(100, topInset, 100 + origW, topInset + origH), out)
    }
}
