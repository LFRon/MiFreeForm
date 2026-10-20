package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

sealed interface RecentsEntryEffect {
    data object QueryCandidate : RecentsEntryEffect
    data class Present(val frame: EntryTargetFrame) : RecentsEntryEffect
    data class Suppress(val durationMs: Long) : RecentsEntryEffect
    data class Drop(val request: EntryDrop) : RecentsEntryEffect
}

/** Existing HookLauncher rules, now shared and independent of Quickstep's input implementation. */
class RecentsEntryController {
    private var potential = false
    private var armed = false
    private var showing = false
    private var taskId = -1
    private var startY = 0f
    private var heightAtDown = 0
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
                heightAtDown = value.height
                displayAtDown = value.displayId
                potential = value.gestureY > heightAtDown * 0.95
                armed = potential
                if (armed) effects += RecentsEntryEffect.Suppress(900L)
            }
            PointerPhase.MOVE -> if (potential) {
                if (startY - value.gestureY > heightAtDown * 0.2) {
                    if (!showing) effects += RecentsEntryEffect.QueryCandidate
                } else showing = false
            }
            PointerPhase.UP -> {
                if (armed) effects += RecentsEntryEffect.Suppress(450L)
                if (showing && inZone(value) && taskId > 0)
                    effects += RecentsEntryEffect.Drop(EntryDrop(taskId, value.x.toInt(), value.y.toInt()))
                reset()
            }
            PointerPhase.CANCEL -> {
                if (armed) effects += RecentsEntryEffect.Suppress(450L)
                reset()
            }
        }
        effects += RecentsEntryEffect.Present(visual(value))
        return effects
    }

    fun candidate(value: EntryCandidate?): EntryTargetFrame? {
        if (value != null && value.taskId > 0 && !value.home && (value.launcherResumed || value.sourceVisible)) {
            taskId = value.taskId; showing = true
        } else { potential = false; showing = false; taskId = -1 }
        return current?.let(::visual)
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
