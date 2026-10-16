package com.buildsession.betterYAMF.window.core

/**
 * mini/贴边几何策略（纯逻辑，零平台依赖；宿主只负责传值与执行）。
 *
 * 模型（参考澎湃OS 5.jpg）：mini 目标 = **屏幕比例尺寸**——窗宽 ≈ 屏宽 28.5%、
 * 窗高 ≈ 屏高 20%；与角落/侧边无关（只影响贴靠位置）。应用在框内拉伸铺满
 * （不保比例，见 writeLeashTransform），故 mini 尺寸不再随应用内容区/分除变化。
 *
 * 公式约定（整数除法、coerce 次序、insets 为原始像素值）：
 * - 角落 TL/BL/BR 贴对应角，TR 与未知 kind 走"右上角"兜底；
 * - 侧边 y 以进入前的位置为基准并夹取到 [margin, screenH-h-margin]（下界不小于 margin）。
 */
object MiniPolicy {

    /** 外壳固定厚度：窗口卡与内容区之差（分除不作用于它）。 */
    data class ShellInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = left + right
        val height: Int get() = top + bottom

        companion object {
            /** 逐帧插值（mini 进出动画中，外壳厚度随模式平滑过渡）。 */
            fun lerp(from: ShellInsets, to: ShellInsets, f: Float): ShellInsets = ShellInsets(
                (from.left + (to.left - from.left) * f).toInt(),
                (from.top + (to.top - from.top) * f).toInt(),
                (from.right + (to.right - from.right) * f).toInt(),
                (from.bottom + (to.bottom - from.bottom) * f).toInt()
            )
        }
    }

    fun enterTarget(
        corner: DropKind,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int,
        topInsetPx: Int,
        bottomInsetPx: Int,
        currentTop: Int?
    ): WindowRect {
        // 屏幕比例尺寸（整数除法，与参考图逐项对齐）
        val w = screenWidth * 285 / 1000
        val h = screenHeight * 200 / 1000
        val margin = marginPx
        return when (corner) {
            DropKind.CORNER_TL -> WindowRect(
                margin, topInsetPx + margin, margin + w, topInsetPx + margin + h
            )
            DropKind.CORNER_BL -> WindowRect(
                margin, screenHeight - bottomInsetPx - margin - h,
                margin + w, screenHeight - bottomInsetPx - margin
            )
            DropKind.CORNER_BR -> WindowRect(
                screenWidth - margin - w, screenHeight - bottomInsetPx - margin - h,
                screenWidth - margin, screenHeight - bottomInsetPx - margin
            )
            DropKind.SIDE_LEFT -> {
                val y = sideY(currentTop, screenHeight, h, margin)
                WindowRect(margin, y, margin + w, y + h)
            }
            DropKind.SIDE_RIGHT -> {
                val y = sideY(currentTop, screenHeight, h, margin)
                WindowRect(screenWidth - margin - w, y, screenWidth - margin, y + h)
            }
            else -> WindowRect(
                screenWidth - margin - w, topInsetPx + margin,
                screenWidth - margin, topInsetPx + margin + h
            )
        }
    }

    /** 退出 mini 的目标：记忆矩形优先；无记忆则围绕当前中心展开；两者都经安全边距夹取。 */
    fun exitTarget(
        remembered: WindowRect?,
        current: WindowRect,
        originalWidth: Int,
        originalHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int
    ): WindowRect {
        return if (remembered != null) {
            clampToScreen(
                remembered.left, remembered.top,
                originalWidth, originalHeight,
                screenWidth, screenHeight, marginPx
            )
        } else {
            val cx = (current.left + current.right) / 2
            val cy = (current.top + current.bottom) / 2
            clampToScreen(
                cx - originalWidth / 2, cy - originalHeight / 2,
                originalWidth, originalHeight,
                screenWidth, screenHeight, marginPx
            )
        }
    }

    /**
     * 屏内夹取（安全边距）：任何"自动定位"都必须保证窗口四边可见——
     * 贴屏会让圆角/边框/阴影被屏幕边界裁掉。窄屏时下界取 [marginPx]。
     */
    fun clampToScreen(
        leftIn: Int,
        topIn: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int
    ): WindowRect {
        val maxLeft = (screenWidth - width - marginPx).coerceAtLeast(marginPx)
        val maxTop = (screenHeight - height - marginPx).coerceAtLeast(marginPx)
        val left = leftIn.coerceIn(marginPx, maxLeft)
        val top = topIn.coerceIn(marginPx, maxTop)
        return WindowRect(left, top, left + width, top + height)
    }

    private fun sideY(currentTop: Int?, screenHeight: Int, h: Int, margin: Int): Int =
        (currentTop ?: 0).coerceIn(margin, (screenHeight - h - margin).coerceAtLeast(margin))
}
