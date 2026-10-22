package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.annotation.SuppressLint
import android.content.Intent
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformPolicy
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.qauxv.util.Initiator

/** Shared early-boot service plumbing, selected only after the system adapter's native preflight. */
internal object SystemHookInstaller {
    private const val TAG = "reYAMF_HookSystem"

    // Invoked only in system_server; app hidden-API target-SDK restrictions do not apply here.
    @SuppressLint("SoonBlockedPrivateApi")
    fun install(classLoader: ClassLoader, policy: FreeformPolicy) {
        val ready = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            .declaredMethods.single { it.name == "systemReady" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Runnable::class.java }
        val registrations = mutableListOf<NativeHooks.Registration>()
        try {
            var readyHook: NativeHooks.Registration? = null
            readyHook = ready.hookAfter { call ->
                readyHook?.unhook()
                YAMFManager.activityManagerService = call.thisObject
                YAMFManager.systemReady()
                log(TAG) { "System ready, reYAMF services initialized." }
            }
            registrations += checkNotNull(readyHook)
            Initiator.init(classLoader)
            FreeformBackend.init(classLoader, policy)
        } catch (error: Throwable) {
            registrations.asReversed().forEach { runCatching { it.unhook() }
                .onFailure { logWarning(TAG, "System hook rollback failed", it) } }
            throw error
        }
        // This optional diagnostic exemption cannot prevent service initialization.
        runCatching {
            classLoader.loadClass("com.android.server.am.BroadcastController").declaredMethods
                .single { it.name == "checkBroadcastFromSystem" }.hookBefore { call ->
                    val intent = call.args[0] as Intent
                    if (intent.action == Protocol.ACTION_RECEIVE_LAUNCHER_CONFIG) call.result = null
                }
        }.onFailure { logWarning(TAG, "Launcher config broadcast exemption unavailable", it) }
    }
}
