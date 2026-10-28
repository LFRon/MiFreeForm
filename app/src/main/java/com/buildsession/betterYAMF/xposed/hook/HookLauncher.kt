package com.buildsession.betterYAMF.xposed.hook

import android.content.pm.ApplicationInfo
import com.buildsession.betterYAMF.xposed.adapters.*
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Framework entry only: bind one exact Android + loaded APK unit. Its service subsequently
 * validates current preferred Home and Recents identities; package load alone cannot activate it.
 */
internal object HookLauncher {
    fun install(classLoader: ClassLoader, processName: String, appInfo: ApplicationInfo?) {
        val packageName = appInfo?.packageName ?: return
        if (packageName !in AdapterCatalog.launchers.map { it.identity.launcherPackage }) return
        val version = AndroidEnvironment.launcherVersionCode(appInfo)
        when (val selection = AdapterCatalog.selectLauncher(
            AndroidEnvironment.sdk, packageName, processName, AndroidEnvironment.identity, version,
        )) {
            is LauncherSelection.Unavailable -> logWarning(TAG, selection.reason)
            is LauncherSelection.Selected -> {
                val adapter = selection.adapter
                runCatching { adapter.install(classLoader) }
                    .onSuccess { log(TAG) { "selected ${adapter.identity.id}, reference=${adapter.identity.referenceRom}, evidence=${adapter.identity.evidence}, build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" } }
                    .onFailure { logWarning(TAG, "${adapter.identity.id}: native binding failed; preserve Launcher", it) }
            }
        }
    }

    private const val TAG = "reYAMF_Adapters"
}
