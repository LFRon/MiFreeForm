package com.buildsession.betterYAMF.xposed.adapters.android16.launcher3

import com.buildsession.betterYAMF.xposed.adapters.*

/** Owner for the LineageOS 23.2 target; the selector never executes a pending implementation. */
internal object Android16Launcher3Adapter : LauncherAdapter {
    override val identity = AdapterIdentity("android16/launcher3", 36,
        "com.android.launcher3", AdapterEvidence.PENDING, "LineageOS 23.2")
    override fun install(classLoader: ClassLoader): Unit = error("Android 16 adaptation is pending")
}
