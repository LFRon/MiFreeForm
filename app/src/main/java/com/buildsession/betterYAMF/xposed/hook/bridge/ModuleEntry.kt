package com.buildsession.betterYAMF.xposed.hook.bridge

import com.buildsession.betterYAMF.xposed.hook.HookLauncher
import com.buildsession.betterYAMF.xposed.hook.HookShell
import com.buildsession.betterYAMF.xposed.hook.HookSystem
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * The single modern entry. libxposed instantiates it once per process generation and delivers the
 * lifecycle callbacks declared below.
 *
 * A single entry keeps hot reload possible (the API only supports it for exactly one entry) but the
 * module does not depend on it. Nothing may be done in the constructor: the framework attaches the
 * interface between construction and [onModuleLoaded].
 */
class ModuleEntry : XposedModule() {
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Framework.bind(this)
        log(TAG) {
            "module loaded process=${param.processName} systemServer=${param.isSystemServer} " +
                "framework=${frameworkName} version=${frameworkVersion} api=${apiVersion} " +
                "caps=$frameworkProperties build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}"
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!Framework.systemServerSupported()) {
            logWarning(TAG, "framework does not declare system_server support; system hooks skipped")
            return
        }
        HookSystem.install(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // The callback has no process name; the package's classloader can serve several processes.
        val process = android.app.Application.getProcessName()
        if (process != param.packageName) {
            if (param.packageName == SYSTEM_UI) {
                log("reYAMF_Shell") { "skip SystemUI secondary process $process" }
            }
            return
        }
        when (param.packageName) {
            SYSTEM_UI -> HookShell.install(param.classLoader, param.applicationInfo)
            else -> HookLauncher.install(param.classLoader, process, param.applicationInfo)
        }
    }

    private companion object {
        const val TAG = "reYAMF_Adapters"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
