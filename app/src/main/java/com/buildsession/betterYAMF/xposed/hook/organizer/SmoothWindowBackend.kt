package com.buildsession.betterYAMF.xposed.hook.organizer

import android.graphics.Rect
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.xposed.hook.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import de.robv.android.xposed.XposedHelpers

/**
 * `WindowBackend` 的实现：TaskOrganizer 悬浮根 + leash 承载（唯一实现）。
 *
 * 本类也是系统侧细节的收口点：
 * - 认领/释放 = `SmoothTaskOrganizer`（organizer/leash/WCT）；
 * - 几何 = leash 变换（被接管）或旧变换重断言（未接管）；
 * - 焦点 = `Task.setFocusable` + `adjustFocusToNextFocusableTask`（浮窗语义）；
 * - 未接管的平滑任务走旧 WCT 释放兜底。
 */
object SmoothWindowBackend : WindowBackend {
    private const val TAG = "reYAMF_Backend"
    private const val WINDOWING_MODE_FULLSCREEN = 1

    @Volatile
    private var callbacks: WindowBackendCallbacks? = null

    override fun initialize(callbacks: WindowBackendCallbacks) {
        // 先绑回调再初始化：初始化过程中可能触发任务事件
        this.callbacks = callbacks
        SmoothTaskOrganizer.init()
    }

    override fun openTask(taskId: Int): Boolean {
        val adopted = SmoothTaskOrganizer.adoptTask(taskId)
        val moveResult = runCatching { Instances.activityManager.moveTaskToFront(taskId, 0) }
        log(TAG, "openTask task=$taskId adopted=$adopted moveToFront=$moveResult")
        // 用户主动开窗：恢复可聚焦（可交互）
        setTaskFocusable(taskId, true)
        return adopted
    }

    override fun releaseTask(taskId: Int, toFront: Boolean): Boolean {
        // 关键：先把任务恢复为可聚焦，再发释放事务。释放事务内的 lifecycle 复算
        // （ensureActivitiesVisible + resumeFocusedTasksTopActivities）需要任务可聚焦，
        // 才会把回全屏后的任务判为可见/可恢复；否则焦点仍留在（已不可见的）launcher 上，
        // 实测 launcher 会随后把 home 置前（以 QuickstepLaunchHome 播放）→"应用看着退出了"。
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            setTaskFocusable(taskId, true)
            if (SmoothTaskOrganizer.releaseTask(taskId, toFront)) {
                return true
            }
        }

