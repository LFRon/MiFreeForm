package com.buildsession.betterYAMF.manager.core.gateway

import android.app.Application
import com.buildsession.betterYAMF.manager.services.ModuleServiceClient
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActivationDetectionSupport { UNSUPPORTED }

/** UI-facing application logic. Service connectivity is only for configuration/window RPCs;
 * it must not be interpreted as module activation or complete Hook scope coverage.
 */
object ModuleInfoGateway {
    val activationDetection = ActivationDetectionSupport.UNSUPPORTED
    private val mutableServiceConnected = MutableStateFlow(false)
    val serviceConnected = mutableServiceConnected.asStateFlow()
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        YAMFManagerProxy.addConnectionListener { binder ->
            mutableServiceConnected.value = binder?.isBinderAlive == true
        }
        ModuleServiceClient.initialize(application)
    }

    /** Foreground reconnect for functional RPCs only. No timer, peer probes or readiness query. */
    fun requestServiceConnection() {
        if (!YAMFManagerProxy.isConnected) ModuleServiceClient.requestConnection()
    }

    val versionName: String? get() = YAMFManagerProxy.versionName
    val versionCode: Int get() = YAMFManagerProxy.versionCode
    val buildTime: Long get() = YAMFManagerProxy.buildTime
}
