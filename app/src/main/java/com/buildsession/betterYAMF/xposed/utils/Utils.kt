package com.buildsession.betterYAMF.xposed.utils

import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden

// 模块侧（system_server / Launcher 等被注入进程）专用工具。
// UI（App 进程）通用工具见 `common/ui`（dpToPx / componentName / vibratePhone / 动画）。

val emptyContextParams = ContextParams.Builder().build()

fun Context.createContext() = createContext(emptyContextParams)

fun getTopRootTask(displayId: Int, excludedTaskIds: Set<Int> = emptySet()): ActivityTaskManager.RootTaskInfo? {
    Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
        if (task.visible && task.taskId !in excludedTaskIds)
            return task
    }
    return null
}

// LoS Quickstep requests this privileged permission (quickstep/AndroidManifest.xml:29).
// Module UI commands use its private Binder; only system/shell/recents may drive these broadcasts.
internal const val TRUSTED_SENDER_PERMISSION = "android.permission.START_TASKS_FROM_RECENTS"

fun Context.registerReceiver(action: String, onReceive: BroadcastReceiver.(Context, Intent) -> Unit) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReceive(this, context, intent)
        }
    }
    val filter = android.content.IntentFilter(action)
    if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID)
        com.buildsession.betterYAMF.xposed.adapters.common.system.UserScopedBroadcasts.registerSystemReceiver(this, receiver, filter)
    else registerReceiver(receiver, filter, TRUSTED_SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
}

fun IPackageManagerHidden.getActivityInfoCompat(className: ComponentName, flags: Int, userId: Int): ActivityInfo =
    getActivityInfo(className, flags.toLong(), userId)
