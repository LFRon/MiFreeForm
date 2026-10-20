package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import de.robv.android.xposed.XposedHelpers

/** Optional menu/taskbar hooks cannot abort the gesture installer. This shared implementation is
 * the LoS Launcher3 ABI; the Pixel unit deliberately does not select it.
 */
internal class QuickstepOptionalHooks(private val loader: ClassLoader,
    private val allowMenu: Boolean, private val allowTaskbar: Boolean) {
    private var requested = false
    private var menuInstalled = false
    private var taskbarInstalled = false

    fun configure(context: Context) {
        if (requested || !allowMenu && !allowTaskbar) return
        requested = true
        try {
            context.registerReceiver(Protocol.ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
                if (allowMenu && intent.getBooleanExtra(Protocol.EXTRA_HOOK_RECENT, false) && !menuInstalled)
                    runCatching { installMenu(); menuInstalled = true }
                        .onFailure { logWarning(TAG, "Recents menu entry unavailable", it) }
                if (allowTaskbar && intent.getBooleanExtra(Protocol.EXTRA_HOOK_TASKBAR, false) && !taskbarInstalled)
                    runCatching { installTaskbar(); taskbarInstalled = true }
                        .onFailure { logWarning(TAG, "Taskbar entry unavailable", it) }
                context.unregisterReceiver(this)
            }
            context.sendBroadcast(Intent(Protocol.ACTION_GET_LAUNCHER_CONFIG).setPackage("android")
                .putExtra("sender", context.packageName))
        } catch (error: Throwable) {
            requested = false
            throw error
        }
    }

    private fun installMenu() {
        val factory = loader.loadClass("com.android.quickstep.TaskOverlayFactory")
        val shortcutClass = loader.loadClass("com.android.launcher3.popup.RemoteActionShortcut")
        val constructors = shortcutClass.constructors.filter { constructor ->
            constructor.parameterCount in 3..4 && constructor.parameterTypes[0] == RemoteAction::class.java
        }
        check(constructors.isNotEmpty()) { "No reviewed RemoteActionShortcut constructor" }
        val methods = factory.declaredMethods.filter { it.name == "getEnabledShortcuts" &&
            it.parameterCount >= 1 && View::class.java.isAssignableFrom(it.parameterTypes[0]) &&
            List::class.java.isAssignableFrom(it.returnType) }
        check(methods.isNotEmpty()) { "No recents menu producer" }
        val hooks = mutableListOf<NativeHooks.Registration>()
        try {
            methods.forEach { method -> hooks += method.hookAfter { call ->
                runCatching {
                    val view = call.args[0] as View
                    val shortcuts = call.result as? List<*> ?: return@runCatching
                    val first = shortcuts.firstOrNull() ?: return@runCatching
                    val item = XposedHelpers.getObjectField(first, "mItemInfo") ?: return@runCatching
                    val task = runCatching { XposedHelpers.callMethod(view, "getTask") }.getOrNull()
                        ?: run {
                            val containers = XposedHelpers.getObjectField(view, "taskContainers") as? List<*>
                            containers?.firstOrNull()?.let { XposedHelpers.getObjectField(it, "task") }
                        } ?: return@runCatching
                    val key = XposedHelpers.getObjectField(task, "key")
                    val id = XposedHelpers.getIntField(key, "id")
                    val user = XposedHelpers.getIntField(key, "userId")
                    if (id <= 0) return@runCatching
                    val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).setPackage("android")
                        .putExtra(Protocol.EXTRA_TASK_ID, id).putExtra(Protocol.EXTRA_USER_ID, user)
                        .putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_RECENT)
                    val action = RemoteAction(Icon.createWithBitmap(
                        moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null).toBitmap()),
                        moduleRes.getString(R.string.open_with_yamf), "",
                        PendingIntent.getBroadcast(view.context.applicationContext, id, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    val constructor = constructors.firstOrNull {
                        it.parameterTypes[1].isInstance(view.context) && it.parameterTypes[2].isInstance(item)
                    } ?: return@runCatching
                    val shortcut = if (constructor.parameterCount == 4)
                        constructor.newInstance(action, view.context, item, null)
                    else constructor.newInstance(action, view.context, item)
                    call.result = ArrayList<Any?>(shortcuts).apply { add(shortcut) }
                }.onFailure { logWarning(TAG, "Recents menu callback failed; preserve original shortcuts", it) }
            } }
        } catch (error: Throwable) {
            hooks.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
    }

    private fun installTaskbar() {
        val type = loader.loadClass("com.android.launcher3.taskbar.TaskbarActivityContext")
        val itemType = loader.loadClass("com.android.launcher3.model.data.ItemInfo")
        val workspaceType = loader.loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
        // LoS 24.0: ItemInfo is argument 0; Pixel 17/907 swaps Task and ItemInfo. That different
        // ABI is not selected here. Skip only after a valid request was actually dispatched.
        val start = type.declaredMethods.single { it.name == "startItemInfoActivity" &&
            it.parameterCount == 2 && it.parameterTypes[0] == itemType }
        start.hookBefore { call ->
            runCatching {
                val item = call.args.firstOrNull()?.takeIf(workspaceType::isInstance) ?: return@runCatching
                val component = (XposedHelpers.callMethod(item, "getIntent") as? Intent)?.component ?: return@runCatching
                val context = call.thisObject as Context
                val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).setPackage("android")
                    .putExtra(Protocol.EXTRA_COMPONENT_NAME, component)
                    .putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_TASKBAR)
                val user = XposedHelpers.getObjectField(item, "user") as? UserHandle
                if (user != null) intent.putExtra(Protocol.EXTRA_USER_ID,
                    XposedHelpers.callMethod(user, "getIdentifier") as Int)
                context.sendBroadcast(intent)
                call.result = null
            }.onFailure { logWarning(TAG, "Taskbar entry failed; preserve native launch", it) }
        }
    }

    companion object { private const val TAG = "reYAMF_LauncherEntries" }
}
