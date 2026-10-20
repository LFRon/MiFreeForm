package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/** Existing automatic 71% x 41% size and explicit dp settings, resolved without Android resources. */
object WindowLayoutPolicy {
    fun size(options: WindowOptions, metrics: SceneMetrics): Pair<Int, Int> {
        require(metrics.width > 0 && metrics.height > 0)
        val w = if (options.widthDp > 0 && options.widthDp != 280)
            (options.widthDp * options.style.density).toInt().coerceAtLeast(1)
        else (metrics.width * 71L / 100).toInt().coerceAtLeast(1)
        val h = if (options.heightDp > 0)
            (options.heightDp * options.style.density).toInt().coerceAtLeast(1)
        else (metrics.height * 41L / 100).toInt().coerceAtLeast(1)
        return w to h
    }

    fun preferredTop(options: WindowOptions, metrics: SceneMetrics): Int =
        if (metrics.height >= metrics.width) options.portraitY else options.landscapeY

    fun layout(content: SceneRect, metrics: SceneMetrics, existingViewport: WindowRect? = null) = TaskLayout(content,
        existingViewport ?: WindowViewportPolicy.logicalBounds(metrics.width, content.rounded().width, content.rounded().height))

    fun dropVisual(kind: DropKind?, active: Boolean, metrics: SceneMetrics): DropTargetVisual {
        val w = metrics.width.toFloat(); val h = metrics.height.toFloat()
        return DropTargetVisual(kind, active, minOf(w, h) * DropTargetCalculator.CORNER_RADIUS_RATIO,
            SceneRect(0f, h * DropTargetCalculator.SIDE_HEIGHT_START_RATIO,
                w * DropTargetCalculator.SIDE_WIDTH_RATIO,
                h * (DropTargetCalculator.SIDE_HEIGHT_END_RATIO - DropTargetCalculator.SIDE_HEIGHT_START_RATIO)),
            SceneRect(w * (1f - DropTargetCalculator.SIDE_WIDTH_RATIO),
                h * DropTargetCalculator.SIDE_HEIGHT_START_RATIO, w * DropTargetCalculator.SIDE_WIDTH_RATIO,
                h * (DropTargetCalculator.SIDE_HEIGHT_END_RATIO - DropTargetCalculator.SIDE_HEIGHT_START_RATIO)), metrics)
    }
}
