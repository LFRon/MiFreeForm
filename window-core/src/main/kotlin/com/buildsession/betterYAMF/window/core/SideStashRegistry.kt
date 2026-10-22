package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*
import kotlin.math.abs
import kotlin.math.floor

/** Process-scene policy, accessed only by the shared serial owner. Reservations start at drop
 * intent and survive the restore animation until its terminal frame commits. No IME metrics.
 * Reflow keeps older reservations first; overflow is reported by absence, never overlapping.
 */
class SideStashRegistry {
    data class Slot(val side: WindowSide, val anchor: Float)
    private data class Reservation(val id: Int, val side: WindowSide, val desired: Float, val order: Long,
        var slot: Slot)
    private val reservations = linkedMapOf<Int, Reservation>()
    private var sequence = 0L
    private var geometry: Pair<SceneMetrics, Float>? = null

    fun configure(metrics: SceneMetrics, style: SceneStyle): Boolean {
        val key = metrics to style.density
        if (geometry == key) return false
        geometry = key
        val old = reservations.values.sortedBy { it.order }
        reservations.clear()
        old.forEach { r -> nearest(r.side, r.desired, metrics, style)?.let {
            r.slot = it; reservations[r.id] = r
        } }
        return true
    }

    fun reserve(id: Int, side: WindowSide, anchor: Float, metrics: SceneMetrics, style: SceneStyle): Slot? {
        configure(metrics, style)
        reservations[id]?.let { return it.slot }
        val desired = anchor.coerceIn(0f, 1f)
        val slot = nearest(side, desired, metrics, style) ?: return null
        reservations[id] = Reservation(id, side, desired, sequence++, slot)
        return slot
    }

    fun get(id: Int): Slot? = reservations[id]?.slot
    fun release(id: Int) { reservations.remove(id) }

    private fun nearest(side: WindowSide, desired: Float, metrics: SceneMetrics, style: SceneStyle): Slot? {
        val size = SideStashPolicy.size(metrics, style)
        val gap = 8f * style.density
        val available = metrics.height - metrics.topInset - metrics.bottomInset - style.margin * 2f
        if (available < size) return null
        val count = floor((available + gap) / (size + gap)).toInt().coerceAtLeast(1)
        val range = (available - size).coerceAtLeast(0f)
        val anchors = if (count == 1) listOf(0.5f) else
            (0 until count).map { it.toFloat() / (count - 1) }
        return anchors.filter { a -> reservations.values.none {
            it.side == side && abs(it.slot.anchor - a) * range < size + gap - 0.1f
        } }.minByOrNull { abs(it - desired) }?.let { Slot(side, it) }
    }
}
