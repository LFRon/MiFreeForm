package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

sealed interface RecentsEntryEffect {
    data object QueryCandidate : RecentsEntryEffect
    data class Present(val frame: EntryTargetFrame) : RecentsEntryEffect
    data class Suppress(val durationMs: Long) : RecentsEntryEffect
    data class Drop(val request: EntryDrop) : RecentsEntryEffect
}

/** Shared entry policy. Native tracking supplies the slop/ownership fact; no second screen-height
 * threshold competes with the platform's gesture recognition.
 */
class RecentsEntryController {
    private var potential = false
    private var armed = false
    private var showing = false
    private var taskId = -1
    private var startY = 0f
    private var displayAtDown = 0
    private var current: EntryInput? = null

    fun targetBounds(width: Int, height: Int) = RecentsGesturePolicy.dropZone(width, height)

    fun input(value: EntryInput): List<RecentsEntryEffect> {
        if (value.width <= 0 || value.height <= 0 || !value.x.isFinite() ||
            !value.y.isFinite() || !value.gestureY.isFinite() || value.rotation !in 0..3 ||
            value.phase != PointerPhase.DOWN && value.displayId != displayAtDown) {
            return cancel()?.let { listOf(RecentsEntryEffect.Present(it)) } ?: emptyList()
        }
        current = value
        val effects = mutableListOf<RecentsEntryEffect>()
        when (value.phase) {
            PointerPhase.DOWN -> {
                reset()
                startY = value.gestureY
                displayAtDown = value.displayId
                potential = value.navigationStart ?: (value.gestureY > value.height * 0.95)
                armed = potential
                if (armed) effects += RecentsEntryEffect.Suppress(900L)
            }
            PointerPhase.MOVE -> if (potential) {
                if (startY > value.gestureY) {
                    effects += RecentsEntryEffect.QueryCandidate
                } else showing = false
            }
            PointerPhase.UP -> {
                if (armed) effects += RecentsEntryEffect.Suppress(450L)
                // UP may supply the first ready facts or the last displacement into the target.
                if (potential && startY > value.gestureY) effects += RecentsEntryEffect.QueryCandidate
                else reset()
            }
            PointerPhase.CANCEL -> {
                if (armed) effects += RecentsEntryEffect.Suppress(450L)
                reset()
            }
        }
        if (RecentsEntryEffect.QueryCandidate !in effects) effects += RecentsEntryEffect.Present(visual(value))
        return effects
    }

    fun candidate(value: EntryCandidate?, capabilities: LauncherEntryCapabilities): List<RecentsEntryEffect> {
        val input = current ?: return emptyList()
        if (value?.home == true || value?.tracking == EntryTracking.REJECTED ||
            taskId > 0 && value != null && value.taskId > 0 && value.taskId != taskId) {
            potential = false
        }
        if (potential && taskId < 0 && value != null && value.taskId > 0) taskId = value.taskId
        showing = potential && value != null && value.taskId > 0 && !value.home &&
            value.tracking == EntryTracking.TRACKING && (capabilities.retainedSource || capabilities.legacyAfterNative)
        val effects = mutableListOf<RecentsEntryEffect>()
        if (input.phase == PointerPhase.UP) {
            if (showing && inZone(input))
                effects += RecentsEntryEffect.Drop(EntryDrop(taskId, input.x.toInt(), input.y.toInt()))
            reset()
        }
        effects += RecentsEntryEffect.Present(visual(input))
        return effects
    }

    fun fallback(capabilities: LauncherEntryCapabilities): EntryDisposition =
        if (capabilities.legacyAfterNative) EntryDisposition.LEGACY_QUEUED else EntryDisposition.PASS_THROUGH

    fun reset() { potential = false; armed = false; showing = false; taskId = -1 }
    fun cancel(): EntryTargetFrame? { reset(); return current?.let(::visual) }
    private fun inZone(value: EntryInput): Boolean {
        val r = targetBounds(value.width, value.height)
        return value.x >= r.left && value.x < r.right && value.y >= r.top && value.y < r.bottom
    }
    private fun visual(value: EntryInput) = EntryTargetFrame(targetBounds(value.width, value.height), showing,
        showing && inZone(value))
}
