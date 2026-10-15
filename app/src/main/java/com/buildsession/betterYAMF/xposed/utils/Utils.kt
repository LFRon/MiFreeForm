package com.buildsession.betterYAMF.xposed.utils

import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import com.buildsession.betterYAMF.BuildConfig
import de.robv.android.xposed.XposedBridge

// 模块侧（system_server / Launcher 等被注入进程）专用工具。
// UI（App 进程）通用工具见 `common/ui`（dpToPx / componentName / vibratePhone / 动画）。

fun log(tag: String, message: String) {
    // 非警告/错误日志：仅 DEBUG 构建输出，Release 静默（避免 logd/IPC 徒增功耗）
    if (!BuildConfig.DEBUG) return
    XposedBridge.log("[$tag] $message")
}

fun log(tag: String, message: String, t: Throwable) {
    // 错误：Release 保留——走 logcat ERROR 级（轻量，不写 Xposed 日志文件）；DEBUG 完整输出
    if (BuildConfig.DEBUG) {
        XposedBridge.log("[$tag] $message")
        XposedBridge.log(t)
    } else {
        android.util.Log.e(tag, message, t)
    }
}

val emptyContextParams = ContextParams.Builder().build()

fun Context.createContext() = createContext(emptyContextParams)

fun getTopRootTask(displayId: Int): ActivityTaskManager.RootTaskInfo? {
    Instances.activityTaskManager.getAllRootTaskInfosOnDisplay(displayId).forEach { task ->
        if (task.visible)
            return task
    }
    return null
}

fun Context.registerReceiver(action: String, onReceive: BroadcastReceiver.(Context, Intent) -> Unit) {
    registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onReceive(this, context, intent)
        }
    }, android.content.IntentFilter(action), Context.RECEIVER_EXPORTED)
}

fun IPackageManagerHidden.getActivityInfoCompat(className: ComponentName, flags: Int, userId: Int): ActivityInfo =
    getActivityInfo(className, flags.toLong(), userId)
