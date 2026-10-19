package com.buildsession.betterYAMF.window.core

/** Public motion policy; no Android animator or task mechanism participates in these tracks. */
enum class FullscreenMotionKind { FOLLOW, RETURN, COVER, EXPAND, REVEAL }

object FullscreenAnimPolicy {
    const val DISARM_FRACTION = 0.8f

    /**
     * COS16 FlexibleWindowUtils#getChangeDumping: max * (1 - 1 / (1 + .5 * x / max)).
     * Here x is normalized pull distance. The 10% cap follows FlexibleTaskScaleManager's
     * overscroll range; unlike a hard clamp, additional finger travel keeps a diminishing effect.
     */
    fun pullScale(progress: Float): Float {
        val p = progress.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        return 1f + 0.1f * (1f - 1f / (1f + 2f * p))
    }

    fun spec(kind: FullscreenMotionKind, opacity: Boolean, timeScale: Float): SpringSpec {
        // COS16: follow response .15 / bounce .15; mask alpha .20 / bounce 0;
        // rebound response .35 / bounce 0. Expansion uses a slower, non-overshooting .40 response
        // so the bridge cannot uncover a screen edge while the real task is changing layout.
        val (response, bounce) = when {
            opacity -> (if (kind == FullscreenMotionKind.COVER) 0.15f else 0.20f) to 0f
            kind == FullscreenMotionKind.FOLLOW -> 0.15f to 0.15f
            kind == FullscreenMotionKind.EXPAND -> 0.40f to 0f
            else -> 0.35f to 0f
        }
        return SpringSpec.of(response * timeScale.coerceAtLeast(0.05f), bounce)
    }

    /** Top-anchored, uniformly scaled preview. Horizontal growth respects the display edges. */
    fun previewContent(
        base: SceneRect, progress: Float, metrics: SceneMetrics, margin: Float,
        rightDecoration: Float = 0f, bottomDecoration: Float = 0f
    ): SceneRect {
        val scale = minOf(pullScale(progress),
            ((metrics.width - margin * 2f - rightDecoration) / base.width).coerceAtLeast(1f),
            ((metrics.height - metrics.bottomInset - margin - bottomDecoration - base.top) / base.height).coerceAtLeast(1f))
        val width = base.width * scale
        val minX = minOf(margin, base.left)
        val maxX = (metrics.width - margin - rightDecoration - width).coerceAtLeast(minX)
        return SceneRect(
            (base.left + (base.width - width) / 2f).coerceIn(minX, maxX),
            base.top, width, base.height * scale
        )
    }
}
