package com.buildsession.betterYAMF.xposed.adapters.launcher.pixel

import com.buildsession.betterYAMF.xposed.adapters.*

/** PixelOS 16 needs its own framework/APK audit; APK 907 targets SDK 37 and is not evidence for it. */
internal object Android16PixelLauncherAdapter : LauncherAdapter {
    override val identity = AdapterIdentity("pixel/16", 36,
        "com.google.android.apps.nexuslauncher", AdapterEvidence.PENDING, "PixelOS 16")
    override fun install(classLoader: ClassLoader): Unit = error("Pixel Launcher / Android 16 adaptation is pending")
}
