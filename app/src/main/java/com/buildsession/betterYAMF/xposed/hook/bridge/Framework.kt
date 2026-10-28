package com.buildsession.betterYAMF.xposed.hook.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import com.buildsession.betterYAMF.xposed.utils.logWarning
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * The only holder of the modern framework interface. Adapters never import libxposed types; they
 * call [NativeHooks] and read [moduleRes] from this package.
 */
internal object Framework {
    private const val TAG = "reYAMF_Adapters"

    @Volatile
    private var entry: XposedModule? = null

    fun bind(module: XposedModule) {
        entry = module
    }

    val api: XposedInterface
        get() = entry ?: error("Framework is not attached yet")

    val attached: Boolean get() = entry != null

    val moduleInfo: ApplicationInfo
        get() = api.moduleApplicationInfo

    val frameworkName: String get() = api.frameworkName
    val frameworkVersion: String get() = api.frameworkVersion
    val apiVersion: Int get() = api.apiVersion
    val frameworkProperties: Long get() = api.frameworkProperties

    fun systemServerSupported(): Boolean = frameworkProperties and XposedInterface.PROP_CAP_SYSTEM != 0L
}

/**
 * Module resources for injected processes. `createPackageContext` needs the module to be installed
 * for the calling user, so the APK path from the framework is the primary route.
 */
internal val moduleRes: Resources by lazy { loadModuleResources() }

private fun loadModuleResources(): Resources {
    val info = Framework.moduleInfo
    runCatching {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
            .invoke(assetManager, info.sourceDir)
        @Suppress("DEPRECATION")
        return Resources(assetManager, null, null)
    }.onFailure {
        logWarning("reYAMF_Adapters", "Module resources unavailable from ${info.sourceDir}", it)
    }
    val application = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
    }.getOrNull() as? android.app.Application
    val context = application?.createPackageContext(
        info.packageName,
        Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE,
    )
    return checkNotNull(context) { "No context to read module resources" }.resources
}
