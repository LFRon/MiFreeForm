package com.buildsession.betterYAMF.window.core

/**
 * mini/贴边几何策略（纯逻辑，零平台依赖；宿主只负责传值与执行）。
 *
 * 公式与宿主旧实现逐位对齐（整数除法、coerce 次序、insets 为原始像素值）：
 * - 目标尺寸：角落 = 原尺寸 ÷ 2；侧边 = 原尺寸 ÷ 3；
 * - 角落 TL/BL/BR 贴对应角，TR 与未知 kind 走"右上角"兜底；
 * - 侧边 y 以进入前的位置为基准并夹取到 [margin, screenH-h-margin]（下界不小于 margin）。
 */
object MiniPolicy {

    fun enterTarget(
        corner: DropKind,
        screenWidth: Int,
        screenHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        marginPx: Int,
        topInsetPx: Int,
        bottomInsetPx: Int,
        currentTop: Int?
    ): WindowRect {
        val isSide = corner == DropKind.SIDE_LEFT || corner == DropKind.SIDE_RIGHT
        val miniDivisor = if (isSide) 3 else 2
        val w = originalWidth / miniDivisor
        val h = originalHeight / miniDivisor
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
