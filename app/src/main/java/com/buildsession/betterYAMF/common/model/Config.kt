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
    /** 顶/底栏圆角（dp）：白顶栏与底部白容器的手柄圆角，默认 12dp，UI 可调。 */
    var barRoundedCorner: Int = 12,
    var sidebarPosition: Boolean = false,
    var animationSpeed: Float = 5100f,
    var forcePreventRelaunch: Boolean = false
) {
    data class HookLauncher(
        var hookRecents: Boolean = true,
        var hookTaskbar: Boolean = true,
    )
}

/** 旧版出厂默认窗口宽（dp）：视作"自动"（用户自定义尺寸仍按 dp 采用）。 */
private const val LEGACY_DEFAULT_WINDOW_WIDTH = 280

/**
 * 解析默认窗口尺寸（px，= 应用内容区大小）。
 * - 默认（自动）：屏幕比例 —— 宽 71%、高 41%（参考澎湃OS 6.jpg 点开态比例）；
 * - 显式尺寸（defaultWindowWidth/Height > 0 且宽度非旧版默认值）按 dp 采用；
 * - 不做 contain 适配：应用按窗口比例拉伸铺满（用户裁定"不需要保持宽高比"）。
 */
fun Config.resolveWindowSizePx(screenWidthPx: Int, screenHeightPx: Int): Pair<Int, Int> {
    val screenW = screenWidthPx.takeIf { it > 0 } ?: 1264
    val screenH = screenHeightPx.takeIf { it > 0 } ?: 2780
    val widthPx = if (defaultWindowWidth > 0 && defaultWindowWidth != LEGACY_DEFAULT_WINDOW_WIDTH) {
        defaultWindowWidth.dpToPx().toInt().coerceAtLeast(1)
    } else {
        (screenW * 71L / 100L).toInt().coerceAtLeast(1)
    }
    val heightPx = if (defaultWindowHeight > 0) {
        defaultWindowHeight.dpToPx().toInt().coerceAtLeast(1)
    } else {
        (screenH * 41L / 100L).toInt().coerceAtLeast(1)
    }
    return widthPx to heightPx
}
