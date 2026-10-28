package com.buildsession.betterYAMF.xposed.hook

import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.SystemAdapters
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/**
 * system_server installation. The framework dispatches this at the entry of
 * `SystemServer.startBootstrapServices`, so no zygote-time or constructor-time workaround is
 * needed: API 101+ modules are never injected into zygote.
 */
internal object HookSystem {
    fun install(loader: ClassLoader) {
        installSystemHooks(loader)
    }

    @Synchronized
    private fun installSystemHooks(loader: ClassLoader?) {
        if (installed || loader == null || !canLoad(loader)) return
        val adapter = SystemAdapters.current ?: run {
            logWarning(TAG, "No system adapter in scope for SDK ${AndroidEnvironment.sdk}; skip native hooks")
            return
        }
        try {
            adapter.install(loader)
            installed = true
            log(TAG) { "selected system adapter: SDK ${adapter.sdk}, build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" }
        } catch (error: Throwable) {
            logWarning(TAG, "System adapter initialization failed", error)
        }
    }

    private fun canLoad(loader: ClassLoader): Boolean =
        runCatching { loader.loadClass("com.android.server.am.ActivityManagerService") }.isSuccess

    private const val TAG = "reYAMF_Adapters"

    @Volatile
    private var installed = false
}
