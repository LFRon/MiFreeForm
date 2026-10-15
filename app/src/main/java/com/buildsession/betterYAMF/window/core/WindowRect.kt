package com.buildsession.betterYAMF.window.core

/** 纯逻辑矩形（零平台依赖；宿主负责与 android.graphics.Rect 互转）。 */
data class WindowRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
