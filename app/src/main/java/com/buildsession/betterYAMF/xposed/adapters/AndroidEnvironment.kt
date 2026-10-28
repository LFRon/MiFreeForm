package com.buildsession.betterYAMF.xposed.adapters

import android.os.Build
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkBaseline
import com.buildsession.betterYAMF.xposed.adapters.baseline.FrameworkShape
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Public platform facts and APK reflection stay outside the JVM ports. */
internal object AndroidEnvironment {
    val sdk: Int get() = Build.VERSION.SDK_INT
    val identity: PlatformIdentity by lazy {
        PlatformIdentity(Build.PRODUCT, Build.FINGERPRINT, Build.DISPLAY)
    }

    /** Axis 3: the reviewed distribution, or null for a ROM nobody has audited yet. */
    val rom: RomProfile? by lazy { RomProfiles.detect(sdk, identity) }

    /** Axis 1: the framework branch the injected process actually implements. */
    fun shape(loader: ClassLoader): FrameworkShape = FrameworkShape.of(loader, rom)

    /** One line per injected process; the shape probe logs itself and is cached. */
    fun reportBaseline(loader: ClassLoader) {
        val profile = rom
        val shape = shape(loader)
        log(TAG) {
            "rom=${profile?.id ?: "unreviewed"} shipped=${profile?.shippedLauncher ?: "unrecorded"} " +
                "sdk=$sdk framework ${shape.summary}"
        }
        if (profile != null && shape.baseline != FrameworkBaseline.UNKNOWN && shape.baseline != profile.baseline)
            logWarning(TAG, "profile ${profile.id} expects ${profile.baseline.name.lowercase()} " +
                "but this build probes ${shape.baseline.name.lowercase()}; the probed shape wins")
    }

    fun launcherVersionCode(info: Any?): Long? = runCatching {
        info ?: return null
        info.javaClass.getField("longVersionCode").getLong(info)
    }.onFailure { logWarning(TAG, "Launcher build identity unavailable", it) }.getOrNull()

    private const val TAG = "reYAMF_Adapters"
}
