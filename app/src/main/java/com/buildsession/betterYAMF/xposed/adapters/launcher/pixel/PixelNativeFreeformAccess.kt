package com.buildsession.betterYAMF.xposed.adapters.launcher.pixel

/** Pixel 17/907 final shortcut list. Match actual native types, never localized labels. */
internal class PixelNativeFreeformAccess(loader: ClassLoader) {
    private val desktop = loader.loadClass("com.android.quickstep.DesktopShortcut")
    private val freeform = loader.loadClass("com.android.quickstep.TaskShortcutFactory\$FreeformSystemShortcut")
    val enabled = loader.loadClass("com.android.quickstep.TaskOverlayFactory").getDeclaredMethod("getEnabledShortcuts",
        loader.loadClass("com.android.quickstep.views.TaskView"), loader.loadClass("com.android.quickstep.views.TaskContainer"))
        .apply { isAccessible = true; check(List::class.java.isAssignableFrom(returnType)) }
    fun filter(items: List<*>): List<*> = items.filterNot { desktop.isInstance(it) || freeform.isInstance(it) }
}
