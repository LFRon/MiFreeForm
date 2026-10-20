package com.buildsession.betterYAMF.xposed.adapters.android17.pixelLauncher

import com.buildsession.betterYAMF.xposed.adapters.*
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.QuickstepHookInstaller
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.LauncherSourceRetirement

/** PixelOS seventeen, Launcher 17/907 DEX: display input + three-argument StateManager playback.
 * Source-reviewed candidate, NOT device-verified. R8 menu constructors and taskbar differ, so those
 * optional entries stay disabled until separately adapted. All native structural probes still apply.
 */
internal object Android17PixelLauncherAdapter : LauncherAdapter {
    override val identity = AdapterIdentity("android17/pixelLauncher", 37,
        "com.google.android.apps.nexuslauncher", AdapterEvidence.SOURCE_REVIEWED, "PixelOS 17", launcherVersionCode = 907L)
    internal val retirementSignature = LauncherSourceRetirement.Signature.THREE_ARGUMENTS

    override fun install(classLoader: ClassLoader) {
        QuickstepHookInstaller.install(identity, classLoader,
            retirementSignature, menu = false, taskbar = false)
    }
}
