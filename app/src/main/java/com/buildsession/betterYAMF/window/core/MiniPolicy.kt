package com.buildsession.betterYAMF.window.core

/**
 * mini/贴边几何策略（纯逻辑，零平台依赖；宿主只负责传值与执行）。
 *
 * 模型（参考澎湃OS 帧_02/帧_05）：**mini 与展开态是同一 UI 的纯缩放——宽高比完全一致**。
 * mini 尺寸 = 当前窗口内容区等比缩放到磁吸槽位（屏宽 28.5% × 屏高 20%，按"填满槽位"的
 * 较小比例缩放，比例恒等）。比例一致带来两个结构性好处：
 * 1) 过渡 = 纯几何缩放（视口降采样），内容无拉伸、**应用零重排**（显示器尺寸全程不变）；
 * 2) mini 观感 = 展开态的缩略图，缩略图与磁吸位置无关（角落/侧边只影响贴靠位置）。
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
        currentTop: Int?,
        contentWidth: Int,
        contentHeight: Int,
        insets: ShellInsets
    ): WindowRect {
        // 磁吸槽位（屏比例，与参考图逐项对齐）；内容区等比缩放到"槽位减去目标外壳"的区域，
        // 卡片 = 缩放后内容 + 目标外壳（insets）⇒ 卡片恒不超出槽位，且内容比例与展开态一致。
        val slotW = screenWidth * 285 / 1000
        val slotH = screenHeight * 200 / 1000
        val availW = (slotW - insets.width).coerceAtLeast(1)
        val availH = (slotH - insets.height).coerceAtLeast(1)
        val cw = if (contentWidth > 0) contentWidth else availW
        val ch = if (contentHeight > 0) contentHeight else availH
        val k = minOf(availW.toDouble() / cw, availH.toDouble() / ch)
        val w = (cw * k).toInt().coerceAtLeast(1) + insets.width
        val h = (ch * k).toInt().coerceAtLeast(1) + insets.height
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

    /**
     * 退出 mini 的目标：记忆矩形优先；无记忆则围绕当前中心展开；两者都经安全边距 +
     * 顶部安全带（[topInsetPx]）夹取——拖拽到上方角落 mini 化时记忆/中心展开的顶边
     * 可能落在状态栏区域内（拖拽本身不夹取），回位必须拉到安全带之下（真机实锤 bug：
     * 旧实现只夹 [marginPx]，展开后小窗压上状态栏）。
     */
    fun exitTarget(
        remembered: WindowRect?,
        current: WindowRect,
        originalWidth: Int,
        originalHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int,
        topInsetPx: Int
    ): WindowRect {
        return if (remembered != null) {
            clampToScreen(
                remembered.left, remembered.top,
                originalWidth, originalHeight,
                screenWidth, screenHeight, marginPx, topInsetPx
            )
        } else {
            val cx = (current.left + current.right) / 2
            val cy = (current.top + current.bottom) / 2
            clampToScreen(
                cx - originalWidth / 2, cy - originalHeight / 2,
                originalWidth, originalHeight,
                screenWidth, screenHeight, marginPx, topInsetPx
            )
        }
    }

    /**
     * 屏内夹取（安全边距 + 顶部安全带）：任何"自动定位"都必须保证窗口四边可见——
     * 贴屏会让圆角/边框/阴影被屏幕边界裁掉。窄屏时下界取 [marginPx]；
     * 顶边下界 = max([marginPx], [topInsetPx])（状态栏安全带不得被压，
     * 与 mini 触点/投放起点同一安全带语义）。
     */
    fun clampToScreen(
        leftIn: Int,
        topIn: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int,
        topInsetPx: Int
    ): WindowRect {
        val minTop = topInsetPx.coerceAtLeast(marginPx)
        val maxLeft = (screenWidth - width - marginPx).coerceAtLeast(marginPx)
        val maxTop = (screenHeight - height - marginPx).coerceAtLeast(minTop)
        val left = leftIn.coerceIn(marginPx, maxLeft)
        val top = topIn.coerceIn(minTop, maxTop)
        return WindowRect(left, top, left + width, top + height)
    }

    private fun sideY(currentTop: Int?, screenHeight: Int, h: Int, margin: Int): Int =
        (currentTop ?: 0).coerceIn(margin, (screenHeight - h - margin).coerceAtLeast(margin))
}
