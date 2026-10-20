package com.buildsession.betterYAMF.xposed.adapters.android17.launcher3

import com.buildsession.betterYAMF.xposed.adapters.*
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.QuickstepHookInstaller
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.LauncherSourceRetirement

/** LoS 24.0 Quickstep: display-scoped input, native final-UP commit and two-argument playback. */
internal object Android17Launcher3Adapter : LauncherAdapter {
    override val identity = AdapterIdentity("android17/launcher3", 37,
        "com.android.launcher3", AdapterEvidence.DEVICE_BASELINE, "LineageOS 24.0")
    internal val retirementSignature = LauncherSourceRetirement.Signature.TWO_ARGUMENTS

    override fun install(classLoader: ClassLoader) {
        QuickstepHookInstaller.install(identity, classLoader,
            retirementSignature, menu = true, taskbar = true)
    }
}