        // 旧路径兜底（未被 leash 接管的平滑任务）：清表面变换 + WCT 回全屏
        return runCatching {
            val runningTasks = XposedHelpers.callMethod(
                Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
            ) as? List<*> ?: return false
            val taskInfo = runningTasks.find {
                XposedHelpers.getIntField(it, "taskId") == taskId
            } ?: return false

            // 清掉我们曾施加的表面变换/裁剪/圆角（用缓存的 Task 对象；传 RunningTaskInfo 会静默失败）
            FreeformBackend.releaseTaskTransforms(taskId)
            // 复位浮窗焦点开关
            setTaskFocusable(taskId, true)

            // 已是全屏的任务（如全屏按钮路径的二次调用）不再重发事务
            if (YAMFManager.taskWindowingMode(taskInfo) == WINDOWING_MODE_FULLSCREEN) {
                return true
            }

            val classLoader = Instances.systemContext.classLoader
            val wctClass = classLoader.loadClass("android.window.WindowContainerTransaction")
            val wct = XposedHelpers.newInstance(wctClass)
            val token = XposedHelpers.getObjectField(taskInfo, "token")
            XposedHelpers.callMethod(wct, "setWindowingMode", token, WINDOWING_MODE_FULLSCREEN)

            val windowOrganizerController =
                XposedHelpers.callMethod(Instances.activityTaskManager, "getWindowOrganizerController")
            XposedHelpers.callMethod(windowOrganizerController, "applyTransaction", wct)
            true
        }.onFailure {
            log(TAG, "releaseTask failed task=$taskId", it)
        }.getOrDefault(false)
    }

    override fun applyGeometry(taskId: Int, physicalBounds: Rect) {
        YAMFManager.smoothFreeformBounds[taskId] = physicalBounds
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            SmoothTaskOrganizer.applyLeash(taskId, physicalBounds)
        } else {
            FreeformBackend.reassertTaskById(taskId)
        }
    }

    override fun reapplyGeometry(taskId: Int) {
        val bounds = YAMFManager.smoothFreeformBounds[taskId] ?: return
        applyGeometry(taskId, bounds)
    }

    override fun ensureTaskVisible(taskId: Int) {
        SmoothTaskOrganizer.showLeash(taskId)
    }

    override fun setTaskFocusable(taskId: Int, focusable: Boolean) {
        runCatching {
            // 缓存可能已被释放路径清空（releaseTaskTransforms 会移除弱引用）→ 现场解析兜底，
            // 否则焦点恢复会静默跳过（曾导致"回全屏后 launcher 抢前台"）。
            val task = FreeformBackend.taskById(taskId) ?: resolveTaskObject(taskId) ?: return
            if (focusable) {
                XposedHelpers.callMethod(task, "setFocusable", true)
            } else {
                val wasFocused = runCatching { XposedHelpers.callMethod(task, "isFocused") as? Boolean }
                    .getOrNull() ?: false
                XposedHelpers.callMethod(task, "setFocusable", false)
                if (wasFocused) {
                    runCatching {
                        XposedHelpers.callMethod(task, "adjustFocusToNextFocusableTask", "betterYAMF-float")
                    }
                    log(TAG, "task=$taskId demoted focus (float on top)")
                }
            }
        }.onFailure {
            log(TAG, "setTaskFocusable($focusable) failed task=$taskId", it)
        }
    }

    /** 现场按 taskId 解析 Task 对象（缓存失效时的兜底路径）。 */
    private fun resolveTaskObject(taskId: Int): Any? = runCatching {
        val runningTasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: return null
        val info = runningTasks.find {
            XposedHelpers.getIntField(it, "taskId") == taskId
        } ?: return null
        val token = XposedHelpers.getObjectField(info, "token")
        val binder = XposedHelpers.callMethod(token, "asBinder") as? android.os.IBinder
            ?: return null
        val cls = Instances.systemContext.classLoader
            .loadClass("com.android.server.wm.WindowContainer")
        val wc = XposedHelpers.callStaticMethod(cls, "fromBinder", binder) ?: return null
        XposedHelpers.callMethod(wc, "asTask")
    }.getOrNull()

    override fun bringTaskToFront(taskId: Int): Boolean {
        // 被接管的窗口任务位于悬浮根内部：置前 = 根内子任务顺序 + 承载根 reorder 到显示最上层。
        // 只对子任务 moveTaskToFront 不会把根抬到 home/其他应用之上（实测 HOME 后"内容消失"的根因）。
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            val moved = runCatching { Instances.activityManager.moveTaskToFront(taskId, 0) }.isSuccess
            val rootFront = SmoothTaskOrganizer.bringRootToFront()
            return moved || rootFront
        }
        return runCatching {
            // 平台方法返回 boolean（老版本 stub 可能是 void），统一归一化
            val result = Instances.activityManager.moveTaskToFront(taskId, 0)
            (result as? Boolean) ?: true
        }.getOrElse {
            log(TAG, "bringTaskToFront failed task=$taskId", it)
            false
        }
    }

    override fun isAlwaysOnTopActive(): Boolean = SmoothTaskOrganizer.isRootAlwaysOnTop()

    override fun isTaskManaged(taskId: Int): Boolean = SmoothTaskOrganizer.isLeashManaged(taskId)

    override fun managedTaskIds(): IntArray = SmoothTaskOrganizer.managedTaskIds()

    // === 后端 → 宿主回灌（由 SmoothTaskOrganizer 在系统回调内调用） ===

    fun notifyTaskAdopted(taskId: Int) {
        callbacks?.onTaskAdopted(taskId)
    }

    fun notifyTaskVanished(taskId: Int) {
        callbacks?.onTaskVanished(taskId)
    }
}
