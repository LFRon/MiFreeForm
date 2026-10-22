package com.buildsession.betterYAMF.window.api

/** Ordered hit regions; an explicit null consumes a touch without starting a gesture. */
data class HitRegion(val bounds: SceneRect, val region: RegionId?)
/** CORE's complete admission map. Adapters choose the region at DOWN and retain that identity
 * until UP/CANCEL; they must not reinterpret a blocked content area as a window-move gesture.
 */
data class SceneInput(val bounds: SceneRect?, val passThrough: SceneRect?, val regions: List<HitRegion>) {
    fun regionAt(x: Float, y: Float): RegionId? = regions.firstOrNull { it.bounds.contains(x, y) }?.region
    companion object { val EMPTY = SceneInput(null, null, emptyList()) }
}
