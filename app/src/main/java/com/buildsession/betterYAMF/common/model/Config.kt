package com.buildsession.betterYAMF.common.model

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
