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
    init {
        // Vector injects legacy modules into system_server very early and may not deliver
        // handleLoadPackage before ActivityManagerService.systemReady. Install from the system
        // server's class loader as soon as the module instance exists.
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID) {
            val loader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
            installSystemHooks(loader)
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
        if (startupParam.startsSystemServer) {
            installSystemHooks(Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader())
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Some frameworks expose the system-server package as "system" instead of "android".
        if (lpparam.packageName != "android" && lpparam.packageName != "system") return
        log(TAG) { "system package=${lpparam.packageName} process=${lpparam.processName} uid=${android.os.Process.myUid()}" }
        installSystemHooks(lpparam.classLoader)
    }

    @Synchronized
    private fun installSystemHooks(loader: ClassLoader?) {
        if (installed || loader == null || !canLoad(loader)) return
        val adapter = SystemAdapters.current ?: run {
            logWarning(TAG, "No system adapter in scope for SDK ${AndroidEnvironment.sdk}; skip native hooks")
            return
        }
        try {
            EzXHelperInit.setEzClassLoader(loader)
            adapter.install(loader)
            installed = true
            log(TAG) { "selected system adapter: SDK ${adapter.sdk}, build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" }
        } catch (error: Throwable) {
            logWarning(TAG, "System adapter initialization failed", error)
        }
    }

    private fun canLoad(loader: ClassLoader): Boolean =
        runCatching { loader.loadClass("com.android.server.am.ActivityManagerService") }.isSuccess

    companion object {
        private const val TAG = "reYAMF_Adapters"
        @Volatile private var installed = false
    }
}
