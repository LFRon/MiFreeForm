package com.buildsession.betterYAMF.xposed.hook

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.android17.shell.Android17ShellAdapter
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellProcessIdentity
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Separate process boundary: system_server hooks cannot isolate Shell in SystemUI. */
class HookShell : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui" || lpparam.processName != lpparam.packageName ||
            AndroidEnvironment.sdk != 37 ||
            AndroidEnvironment.identity.excluded) return
        val uid = android.os.Process.myUid()
        val appUid = lpparam.appInfo?.uid ?: -1
        val systemUser = android.os.Process.myUserHandle() == android.os.UserHandle.getUserHandleForUid(0)
        if (!ShellProcessIdentity.acceptsHost(lpparam.packageName, lpparam.processName, systemUser, uid, appUid)) {
            logWarning("reYAMF_Shell", "Shell host identity rejected: uid=$uid applicationUid=$appUid systemUser=$systemUser")
            return
        }
        EzXHelperInit.initHandleLoadPackage(lpparam)
        runCatching { Android17ShellAdapter.install(lpparam.classLoader) }
            .onFailure { logWarning("reYAMF_Shell", "Android 17 Shell adapter initialization failed", it) }
        installNotificationWhenResolvable(lpparam.classLoader)
    }

    /**
     * Vector can deliver this package before SystemUI's own dex paths exist: the callback runs
     * inside `LoadedApk.getClassLoader()` for a window context, where the reported loader has an
     * empty `DexPathList`. Binding the notification classes there throws ClassNotFoundException
     * even though the APK contains them. Retry on main with the application loader, bounded.
     */
    private fun installNotificationWhenResolvable(earlyLoader: ClassLoader, attempt: Int = 0) {
        val main = Handler(Looper.getMainLooper())
        val probe = Runnable {
            val application = runCatching {
                Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
            }.getOrNull() as? Application
            val target = application?.classLoader ?: earlyLoader
            val ready = runCatching { Class.forName(NOTIFICATION_STARTER, false, target) }.isSuccess
            if (!ready) {
                if (attempt >= MAX_PROBES) {
                    logWarning("reYAMF_Notification",
                        "notification classes never resolved; Shell hooks remain active")
                } else {
                    main.postDelayed({ installNotificationWhenResolvable(earlyLoader, attempt + 1) }, RETRY_MS)
                }
                return@Runnable
            }
            runCatching {
                com.buildsession.betterYAMF.xposed.adapters.android17.notification.NotificationWindowAdapter.install(target)
            }.onSuccess {
                log("reYAMF_Notification") { "notification body-click capability installed attempt=$attempt" }
            }.onFailure {
                logWarning("reYAMF_Notification", "Notification capability unavailable; Shell hooks remain active", it)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) probe.run() else main.post(probe)
    }

    private companion object {
        const val NOTIFICATION_STARTER =
            "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter"
        const val RETRY_MS = 200L
        const val MAX_PROBES = 100 // Failure bound (~20 s), not a poll for product state.
    }
}
