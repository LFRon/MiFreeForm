package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/** Application pass-through and shell gestures have independent admission conditions. */
internal enum class ShellInputMode { BLOCKED, MINI, NORMAL }

/** Policy belongs to CORE; the API carries only the ordered, resolved input rectangles. */
internal object WindowHitPolicy {
    fun resolve(f: WindowSceneFrame, style: SceneStyle, mode: ShellInputMode): SceneInput {
        if (f.alpha <= 0f) return SceneInput.EMPTY
        if (mode == ShellInputMode.BLOCKED) {
            val bounds = f.fullscreen?.inputBounds ?: f.outer
            return SceneInput(bounds, null, listOf(HitRegion(bounds, null)))
        }
        f.sideStash?.let { stash ->
            // Clip before dispatch: only the visible icon/card is a target, never its old rect.
            // During restoration retain this shield until the final scene's commit fence.
            val rect = if (f.shellAlpha > 0f) f.outer else stash.bounds
            val visible = SideStashPolicy.visiblePart(rect, f.coordinateSpace) ?: return SceneInput.EMPTY
            return SceneInput(visible, null, listOf(HitRegion(visible, RegionId.STASH_HANDLE)))
        }
        val regions = mutableListOf<HitRegion>()
        fun add(rect: SceneRect, region: RegionId?) { regions += HitRegion(rect, region) }
        val full = f.fullscreen
        if (full != null) {
            if (full.allowsBarGesture) {
                if (f.portrait) add(SceneRect(f.outer.left, f.content.bottom, f.outer.width,
                    (f.outer.bottom - f.content.bottom).coerceAtLeast(0f)), RegionId.BAR_BOTTOM)
                else add(SceneRect(f.content.right, f.outer.top,
                    (f.outer.right - f.content.right).coerceAtLeast(0f), f.outer.height), RegionId.BAR_SIDE)
            }
            add(f.outer, null)
        } else if (mode == ShellInputMode.MINI) {
            add(f.outer, RegionId.BODY_MASK)
        } else {
            // An ordinary window keeps its real chrome hit regions while opening/settling.
            // Content remains consumed here until its own committed-frame fence opens it.
            add(f.content, null)
            val size = style.resizeTouchSize
            add(SceneRect(maxOf(f.outer.left, f.outer.right - size), maxOf(f.outer.top, f.outer.bottom - size),
                minOf(size, f.outer.width), minOf(size, f.outer.height)), RegionId.RESIZE_HANDLE)
            add(SceneRect(f.outer.left, f.outer.top, f.outer.width, (f.content.top - f.outer.top).coerceAtLeast(0f)), RegionId.HANDLE)
            add(f.outer, if (f.portrait) RegionId.BAR_BOTTOM else RegionId.BAR_SIDE)
        }
        return SceneInput(f.fullscreen?.inputBounds ?: f.outer, if (f.acceptsContent) f.content else null, regions.toList())
    }
}
