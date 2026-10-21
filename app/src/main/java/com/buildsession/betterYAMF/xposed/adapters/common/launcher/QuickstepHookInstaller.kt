package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.Service
import android.content.Context
import android.view.MotionEvent
import com.buildsession.betterYAMF.window.api.PointerPhase
import com.buildsession.betterYAMF.xposed.adapters.AdapterIdentity
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.utils.logWarning
import java.lang.ref.WeakReference

/** Shared registration mechanics, called by concrete Android + Launcher installers. */
internal object QuickstepHookInstaller {
    private var owner: ClassLoader? = null
    private var identity: AdapterIdentity? = null
    private var startService: ((Service) -> Unit)? = null
    private var activeService: WeakReference<Service>? = null
    private var environment: LauncherEnvironmentMonitor? = null
    val isCurrentLauncher: Boolean get() = activeService?.get() != null && environment?.isCurrentLauncher == true
    private val registrations = mutableListOf<NativeHooks.Registration>()
    private class InputCall(val adapter: LauncherGestureAdapter, val input: LauncherGestureAdapter.PreparedInput,
        var handoff: RecentsDropHandoff.Handoff? = null)

    fun install(id: AdapterIdentity, loader: ClassLoader,
        signature: LauncherSourceRetirement.Signature, menu: Boolean, taskbar: Boolean,
        nativeDownAdmission: Boolean = false) {
        if (owner === loader && identity == id) return
        check(owner == null) { "An active Launcher binding cannot be replaced in-flight" }
        val native = QuickstepNativeAccess(loader, signature)
        val optional = QuickstepOptionalHooks(loader, menu, taskbar) { isCurrentLauncher }
        var gesture: LauncherGestureAdapter? = null
        fun adapter(): LauncherGestureAdapter =
            gesture ?: LauncherGestureAdapter(native, nativeDownAdmission).also { gesture = it }

        val pending = mutableListOf<NativeHooks.Registration>()
        try {
            // Required task-target filtering is part of this coherent input mechanism.
            pending += ManagedTaskFilter.installTopTaskFilters(native.taskFilter)
            pending += native.onCreate.hookAfter { call ->
                runCatching { start(call.thisObject as Service) }
                    .onFailure { logWarning(TAG, "${id.id}: Launcher service setup failed", it) }
            }
            pending += NativeHooks.hookMethod(native.onInput, object : NativeHooks.Callback() {
                override fun beforeHookedMethod(call: NativeHooks.Call) {
                    val event = call.args.firstOrNull() as? MotionEvent ?: return
                    val current = gesture?.takeIf {
                        activeService?.get() != null && it.acceptsInput(call.thisObject)
                    } ?: return
                    val input = runCatching { current.prepareInput(call.thisObject, event) }
                        .onFailure { current.inputFailed(call.thisObject, it) }.getOrNull() ?: return
                    val invocation = InputCall(current, input)
                    call.setObjectExtra("miwindow.entryInput", invocation)
                    // Never skip original UP. MOVE recognition is observed after the original.
                    if (input.value.phase != PointerPhase.MOVE &&
                        (input.value.phase != PointerPhase.DOWN || !nativeDownAdmission))
                        invocation.handoff = current.input(input)
                }
                override fun afterHookedMethod(call: NativeHooks.Call) {
                    val invocation = call.getObjectExtra("miwindow.entryInput") as? InputCall ?: return
                    runCatching { invocation.adapter.inputFinished(invocation.input, invocation.handoff, call.throwable) }
                        .onFailure { invocation.adapter.inputFailed(invocation.input, it) }
                }
            })
            pending += native.onDestroy.hookBefore { call ->
                if (activeService?.get() === call.thisObject) {
                    activeService = null
                    environment?.close()
                    environment = null
                    runCatching { gesture?.destroy() }.onFailure { error -> logWarning(TAG, "Launcher cleanup failed", error) }
                }
            }
            pending += native.launcherOnCreate.hookAfter { call ->
                val context = call.thisObject as Context
                runCatching { optional.configure(context.applicationContext) }
                    .onFailure { logWarning(TAG, "Optional Launcher entry configuration failed", it) }
            }
            registrations += pending
            owner = loader
            identity = id
            startService = { service ->
                environment?.close()
                environment = null
                gesture?.destroy()
                try {
                    val current = adapter()
                    val monitor = LauncherEnvironmentMonitor(service, id, current::setEnvironmentCurrent)
                    environment = monitor
                    monitor.start()
                    current.create(service, native.inputOwnerOf(service))
                } catch (error: Throwable) {
                    environment?.close()
                    environment = null
                    gesture?.destroy()
                    throw error
                }
                runCatching { optional.configure(service.applicationContext) }
                    .onFailure { logWarning(TAG, "Optional Launcher entry configuration failed", it) }
            }
        } catch (error: Throwable) {
            pending.asReversed().forEach { registration ->
                runCatching { registration.unhook() }.onFailure { logWarning(TAG, "Partial Launcher hook rollback failed", it) }
            }
            throw error
        }
    }

    /** Start each native service owner once, without duplicating overlays or receivers. */
    private fun start(service: Service) {
        if (activeService?.get() === service) return
        val initialize = checkNotNull(startService) { "Native binding has not been installed" }
        activeService = WeakReference(service)
        try { initialize(service) } catch (error: Throwable) { activeService = null; throw error }
    }

    private const val TAG = "reYAMF_Adapters"
}
