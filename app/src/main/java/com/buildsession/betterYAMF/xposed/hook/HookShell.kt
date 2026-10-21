package com.buildsession.betterYAMF.xposed.hook

import com.buildsession.betterYAMF.xposed.adapters.AndroidEnvironment
import com.buildsession.betterYAMF.xposed.adapters.android17.shell.Android17ShellAdapter
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellProcessIdentity
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
    }
}
