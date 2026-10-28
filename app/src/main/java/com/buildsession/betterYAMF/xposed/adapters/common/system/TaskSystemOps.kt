package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import com.buildsession.betterYAMF.xposed.utils.argTypes
import com.buildsession.betterYAMF.xposed.utils.args
import com.buildsession.betterYAMF.xposed.utils.getObject
import com.buildsession.betterYAMF.xposed.utils.invokeMethod
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.Reflect

/**
 * 系统操作实现（反射细节收口点）：
 * 窗口模式查询 / Home 判定 / 任务快照刷新 / HOME 键注入。
 *
 * 分层边界：本类属实现层；流程（YAMFManager）与后端（SmoothWindowBackend）只经
 * `WindowBackend` 端口调用，不直接持有这些反射细节。
 *
 * 关键源码事实（LineageOS 24.0）：
 * - `WindowManagerService.java:787-788`：`mTaskSnapshotController` / `mSnapshotController` 字段并存；
 *   `:1405`：`SnapshotController.mTaskSnapshotController`；
 * - `TaskSnapshotController.java:122`：`snapshotTasks(ArraySet<Task>)`（包私有，反射调用）；
 * - `WindowConfiguration.java:438`：`getActivityType()`（`ACTIVITY_TYPE_HOME = 2`）。
 */
object TaskSystemOps {
    private const val TAG = "reYAMF_TaskOps"

    /** 任务当前窗口模式（解析失败返回 0）。 */
    fun windowingMode(taskInfo: Any): Int = runCatching {
        val windowConfig = Reflect.getObjectField(
            Reflect.getObjectField(taskInfo, "configuration"),
            "windowConfiguration"
        )
        Reflect.callMethod(windowConfig, "getWindowingMode") as Int
    }.getOrDefault(0)

    /** 任务是否 Home（主路径 = windowConfiguration.getActivityType；兜底 = 包名启发式）。 */
    fun isHomeTask(taskInfo: android.app.TaskInfo): Boolean {
        val activityType = runCatching {
            val config = taskInfo.getObject("configuration")
            val windowConfig = config.getObject("windowConfiguration")
            windowConfig.invokeMethod("getActivityType") as Int
        }.getOrElse {
            val topActivity = taskInfo.topActivity
            if (topActivity != null) {
                val pkg = topActivity.packageName
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolveInfo =
                    Instances.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                val defaultLauncher = resolveInfo?.activityInfo?.packageName

                if (pkg == defaultLauncher ||
                    pkg == "com.android.launcher3" ||
                    pkg == "com.google.android.apps.nexuslauncher" ||
                    (pkg.contains("launcher", ignoreCase = true) && !pkg.contains("service", ignoreCase = true))
                ) 2 else 0
            } else 0
        }
        return activityType == 2
    }

    /**
     * 关闭前主动刷新任务快照（必须在应用仍完整可见时调用）。
     * 背景：关闭流程会先把任务表面收缩/隐藏，再沉入后台；系统在"后台化"时刻自动抓的
     * 快照恰好是空帧 → 最近任务卡片"白屏"。提前用当前完整画面刷新快照即可避免。
     */
    fun refreshTaskSnapshot(taskId: Int) {
        runCatching {
            val atm = Instances.activityTaskManager
            val wm = Reflect.getObjectField(atm, "mWindowManager") ?: return
            val controller = runCatching {
                val sc = Reflect.getObjectField(wm, "mSnapshotController")
                Reflect.getObjectField(sc, "mTaskSnapshotController")
            }.getOrNull()
                ?: runCatching { Reflect.getObjectField(wm, "mTaskSnapshotController") }.getOrNull()
                ?: return
            val runningTasks = Reflect.callMethod(
                atm, "getTasks", Int.MAX_VALUE, false, false, -1
            ) as? List<*> ?: return
            val info = runningTasks.find { Reflect.getIntField(it, "taskId") == taskId } ?: return
            val token = Reflect.getObjectField(info, "token")
            val binder = Reflect.callMethod(token, "asBinder") as? android.os.IBinder ?: return
            val wcClass = Instances.systemContext.classLoader
                .loadClass("com.android.server.wm.WindowContainer")
            val wc = Reflect.callStaticMethod(wcClass, "fromBinder", binder) ?: return
            val task = Reflect.callMethod(wc, "asTask") ?: return
            val set = android.util.ArraySet<Any>()
            set.add(task)
            Reflect.callMethod(controller, "snapshotTasks", set)
            log("reYAMF_debug") { "refreshTaskSnapshot task=$taskId ok" }
        }.onFailure {
            log("reYAMF_debug", "refreshTaskSnapshot failed task=$taskId", it)
        }
    }

    /** 注入 HOME（用于"从最近任务打开即小窗"后关闭最近任务界面）。 */
    fun injectHomeKey(): Boolean {
        return runCatching {
            val down = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_HOME,
                0
            ).apply {
                this.source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
            }
            val up = KeyEvent(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_HOME,
                0
            ).apply {
                this.source = InputDevice.SOURCE_KEYBOARD
                this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
            }
            val r1 = Instances.inputManager.injectInputEvent(down, 0)
            val r2 = Instances.inputManager.injectInputEvent(up, 0)
            log("reYAMF_debug") { "injectHome done down=$r1 up=$r2" }
            true
        }.onFailure {
            log("reYAMF_debug", "injectHome failed", it)
        }.getOrDefault(false)
    }
}
