package com.buildsession.betterYAMF.xposed.hook

import com.buildsession.betterYAMF.xposed.adapters.*
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Framework entry only: bind one exact Android + loaded APK unit. Its service subsequently
 * validates current preferred Home and Recents identities; package load alone cannot activate it.
 */
class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in AdapterCatalog.launchers.map { it.identity.launcherPackage }) return
        val version = AndroidEnvironment.launcherVersionCode(lpparam.appInfo)
        when (val selection = AdapterCatalog.selectLauncher(AndroidEnvironment.sdk,
            lpparam.packageName, lpparam.processName, AndroidEnvironment.identity, version)) {
            is LauncherSelection.Unavailable -> logWarning(TAG, selection.reason)
            is LauncherSelection.Selected -> {
                EzXHelperInit.initHandleLoadPackage(lpparam)
                val adapter = selection.adapter
                runCatching { adapter.install(lpparam.classLoader) }
                    .onSuccess { log(TAG) { "selected ${adapter.identity.id}, reference=${adapter.identity.referenceRom}, evidence=${adapter.identity.evidence}, build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" } }
                    .onFailure { logWarning(TAG, "${adapter.identity.id}: native binding failed; preserve Launcher", it) }
            }
        }
    }

    companion object { private const val TAG = "reYAMF_Adapters" }
}
