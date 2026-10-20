package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.abs

/** Entry-only projection and replacement policy. Both images uniformly cover the shared viewport.
 *
 * 4.mp4 ~0.83-1.20s: fitting a full portrait image into a shorter viewport creates black pillars;
 * a whole-image fade then overlaps layouts of different scales. Keep the header anchored and
 * replace from the bottom with only a two-pixel seam. The task still receives one fixed layout.
 * Geometric tolerances select a similar scale, never replace platform draw readiness.
 */
object EntryTransitionPolicy {
    private const val SIZE_REMAINDER = 0.20f
    private const val ASPECT_REMAINDER = 0.06f
    private const val EDGE_FEATHER_PX = 2f

    fun canReveal(current: SceneRect, target: SceneRect): Boolean {
        val x = current.width / target.width
        val y = current.height / target.height
        if (!x.isFinite() || !y.isFinite() || x <= 0f || y <= 0f) return false
        return maxOf(abs(x - 1f), abs(y - 1f)) <= SIZE_REMAINDER &&
            1f - minOf(x / y, y / x) <= ASPECT_REMAINDER
    }

    /** Explicit cover crop, centered horizontally and anchored at the top. No letterboxing. */
    fun cover(bounds: SceneRect, aspect: Float): SceneRect {
        return bounds.coverTop(aspect)
    }

    fun sourceVisual(viewport: SceneRect, aspect: Float, remaining: Float): EntryImageVisual {
        val height = viewport.height * remaining.coerceIn(0f, 1f)
        // The initial source must cover every pixel, including its bottom edge, until ready.
        val feather = minOf(EDGE_FEATHER_PX, height, viewport.height - height).coerceAtLeast(0f)
        return EntryImageVisual(cover(viewport, aspect), viewport.copy(height = height), feather)
    }

    /** 4.mp4's ~200ms overlapping tail is too long. A critically damped edge replacement uses
     * half the existing MINIMIZE alpha response and keeps the geometry's independent velocity.
     */
    fun replacementSpec(timeScale: Float): SpringSpec = SpringSpec.of(
        MiniAnimPolicy.responseBounce(MiniAnimKind.MINIMIZE, MiniAnimProp.ALPHA).first *
            0.5f * timeScale.coerceIn(0.05f, 10f), 0f)
}
