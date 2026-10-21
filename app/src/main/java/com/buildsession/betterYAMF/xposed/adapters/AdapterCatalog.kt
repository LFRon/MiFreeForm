package com.buildsession.betterYAMF.xposed.adapters

import com.buildsession.betterYAMF.xposed.adapters.android15.launcher3.Android15Launcher3Adapter
import com.buildsession.betterYAMF.xposed.adapters.android16.launcher3.Android16Launcher3Adapter
import com.buildsession.betterYAMF.xposed.adapters.android16.pixelLauncher.Android16PixelLauncherAdapter
import com.buildsession.betterYAMF.xposed.adapters.android17.launcher3.Android17Launcher3Adapter
import com.buildsession.betterYAMF.xposed.adapters.android17.pixelLauncher.Android17PixelLauncherAdapter

/** Exact keys: no package substring matching, SDK >= fallback, or mixing individual probes. */
internal object AdapterCatalog {
    val launchers: List<LauncherAdapter> = listOf(Android15Launcher3Adapter,
        Android16Launcher3Adapter, Android16PixelLauncherAdapter,
        Android17Launcher3Adapter, Android17PixelLauncherAdapter)

    /** Metadata selects one implementation; its installer must still resolve the complete ABI.
     * Matching these facts does not turn an unaudited ROM into a supported target.
     */
    fun selectLauncher(sdk: Int, packageName: String, processName: String,
        platform: PlatformIdentity = PlatformIdentity(), launcherVersionCode: Long? = null): LauncherSelection {
        if (platform.excluded) return LauncherSelection.Unavailable("ROM with its own freeform implementation is outside scope")
        if (processName != packageName) return LauncherSelection.Unavailable("not the Launcher main process")
        val matches = launchers.filter { it.identity.sdk == sdk && it.identity.launcherPackage == packageName }
        if (matches.size != 1) return LauncherSelection.Unavailable(
            if (matches.isEmpty()) "no adapter for SDK $sdk / $packageName" else "ambiguous adapter registration")
        val adapter = matches.single()
        if (adapter.identity.evidence == AdapterEvidence.PENDING)
            return LauncherSelection.Unavailable("${adapter.identity.id}: native mechanism has not been adapted")
        if (adapter.identity.launcherVersionCode != null && adapter.identity.launcherVersionCode != launcherVersionCode)
            return LauncherSelection.Unavailable("${adapter.identity.id}: Launcher APK is outside the reviewed build range")
        return LauncherSelection.Selected(adapter)
    }

    /** Deferred until a Context and the user's preferred Home are available. Installing hooks
     * for a loaded package does not activate it: its service must also satisfy these facts.
     * The reviewed handoff uses integrated Home/Overview; a split Home/Quickstep combination
     * needs its own audit rather than borrowing either installed Launcher's implementation.
     */
    fun selectRuntimeLauncher(runtime: LauncherRuntimeIdentity,
        platform: PlatformIdentity = PlatformIdentity()): LauncherSelection {
        val selection = selectLauncher(runtime.sdk, runtime.packageName, runtime.processName,
            platform, runtime.launcherVersionCode)
        if (selection !is LauncherSelection.Selected) return selection
        if (runtime.defaultHomePackage == null)
            return LauncherSelection.Unavailable("current user has no resolved default Home")
        if (runtime.defaultHomePackage != runtime.packageName)
            return LauncherSelection.Unavailable("default Home ${runtime.defaultHomePackage} differs from Quickstep host ${runtime.packageName}; split Home/Overview is not adapted")
        if (runtime.recentsPackage == null)
            return LauncherSelection.Unavailable("configured Recents component is unavailable")
        if (runtime.recentsPackage != runtime.packageName)
            return LauncherSelection.Unavailable("configured Recents host ${runtime.recentsPackage} differs from loaded Launcher ${runtime.packageName}")
        return selection
    }

}
