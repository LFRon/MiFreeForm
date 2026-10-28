package com.buildsession.betterYAMF.xposed.adapters.android17.pixelLauncher

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.QuickstepHookInstaller
import com.buildsession.betterYAMF.xposed.adapters.common.launcher.RecentsDropHandoff
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * Pixel Launcher 17/907 overview menu. Its SystemShortcut does not have a public
 * RemoteActionShortcut constructor: the APK invokes SystemShortcut.<init> directly while
 * allocating the final subclass. Allocate that subclass and initialize the inherited fields;
 * its final overrides only consume mAction, mTarget and mItemInfo.
 */
internal object PixelTaskMenuHook {
    private const val TAG = "reYAMF_LauncherEntries"
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        val factory = loader.loadClass("com.android.quickstep.TaskOverlayFactory")
        val methods = factory.declaredMethods.filter {
            it.name == "getEnabledShortcuts" && it.parameterCount >= 2 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                List::class.java.isAssignableFrom(it.returnType)
        }
        check(methods.isNotEmpty()) { "No Pixel task menu producer" }
        methods.forEach { method ->
            method.hookAfter { call ->
                if (!QuickstepHookInstaller.isCurrentLauncher ||
                    !RecentsDropHandoff.backendAvailable()) return@hookAfter
                runCatching {
                    val view = call.args[0] as? View ?: return@runCatching
                    val shortcuts = call.result as? List<*> ?: return@runCatching
                    val shortcut = buildShortcut(loader, view, call.args[1]) ?: return@runCatching
                    call.result = ArrayList(shortcuts).apply { add(shortcut) }
                }.onFailure { logWarning(TAG, "Pixel task menu entry failed", it) }
            }
        }
        installed = true
        log(TAG) { "task menu entry installed producers=${methods.size}" }
    }

    private fun buildShortcut(loader: ClassLoader, view: View, container: Any): Any? {
        val task = runCatching { XposedHelpers.callMethod(container, "getTask") }.getOrNull() ?: return null
        val key = runCatching { XposedHelpers.getObjectField(task, "key") }.getOrNull() ?: return null
        val taskId = runCatching { XposedHelpers.getIntField(key, "id") }.getOrDefault(-1)
        val userId = runCatching { XposedHelpers.getIntField(key, "userId") }.getOrDefault(0)
        if (taskId <= 0) return null
        val itemInfo = runCatching { XposedHelpers.callMethod(container, "getItemInfo") }.getOrNull() ?: return null
        val activityContext = runCatching {
            val type = loader.loadClass("com.android.quickstep.views.RecentsViewContainer")
            type.getMethod("containerFromContext", Context::class.java).invoke(null, view.context)
        }.getOrNull() ?: return null
        val shortcutClass = loader.loadClass("com.android.launcher3.popup.RemoteActionShortcut")
        val shortcut = allocate(shortcutClass)
        val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).setPackage("android")
            .putExtra(Protocol.EXTRA_TASK_ID, taskId)
            .putExtra(Protocol.EXTRA_USER_ID, userId)
            .putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_RECENT)
        val action = RemoteAction(
            Icon.createWithBitmap(moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null).toBitmap()),
            moduleRes.getString(R.string.open_with_yamf), "",
            PendingIntent.getBroadcast(view.context.applicationContext, taskId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        )
        set(shortcut, "mAction", action)
        set(shortcut, "mTarget", activityContext)
        set(shortcut, "mItemInfo", itemInfo)
        set(shortcut, "mOriginalView", view)
        set(shortcut, "mIsCollapsible", true)
        set(shortcut, "mAbstractFloatingViewHelper",
            runCatching {
                loader.loadClass("com.android.launcher3.popup.SystemShortcut\$AbstractFloatingViewHelper")
                    .getField("INSTANCE").get(null)
            }.getOrNull())
        return shortcut
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    }

    private fun set(target: Any, name: String, value: Any?) {
        val field: Field = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull() ?: return
        field.isAccessible = true
        when (value) {
            is Boolean -> field.setBoolean(target, value)
            is Int -> field.setInt(target, value)
            else -> field.set(target, value)
        }
    }
}
