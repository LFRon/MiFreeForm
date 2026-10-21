package com.buildsession.betterYAMF.xposed.adapters.android17.system

import android.os.IBinder
import com.buildsession.betterYAMF.xposed.adapters.common.organizer.SmoothTaskOrganizer
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellOwnershipServer
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.utils.log
import java.util.Collections
import java.util.WeakHashMap

/** PixelOS A17 WOC.applyTransaction(WCT,int,ActionChain,CallerInfo), immediately before native
 * sanitization, under WM's global lock. Protects against a Shell coroutine which captured an
 * orphan-freeform snapshot BEFORE adoption and only submits its WCT AFTER adoption.
 *
 * CallerInfo is captured before Binder.clearCallingIdentity and survives deferred execution.
 * Mark its identity while the authenticated Shell peer is alive; a deferred old caller remains
 * recognizable after peer replacement, without trusting a PID forever across process reuse.
 */
internal class ShellTaskTransactionGuard(loader: ClassLoader) {
    private val caller = loader.loadClass("com.android.server.wm.WindowOrganizerController\$CallerInfo")
    private val callerConstructor = caller.getDeclaredConstructor().apply { isAccessible = true }
    private val pid = caller.getDeclaredField("mPid").apply { isAccessible = true }
    private val uid = caller.getDeclaredField("mUid").apply { isAccessible = true }
    private val wct = loader.loadClass("android.window.WindowContainerTransaction")
    private val apply = loader.loadClass("com.android.server.wm.WindowOrganizerController")
        .getDeclaredMethod("applyTransaction", wct, Int::class.javaPrimitiveType,
            loader.loadClass("com.android.server.wm.ActionChain"), caller).apply { isAccessible = true }
    private val changes = wct.getMethod("getChanges")
    private val hops = wct.getMethod("getHierarchyOps")
    private val hop = loader.loadClass("android.window.WindowContainerTransaction\$HierarchyOp")
    private val container = hop.getMethod("getContainer")
    private val newParent = hop.getMethod("getNewParent")
    private val hopType = hop.getMethod("getType")
    private val changeMode = loader.loadClass("android.window.WindowContainerTransaction\$Change").getMethod("getWindowingMode")
    private val boundsTransaction = loader.loadClass("android.window.WindowContainerTransaction\$Change")
        .getMethod("getBoundsChangeTransaction")
    private val windowContainer = loader.loadClass("com.android.server.wm.WindowContainer")
    private val fromBinder = windowContainer.getDeclaredMethod("fromBinder", IBinder::class.java).apply { isAccessible = true }
    private val asTask = windowContainer.getDeclaredMethod("asTask").apply { isAccessible = true }
    private val asDisplayArea = windowContainer.getDeclaredMethod("asTaskDisplayArea").apply { isAccessible = true }
    private val windowingMode = windowContainer.getMethod("getWindowingMode").apply { isAccessible = true }
    private val launchCookie = loader.loadClass("com.android.server.wm.Task").getDeclaredField("mLaunchCookie").apply { isAccessible = true }
    private val shellCallers = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    fun install(): List<NativeHooks.Registration> {
        val hooks = mutableListOf<NativeHooks.Registration>()
        try {
            hooks += callerConstructor.hookAfter { call ->
                if (ShellOwnershipServer.matchesCaller(uid.getInt(call.thisObject), pid.getInt(call.thisObject)))
                    shellCallers[call.thisObject] = true
            }
            hooks += apply.hookBefore { call ->
                if (shellCallers[call.args[3]] != true) return@hookBefore
                runCatching {
                    // This is Shell's unmarshalled server-side WCT, already owned/sanitized by
                    // WOC. Keep all unrelated operations and execute the original even if empty.
                    excludeNativeWrites(call.args[0], ::owns, ::isNonFreeformParent, ::isInternalRoot)
                }.onFailure { ShellOwnershipServer.invalidate("Native task transaction isolation failed", it) }
            }
            return hooks
        } catch (error: Throwable) {
            hooks.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    /** Takes only WOC's private incoming copy. Removed bounds transactions belong to this copy;
     * release them without applying. Unrelated Changes and hierarchy operations retain identity.
     */
    internal fun excludeNativeWrites(transaction: Any, owned: (IBinder?) -> Boolean,
        canReparentOut: (IBinder?) -> Boolean = { false }, internalRoot: (IBinder?) -> Boolean = { false }) {
        @Suppress("UNCHECKED_CAST") val config = changes.invoke(transaction) as MutableMap<IBinder, Any>
        @Suppress("UNCHECKED_CAST") val hierarchy = hops.invoke(transaction) as MutableList<Any>
        // Honor real ownership handbacks (split/fullscreen reparent, PiP request, task close).
        // Blocking every SystemUI operation would also block those legitimate lifecycles.
        val exiting = config.filter { (token, change) ->
            owned(token) && !internalRoot(token) && changeMode.invoke(change) == MODE_PINNED
        }.keys.toMutableSet()
        for (op in hierarchy) {
            val type = hopType.invoke(op) as Int
            if (type == FINISH_ACTIVITY || type == TASK_FRAGMENT_OPERATION) continue
            val token = container.invoke(op) as? IBinder ?: continue
            if (!owned(token) || internalRoot(token)) continue
            when (type) {
                REMOVE_TASK -> exiting.add(token)
                REPARENT -> {
                    val parent = newParent.invoke(op) as? IBinder
                    if (!owned(parent) && canReparentOut(parent)) exiting.add(token)
                }
            }
        }
        val excludedConfig = config.keys.filter { owned(it) && it !in exiting }
        val excludedHierarchy = hierarchy.filter {
            // PixelOS A17 WOC.sanitizeTransaction has the same exclusions: these
            // operands are activity/client-fragment tokens, not WindowContainer tokens.
            // WOC resolves and validates them via ActivityRecord / TaskFragment later.
            val type = hopType.invoke(it) as Int
            if (type == FINISH_ACTIVITY || type == TASK_FRAGMENT_OPERATION) return@filter false
            val token = container.invoke(it) as? IBinder
            owned(token) && token !in exiting || owned(newParent.invoke(it) as? IBinder)
        }.toSet()
        for (token in excludedConfig) {
            val change = checkNotNull(config.remove(token))
            (boundsTransaction.invoke(change) as? android.view.SurfaceControl.Transaction)?.close()
        }
        hierarchy.removeAll(excludedHierarchy)
        if (excludedConfig.isNotEmpty() || excludedHierarchy.isNotEmpty())
            log("reYAMF_Shell") { "excluded native writes to owned tasks: config=${excludedConfig.size} hierarchy=${excludedHierarchy.size}" }
    }

    private fun owns(token: IBinder?): Boolean {
        if (token == null) return false
        val native = fromBinder.invoke(null, token) ?: return false
        return SmoothTaskOrganizer.ownsNativeTask(asTask.invoke(native))
    }

    private fun isInternalRoot(token: IBinder?): Boolean {
        if (token == null) return false
        val native = fromBinder.invoke(null, token)?.let { asTask.invoke(it) } ?: return false
        return launchCookie.get(native) == ShellOwnershipServer.cookie
    }

    private fun isNonFreeformParent(token: IBinder?): Boolean {
        if (token == null) return true // default TaskDisplayArea
        val native = fromBinder.invoke(null, token) ?: return false
        val task = asTask.invoke(native)
        return if (task != null) windowingMode.invoke(task) != MODE_FREEFORM else asDisplayArea.invoke(native) != null
    }

    private companion object {
        const val MODE_PINNED = 2
        const val MODE_FREEFORM = 5
        const val REPARENT = 0
        const val REMOVE_TASK = 13
        const val FINISH_ACTIVITY = 14
        const val TASK_FRAGMENT_OPERATION = 17
    }
}
