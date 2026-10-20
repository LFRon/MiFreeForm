package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/**
 * 窗口位偏移夹取策略（零平台依赖；宿主只读平台值并执行）：
 * 把窗口位置 (x, y) 夹回 [0, 屏宽-窗宽-帧偏移] × [0, 屏高-窗高-帧偏移] 的可见域，
 * 保证窗口四边（含帧阴影 [frameOffsetX]/[frameOffsetY]）不出屏。
 * 行为与原宿主实现逐式一致（含 coerceIn 语义）。
 */
object ClampPolicy {

    data class Offset(val x: Int, val y: Int)

    fun clampOffset(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        frameOffsetX: Int,
        frameOffsetY: Int
    ): Offset {
        val maxX = screenWidth - windowWidth - frameOffsetX
        val maxY = screenHeight - windowHeight - frameOffsetY
        return Offset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }
}
