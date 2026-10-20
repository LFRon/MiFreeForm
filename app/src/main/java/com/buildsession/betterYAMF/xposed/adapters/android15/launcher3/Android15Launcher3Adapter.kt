package com.buildsession.betterYAMF.xposed.adapters.android15.launcher3

import com.buildsession.betterYAMF.xposed.adapters.*

/** Owner for the LineageOS 22.2 target. No Android 17 task/UP mechanism is borrowed here. */
internal object Android15Launcher3Adapter : LauncherAdapter {
    override val identity = AdapterIdentity("android15/launcher3", 35,
        "com.android.launcher3", AdapterEvidence.PENDING, "LineageOS 22.2")
    override fun install(classLoader: ClassLoader): Unit = error("Android 15 adaptation is pending")
}
