package com.buildsession.betterYAMF.xposed.hook

import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.SystemAdapters
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Framework entry only. Native hook points live in the selected Android implementation. */
class HookSystem : IXposedHookZygoteInit, IXposedHookLoadPackage {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        val adapter = SystemAdapters.current ?: run {
            logWarning(TAG, "No system adapter in scope for SDK ${AndroidEnvironment.sdk}; skip native hooks")
            return
        }
        EzXHelperInit.initHandleLoadPackage(lpparam)
        runCatching { adapter.install(lpparam.classLoader) }
            .onSuccess { log(TAG) { "selected system adapter: SDK ${adapter.sdk}, build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" } }
            .onFailure { logWarning(TAG, "System adapter initialization failed", it) }
    }

    companion object { private const val TAG = "reYAMF_Adapters" }
}
