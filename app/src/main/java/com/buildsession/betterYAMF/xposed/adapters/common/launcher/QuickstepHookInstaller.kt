package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.Service
import android.content.Context
import android.view.MotionEvent
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
    private val registrations = mutableListOf<NativeHooks.Registration>()

    fun install(id: AdapterIdentity, loader: ClassLoader,
        signature: LauncherSourceRetirement.Signature, menu: Boolean, taskbar: Boolean) {
        if (owner === loader && identity == id) return
        check(owner == null) { "An active Launcher binding cannot be replaced in-flight" }
        val native = QuickstepNativeAccess(loader, signature)
        val optional = QuickstepOptionalHooks(loader, menu, taskbar)
        var gesture: LauncherGestureAdapter? = null
        fun adapter(): LauncherGestureAdapter =
            gesture ?: LauncherGestureAdapter(native).also { gesture = it }

        val pending = mutableListOf<NativeHooks.Registration>()
        try {
            // Required task-target filtering is part of this coherent input mechanism.
            pending += ManagedTaskFilter.installTopTaskFilters(native.taskFilter)
            pending += native.onCreate.hookAfter { call ->
                runCatching { start(call.thisObject as Service) }
                    .onFailure { logWarning(TAG, "${id.id}: Launcher service setup failed", it) }
            }
            pending += native.onInput.hookBefore { call ->
                val event = call.args.firstOrNull() as? MotionEvent ?: return@hookBefore
                // Never skip original UP, even when its final end decision has been armed.
                gesture?.takeIf { activeService?.get() != null }?.input(call.thisObject, event)?.let {
                    call.setObjectExtra("miwindow.sourceHandoff", it)
                }
            }
            pending += native.onInput.hookAfter { call ->
                (call.getObjectExtra("miwindow.sourceHandoff") as? RecentsDropHandoff.Handoff)
                    ?.inputFinished(call.throwable)
            }
            pending += native.onDestroy.hookBefore { call ->
                if (activeService?.get() === call.thisObject) {
                    activeService = null
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
                adapter().create(service)
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
