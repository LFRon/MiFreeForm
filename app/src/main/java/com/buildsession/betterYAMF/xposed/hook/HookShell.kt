package com.buildsession.betterYAMF.xposed.hook

import android.content.pm.ApplicationInfo
import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.android17.shell.Android17ShellAdapter
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellProcessIdentity
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Separate process boundary: system_server hooks cannot isolate Shell in SystemUI. */
internal object HookShell {
    private const val TAG = "reYAMF_Shell"

    fun install(classLoader: ClassLoader, appInfo: ApplicationInfo?) {
        if (AndroidEnvironment.sdk != 37 || AndroidEnvironment.identity.excluded) return
        val uid = android.os.Process.myUid()
        val appUid = appInfo?.uid ?: -1
        val systemUser = android.os.Process.myUserHandle() == android.os.UserHandle.getUserHandleForUid(0)
        if (!ShellProcessIdentity.acceptsHost(
                appInfo?.packageName, appInfo?.processName, systemUser, uid, appUid,
            )
        ) {
            logWarning(TAG, "Shell host identity rejected: uid=$uid applicationUid=$appUid systemUser=$systemUser")
            return
        }
        runCatching { Android17ShellAdapter.install(classLoader) }
            .onFailure { logWarning(TAG, "Android 17 Shell adapter initialization failed", it) }
        if (!notificationInstalled) installNotification(classLoader)
    }

    /**
     * `onPackageReady` can fire more than once for one package and not every delivery carries a
     * loader that can see the notification classes. Try the offered loader, then the real
     * application loader; a delivery that cannot resolve simply leaves the slot open for the next.
     */
    private fun installNotification(offered: ClassLoader) {
        val applicationLoader = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null)
                ?.javaClass?.classLoader
        }.getOrNull()
        for (target in listOfNotNull(offered, applicationLoader).distinct()) {
            if (runCatching { Class.forName(NOTIFICATION_STARTER, false, target) }.isFailure) continue
            runCatching {
                com.buildsession.betterYAMF.xposed.adapters.android17.notification.NotificationWindowAdapter.install(target)
            }.onSuccess {
                notificationInstalled = true
                log("reYAMF_Notification") { "notification body-click capability installed" }
                return
            }.onFailure {
                logWarning("reYAMF_Notification", "Notification capability unavailable; Shell hooks remain active", it)
            }
        }
    }

    @Volatile private var notificationInstalled = false

    private const val NOTIFICATION_STARTER =
        "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter"
}
