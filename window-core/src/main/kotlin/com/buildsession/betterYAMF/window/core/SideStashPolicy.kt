package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/** 7.mp4: a collapsing card reveals its application icon and leaves part of it at the edge.
 * Reuse the existing position/size springs; these are product geometry and opacity rules only.
 * No velocity threshold: admission is the existing SIDE_LEFT / SIDE_RIGHT drop decision.
 */
internal object SideStashPolicy {
    private const val ICON_SIZE_DP = 52f
    private const val VISIBLE_FRACTION = 0.45f

    fun size(metrics: SceneMetrics, style: SceneStyle): Float = minOf(
        ICON_SIZE_DP * style.density, metrics.width.toFloat(),
        (metrics.height - metrics.topInset - metrics.bottomInset - style.margin * 2f).coerceAtLeast(1f)
    ).coerceAtLeast(1f)

    private fun centerRange(metrics: SceneMetrics, style: SceneStyle): Pair<Float, Float> {
        val half = size(metrics, style) / 2f
        val top = metrics.topInset + style.margin + half
        return top to (metrics.height - metrics.bottomInset - style.margin - half).coerceAtLeast(top)
    }

    fun anchor(content: SceneRect, metrics: SceneMetrics, style: SceneStyle): Float {
        val (top, bottom) = centerRange(metrics, style)
        if (bottom <= top) return 0.5f
        return ((content.top + content.height / 2f - top) / (bottom - top)).coerceIn(0f, 1f)
    }

    fun target(side: WindowSide, anchor: Float, metrics: SceneMetrics, style: SceneStyle): SceneRect {
        val size = size(metrics, style)
        val (top, bottom) = centerRange(metrics, style)
        val centerY = top + (bottom - top) * anchor.coerceIn(0f, 1f)
        val peek = size * VISIBLE_FRACTION
        // Stashed icons peek from the physical edge. Insets constrain ordinary content/admission,
        // not this off-screen target; otherwise a landscape cutout exposes the entire icon.
        return SceneRect(if (side == WindowSide.LEFT) peek - size else metrics.width - peek,
            centerY - size / 2f, size, size)
    }

    fun icon(content: SceneRect, metrics: SceneMetrics, style: SceneStyle): SceneRect {
        val size = minOf(size(metrics, style), content.width, content.height)
        return SceneRect(content.left + (content.width - size) / 2f,
            content.top + (content.height - size) / 2f, size, size)
    }

    fun iconAlpha(fraction: Float): Float {
        val t = ((fraction - 0.45f) / 0.5f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun visiblePart(rect: SceneRect, metrics: SceneMetrics): SceneRect? {
        val left = rect.left.coerceAtLeast(0f)
        val top = rect.top.coerceAtLeast(0f)
        val right = rect.right.coerceAtMost(metrics.width.toFloat())
        val bottom = rect.bottom.coerceAtMost(metrics.height.toFloat())
        return if (right > left && bottom > top) SceneRect(left, top, right - left, bottom - top) else null
    }
}
