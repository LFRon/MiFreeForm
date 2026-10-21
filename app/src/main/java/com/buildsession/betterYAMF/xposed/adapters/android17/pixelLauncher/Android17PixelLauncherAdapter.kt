package com.buildsession.betterYAMF.xposed.adapters.android17.pixelLauncher

import com.buildsession.betterYAMF.xposed.adapters.*
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.QuickstepHookInstaller
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.LauncherSourceRetirement
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.RecentsDropHandoff
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** PixelOS seventeen, Launcher 17/907 DEX: display input + three-argument StateManager playback.
 * R8 removes the RemoteActionShortcut constructor used by LoS. Its native desktop/freeform entries
 * are instead filtered at the final list, only when the MiWindow backend is actually usable.
 */
internal object Android17PixelLauncherAdapter : LauncherAdapter {
    override val identity = AdapterIdentity("android17/pixelLauncher", 37,
        "com.google.android.apps.nexuslauncher", AdapterEvidence.SOURCE_REVIEWED, "PixelOS 17", launcherVersionCode = 907L)
    internal val retirementSignature = LauncherSourceRetirement.Signature.THREE_ARGUMENTS
    private var shortcutsInstalled = false

    override fun install(classLoader: ClassLoader) {
        QuickstepHookInstaller.install(identity, classLoader,
            retirementSignature, menu = false, taskbar = false, nativeDownAdmission = true)
        if (!shortcutsInstalled) runCatching {
            val native = PixelNativeFreeformAccess(classLoader)
            native.enabled.hookAfter { call ->
                if (!QuickstepHookInstaller.isCurrentLauncher ||
                    !RecentsDropHandoff.backendAvailable() || call.throwable != null) return@hookAfter
                val items = call.result as? List<*> ?: return@hookAfter
                val filtered = native.filter(items)
                if (filtered.size != items.size) call.result = filtered
            }
            shortcutsInstalled = true
        }.onFailure { logWarning("reYAMF_Adapters", "Pixel native shortcuts isolation unavailable", it) }
    }
}
