package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/** Existing automatic 71% x 41% size and explicit dp settings, resolved without Android resources. */
object WindowLayoutPolicy {
    fun size(options: WindowOptions, metrics: SceneMetrics): Pair<Int, Int> {
        require(metrics.width > 0 && metrics.height > 0)
        val landscape = metrics.width > metrics.height
        val shortEdge = minOf(metrics.width, metrics.height)
        val longEdge = maxOf(metrics.width, metrics.height)
        val w = if (options.widthDp > 0 && options.widthDp != 280)
            (options.widthDp * options.style.density).toInt().coerceAtLeast(1)
        else (shortEdge * 71L / 100).toInt().coerceAtLeast(1)
        val h = if (options.heightDp > 0)
            (options.heightDp * options.style.density).toInt().coerceAtLeast(1)
        else (longEdge * 41L / 100).toInt().coerceAtLeast(1)
        return if (landscape && w >= h) h to (w + if (w == h) 1 else 0) else w to h
    }

    fun preferredTop(options: WindowOptions, metrics: SceneMetrics): Int =
        if (metrics.height >= metrics.width) options.portraitY else options.landscapeY

    fun layout(content: SceneRect, metrics: SceneMetrics, existingViewport: WindowRect? = null, density: Float = 1f): TaskLayout {
        val portrait = content.height > content.width || metrics.width > metrics.height
        val existing = existingViewport?.takeIf { !portrait || it.height > it.width }
        val generated = WindowViewportPolicy.logicalBounds(minOf(metrics.width, metrics.height),
            content.rounded().width, content.rounded().height)
        // A near-square custom size must remain portrait after pixel-to-dp rounding as well.
        val viewport = existing ?: if (portrait) generated.copy(bottom = maxOf(generated.bottom,
            generated.top + generated.width + kotlin.math.ceil(density.toDouble()).toInt())) else generated
        return TaskLayout(content, viewport,
            if (portrait) ContentOrientation.PORTRAIT else ContentOrientation.UNSPECIFIED)
    }

    fun dropVisual(kind: DropKind?, active: Boolean, metrics: SceneMetrics): DropTargetVisual {
        val w = metrics.width.toFloat(); val h = metrics.height.toFloat()
        val start = if (w > h) metrics.topInset.toFloat() else h * DropTargetCalculator.SIDE_HEIGHT_START_RATIO
        val end = if (w > h) h - metrics.bottomInset else h * DropTargetCalculator.SIDE_HEIGHT_END_RATIO
        return DropTargetVisual(kind, active, minOf(w, h) * DropTargetCalculator.CORNER_RADIUS_RATIO,
            SceneRect(metrics.leftInset.toFloat(), start,
                w * DropTargetCalculator.SIDE_WIDTH_RATIO,
                (end - start).coerceAtLeast(1f)),
            SceneRect(w * (1f - DropTargetCalculator.SIDE_WIDTH_RATIO) - metrics.rightInset,
                start, w * DropTargetCalculator.SIDE_WIDTH_RATIO, (end - start).coerceAtLeast(1f)), metrics)
    }
}
