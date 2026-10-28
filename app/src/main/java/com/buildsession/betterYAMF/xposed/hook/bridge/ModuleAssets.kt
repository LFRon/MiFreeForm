package com.buildsession.betterYAMF.xposed.hook.bridge

import android.content.res.AssetManager
import android.content.res.Resources
import com.buildsession.betterYAMF.xposed.utils.logWarning

/**
 * Exposes the module APK to a context wrapper's resource table. The floating window runs inside
 * SystemUI/system_server, where `R.style` and the module drawables only resolve once the module
 * APK is part of that `AssetManager`.
 */
object ModuleAssets {
    @JvmStatic
    fun attach(resources: Resources) {
        runCatching {
            val path = Framework.moduleInfo.sourceDir
            AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
                .invoke(resources.assets, path)
        }.onFailure { logWarning("reYAMF_Adapters", "Cannot attach module assets", it) }
    }
}
