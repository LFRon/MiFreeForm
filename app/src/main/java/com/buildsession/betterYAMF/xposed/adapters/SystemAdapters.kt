package com.buildsession.betterYAMF.xposed.adapters

import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.xposed.adapters.android17.system.Android17SystemAdapter

internal interface AndroidSystemAdapter {
    val sdk: Int
    fun install(classLoader: ClassLoader)
    fun windowBackend(): WindowBackend
}

/** Never borrow the Android 17 organizer for pending SDK 35/36 targets or future releases. */
internal object SystemAdapters {
    fun forSdk(sdk: Int): AndroidSystemAdapter? = when (sdk) {
        37 -> Android17SystemAdapter
        else -> null
    }

    fun select(sdk: Int, platform: PlatformIdentity): AndroidSystemAdapter? =
        if (platform.excluded) null else forSdk(sdk)

    val current: AndroidSystemAdapter? by lazy { select(AndroidEnvironment.sdk, AndroidEnvironment.identity) }
}
