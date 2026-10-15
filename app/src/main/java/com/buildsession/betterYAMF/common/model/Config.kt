package com.buildsession.betterYAMF.common.model

import com.buildsession.betterYAMF.common.ui.dpToPx

data class Config(
    var coloredController: Boolean = false,
    var defaultWindowWidth: Int = 280,
    var defaultWindowHeight: Int = 0,
    var hookLauncher: HookLauncher = HookLauncher(),
    var portraitY: Int = 0,
    var landscapeY: Int = 0,
    var launchSideBarAtBoot: Boolean = false,
    var enableSidebar: Boolean = true,
    var sidebarTransparency: Int = 80,
    var windowRoundedCorner: Int = 20,
    var sidebarPosition: Boolean = false,
    var animationSpeed: Float = 5100f,
    var forcePreventRelaunch: Boolean = false
) {
    data class HookLauncher(
        var hookRecents: Boolean = true,
        var hookTaskbar: Boolean = true,
    )
}

fun Config.resolveWindowSizePx(screenWidthPx: Int, screenHeightPx: Int): Pair<Int, Int> {
    val widthPx = defaultWindowWidth.dpToPx().toInt().coerceAtLeast(1)
    if (defaultWindowHeight > 0) {
        return widthPx to defaultWindowHeight.dpToPx().toInt().coerceAtLeast(1)
    }
    // 自动高度 = 保持屏幕比例（演示：窗口=整屏画面等比缩小，窗口比例=屏幕比例，无黑边）
    val heightPx = if (screenWidthPx > 0 && screenHeightPx > 0) {
        (widthPx.toLong() * screenHeightPx / screenWidthPx).toInt().coerceAtLeast(1)
    } else {
        (widthPx.toLong() * 1633L / 1000L).toInt().coerceAtLeast(1)
    }
    return widthPx to heightPx
}
