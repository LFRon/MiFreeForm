package com.buildsession.betterYAMF.common.model

data class Config(
    var coloredController: Boolean = false,
    var recentBackHome: Boolean = false,
    var defaultWindowWidth: Int = 280,
    var defaultWindowHeight: Int = 380,
    var hookLauncher: HookLauncher = HookLauncher(),
    var showForceShowIME: Boolean = false,
    var portraitY: Int = 0,
    var landscapeY: Int = 0,
    var favApps: MutableList<FavApps> = mutableListOf(),
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
        var hookPopup: Boolean = true,
        var hookTransientTaskbar: Boolean = false,
    )
}
