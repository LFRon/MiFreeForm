package com.buildsession.betterYAMF.xposed.adapters

import android.os.Build
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Public platform facts and APK reflection stay outside the JVM ports. */
internal object AndroidEnvironment {
    val sdk: Int get() = Build.VERSION.SDK_INT
    val identity: PlatformIdentity by lazy {
        PlatformIdentity(Build.PRODUCT, Build.FINGERPRINT, Build.DISPLAY)
    }

    fun launcherVersionCode(info: Any?): Long? = runCatching {
        info ?: return null
        info.javaClass.getField("longVersionCode").getLong(info)
    }.onFailure { logWarning("reYAMF_Adapters", "Launcher build identity unavailable", it) }.getOrNull()
}
