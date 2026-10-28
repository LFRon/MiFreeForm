package com.buildsession.betterYAMF.manager.core.gateway

import android.app.Application
import com.buildsession.betterYAMF.common.logging.AppLog
import com.buildsession.betterYAMF.manager.services.ModuleServiceClient
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActivationDetectionSupport { SUPPORTED }

/**
 * What the framework reports about this module. Scope completeness is the framework's answer, not
 * an inference from service connectivity: an injected process proves activation, the scope list
 * proves coverage.
 */
/** One scope the module needs, with its current state in the framework. */
data class ScopeRequirement(val label: String, val satisfied: Boolean)

data class ModuleActivation(
    val frameworkName: String,
    val frameworkVersion: String,
    val apiVersion: Int,
    val scope: Set<String>,
    val requirements: List<ScopeRequirement>,
    val runningTargets: List<String>,
) {
    val injected: Boolean get() = runningTargets.isNotEmpty()
    val scopeComplete: Boolean get() = requirements.all { it.satisfied }
    val missingScope: List<String> get() = requirements.filterNot { it.satisfied }.map { it.label }
}

/** UI-facing application logic. Framework status and configuration RPCs are separate channels. */
object ModuleInfoGateway {
    private const val TAG = "reYAMF_ModuleInfo"

    /** `system` is the framework's virtual package for system_server, not `android`. */
    private val launcherPackages = listOf(PIXEL_LAUNCHER, LAUNCHER3)

    val activationDetection = ActivationDetectionSupport.SUPPORTED
    private val mutableServiceConnected = MutableStateFlow(false)
    val serviceConnected = mutableServiceConnected.asStateFlow()
    private val mutableActivation = MutableStateFlow<ModuleActivation?>(null)
    val activation = mutableActivation.asStateFlow()

    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        YAMFManagerProxy.addConnectionListener { binder ->
            mutableServiceConnected.value = binder?.isBinderAlive == true
        }
        ModuleServiceClient.initialize(application)
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) = publish(service)

                override fun onServiceDied(service: XposedService) {
                    AppLog.warn(TAG, "Xposed framework service died")
                    mutableActivation.value = null
                }
            })
        }.onFailure { AppLog.warn(TAG, "Xposed service bridge unavailable", it) }
    }

    private fun publish(service: XposedService) {
        runCatching {
            val scope = service.scope.orEmpty().toSet()
            val targets = service.runningTargets.orEmpty().mapNotNull { it.processName }
            ModuleActivation(
                frameworkName = service.frameworkName ?: "libxposed",
                frameworkVersion = service.frameworkVersion ?: "?",
                apiVersion = service.apiVersion,
                scope = scope,
                requirements = requirementsFor(scope),
                runningTargets = targets,
            )
        }.onSuccess {
            AppLog.debug(TAG) {
                "framework=${it.frameworkName} ${it.frameworkVersion} api=${it.apiVersion} " +
                    "scope=${it.scope} targets=${it.runningTargets}"
            }
            mutableActivation.value = it
        }.onFailure { AppLog.warn(TAG, "Cannot read module status", it) }
    }

    /**
     * The scope the framework must hold, in the order the manager lists it. `system` is the virtual
     * package for system_server; the Launcher may be either integrated implementation.
     */
    private fun requirementsFor(scope: Set<String>): List<ScopeRequirement> = listOf(
        ScopeRequirement(SYSTEM_SERVER, SYSTEM_SERVER in scope),
        ScopeRequirement(SYSTEM_UI, SYSTEM_UI in scope),
        ScopeRequirement(
            "$PIXEL_LAUNCHER / $LAUNCHER3",
            launcherPackages.any(scope::contains),
        ),
    )

    /** Foreground reconnect for functional RPCs only. No timer, peer probes or readiness query. */
    fun requestServiceConnection() {
        if (!YAMFManagerProxy.isConnected) ModuleServiceClient.requestConnection()
    }

    val versionName: String? get() = YAMFManagerProxy.versionName
    val versionCode: Int get() = YAMFManagerProxy.versionCode
    val buildTime: Long get() = YAMFManagerProxy.buildTime

    private const val SYSTEM_SERVER = "system"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val PIXEL_LAUNCHER = "com.google.android.apps.nexuslauncher"
    private const val LAUNCHER3 = "com.android.launcher3"
}
