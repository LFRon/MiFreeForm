package com.buildsession.betterYAMF.xposed.hook.organizer

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Process
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.xposed.hook.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.hook.system.SystemSignals
import com.buildsession.betterYAMF.xposed.hook.system.TaskSystemOps
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * `WindowBackend` 的实现：TaskOrganizer 悬浮根 + leash 承载（唯一实现）。
 *
 * 本类也是系统侧细节的收口点：
 * - 认领/释放 = `SmoothTaskOrganizer`（organizer/leash/WCT）；
 * - 几何 = leash 变换（被接管）或旧变换重断言（未接管）；几何缓存由本后端持有；
 * - 焦点 = `Task.setFocusable` + `adjustFocusToNextFocusableTask`（浮窗语义）；
 * - 系统操作（快照/Home/HOME 注入/窗口模式）= `TaskSystemOps`（反射细节不外泄）；
 * - 输入信号 = `SystemSignals`（触摸在屏态 / 最近任务手势抑制）；
 * - 未接管的平滑任务走旧 WCT 释放兜底。
 */
object SmoothWindowBackend : WindowBackend {
    private const val TAG = "reYAMF_Backend"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    @Volatile
    private var callbacks: WindowBackendCallbacks? = null

    /** 会话几何缓存：唯一写入者 = 本后端（applyGeometry/applyRealBounds 写、release/forget 清）。 */
    private val geometryByTask = ConcurrentHashMap<Int, Rect>()

    /** 供实现层（SmoothTaskOrganizer / FreeformBackend）读取的几何访问器。 */
    fun geometryOf(taskId: Int): Rect? = geometryByTask[taskId]

    /** 供实现层清理的几何访问器（任务消失等；幂等）。 */
    fun forgetGeometry(taskId: Int) {
        geometryByTask.remove(taskId)
    }

    override fun initialize(callbacks: WindowBackendCallbacks) {
        // 先绑回调再初始化：初始化过程中可能触发任务事件
        this.callbacks = callbacks
        SmoothTaskOrganizer.init()
    }

    override fun openTask(taskId: Int, densityDpi: Int): Boolean {
        // 会话密度先落地（逻辑层声明、执行层贯彻执行）：认领前把承载根密度就位，
        // 任务归入会话（reparent 入根）时即继承目标密度。
        SmoothTaskOrganizer.ensureSessionDensity(densityDpi)
        val adopted = SmoothTaskOrganizer.adoptTask(taskId)
        val moveResult = runCatching { Instances.activityManager.moveTaskToFront(taskId, 0) }
        log(TAG, "openTask task=$taskId adopted=$adopted moveToFront=$moveResult")
        // 用户主动开窗：恢复可聚焦（可交互）
        setTaskFocusable(taskId, true)
        return adopted
    }

    override fun releaseTask(taskId: Int, toFront: Boolean): Boolean {
        // 会话几何先清：无论走 organizer 释放还是旧路径兜底，几何缓存都不应残留
        forgetGeometry(taskId)
        // 关键：先把任务恢复为可聚焦，再发释放事务。释放事务内的 lifecycle 复算
        // （ensureActivitiesVisible + resumeFocusedTasksTopActivities）需要任务可聚焦，
        // 才会把回全屏后的任务判为可见/可恢复；否则焦点仍留在（已不可见的）launcher 上，
        // 实测 launcher 会随后把 home 置前（以 QuickstepLaunchHome 播放）→"应用看着退出了"。
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            setTaskFocusable(taskId, true)
            // "平台摘根=外部启动在途"判定（最近任务/图标点击触发的 relaunch 由平台直接把
            // 任务摘出我方根后再回调 vanish→release）：此类释放本质是"用户要打开应用全屏"，
            // 必须走确定性重建，否则外部 launch 已折叠为置前、随后进程死亡 → 应用直接消失。
            val outOfRootBefore = runCatching {
                val runningTasks = XposedHelpers.callMethod(
                    Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
                ) as? List<*> ?: return@runCatching false
                val info = runningTasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
                    .find { it.taskId == taskId } ?: return@runCatching false
                !SmoothTaskOrganizer.isTaskInFloatingRoot(info)
            }.getOrDefault(false)
            if (SmoothTaskOrganizer.releaseTask(taskId, toFront)) {
                // 会话期应用进程以会话密度"出生"（视图/资源缓存/静态量均为会话密度产物）：
                // 仅恢复配置不会重建界面 → 回全屏仍小密度。正确收尾 = 回收 UI 进程后
                // **移除旧任务再显式全屏冷启动**（与"重启进入会话"同一套已验证配方）：
                // 任务不被移除时显式启动会被 ATMS 折叠为"顶层活动已存在"（START_DELIVERED_TO_TOP，
                // 实测 result code=3），随后进程死亡 Force removing → 应用消失（真机实证）。
                recycleUiProcessOnRelease(taskId, toFront || outOfRootBefore)
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
            if (TaskSystemOps.windowingMode(taskInfo) == WINDOWING_MODE_FULLSCREEN) {
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
        geometryByTask[taskId] = physicalBounds
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            SmoothTaskOrganizer.applyLeash(taskId, physicalBounds)
        } else {
            FreeformBackend.reassertTaskById(taskId)
        }
    }

    override fun applyRealBounds(taskId: Int, bounds: Rect) {
        geometryByTask[taskId] = bounds
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            // 是否变更/原子复位由 organizer 依"任务当前 bounds"判定
            SmoothTaskOrganizer.applyRealBounds(taskId, bounds)
        } else {
            // 兼容路径（未接管）：走旧变换重断言
            FreeformBackend.reassertTaskById(taskId)
        }
    }

    override fun reapplyGeometry(taskId: Int) {
        val bounds = geometryByTask[taskId] ?: return
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

    override fun isTaskInSessionRoot(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        SmoothTaskOrganizer.isTaskInFloatingRoot(taskInfo)

    /**
     * 结束既有任务（"重启进入会话"用）：从最近任务移除 + 结束**承载该任务的 UI 进程**。
     *
     * 为什么必须清进程：既有任务的应用在显示器密度下渲染过，进程级资源缓存（皮肤引擎/
     * 位图缓存等）已按旧密度解码，且会跨 Activity 重建存活；`removeTask` 对带常驻服务/
     * 多进程的应用不保证杀进程，缓存跨进程存活 → 重启后仍会"部分 UI 旧密度"混排（QQ 实锤）。
     * 最小代价的正确解 = 只结束 UI 进程（缓存所在）；推送/下载等 :子进程 保留。
     * 顶层活动进程名精确解析失败时退回"结束该应用全部进程"（正确性优先——缓存必须清）。
     */
    override fun discardTask(taskId: Int): Boolean = runCatching {
        val runningTasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: run {
            log(TAG, "discardTask task=$taskId: tasks unavailable")
            return false
        }
        val info = runningTasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
            .find { it.taskId == taskId } ?: run {
            log(TAG, "discardTask task=$taskId: not found")
            return false
        }
        val pkg = info.baseActivity?.packageName ?: info.topActivity?.packageName

        // 顺序敏感：先杀 UI 进程再移除任务。任务仍挂载时 topActivityProcessName 能解析到
        // UI 进程名（精确只杀缓存所在进程，:MSF 等子进程保留）；先 removeTask 会让任务
        // detached → 解析失败 → 退化为"全杀"（真机实证：ui process unresolved）。
        killUiProcesses(info)

        // removeTask 为隐藏系统 API（SDK 存根不含），统一走反射
        runCatching { XposedHelpers.callMethod(Instances.activityTaskManager, "removeTask", taskId) }
            .onFailure { log(TAG, "discardTask removeTask failed task=$taskId", it) }

        log(TAG, "discardTask task=$taskId pkg=$pkg (removed + ui process recycled)")
        true
    }.onFailure { log(TAG, "discardTask failed task=$taskId", it) }.getOrDefault(false)

    /**
     * 结束承载该任务顶层活动的进程（进程级资源缓存所在）。
     * 精确解析失败时退回"结束该应用全部进程"（正确性优先——缓存必须清，否则密度残影）。
     */
    private fun killUiProcesses(info: ActivityManager.RunningTaskInfo) {
        val pkg = info.baseActivity?.packageName ?: info.topActivity?.packageName
        val uiProcess = topActivityProcessName(info)
        val procs = Instances.activityManager.getRunningAppProcesses()
        if (uiProcess != null) {
            procs?.filter { it.processName == uiProcess }?.forEach { pr ->
                runCatching { Process.killProcess(pr.pid) }
                    .onFailure { log(TAG, "kill UI process failed pid=${pr.pid}", it) }
            }
            log(TAG, "killUiProcesses uiProcess=$uiProcess (siblings preserved)")
        } else if (pkg != null) {
            procs?.filter { it.processName == pkg || it.processName.startsWith("$pkg:") }?.forEach { pr ->
                runCatching { Process.killProcess(pr.pid) }
                    .onFailure { log(TAG, "kill app processes failed pid=${pr.pid}", it) }
            }
            log(TAG, "killUiProcesses pkg=$pkg (ui process unresolved; all processes killed)")
        }
    }

    /**
     * 回全屏收尾（"重启进入会话"在退出侧的对称实现）：
     * 1. 先杀承载该任务的 UI 进程（进程级资源缓存所在；任务仍挂载时才能精确解析 UI 进程名）；
     * 2. 需要回到前台的场景（toFront=true：下拉全屏 / 平台摘根=外部启动在途）
     *    再 `removeTask` 移除旧任务 —— 否则显式启动会被 ATMS 折叠为"顶层活动已存在"
     *    （START_DELIVERED_TO_TOP），随后进程死亡 Force removing → 应用消失（真机实证）；
     * 3. 最后以全屏语义显式冷启动：新建任务、全新进程出生配置即显示器密度。
     *
     * 关闭小窗（toFront=false 且任务仍在我方根内）只回收不拉起：任务留在最近任务，
     * 下次打开即冷启动正确密度。
     */
    private fun recycleUiProcessOnRelease(taskId: Int, toFront: Boolean) {
        val resolved = runCatching {
            val runningTasks = XposedHelpers.callMethod(
                Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
            ) as? List<*> ?: return@runCatching null
            val info = runningTasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
                .find { it.taskId == taskId } ?: return@runCatching null
            val component = info.topActivity ?: info.baseActivity ?: return@runCatching null
            val userId = XposedHelpers.getIntField(info, "userId")
            killUiProcesses(info)
            if (toFront) {
                runCatching { XposedHelpers.callMethod(Instances.activityTaskManager, "removeTask", taskId) }
                    .onFailure { log(TAG, "removeTask before relaunch failed task=$taskId", it) }
            }
            component to userId
        }.getOrNull() ?: return

        if (!toFront) return
        runCatching {
            // 显式全屏冷启动：无 launchRootTask（留在默认 TDA），NEW_TASK 必新建任务
            // （旧任务已移除，启动不再可能被折叠）；全新进程以当前配置（显示器密度）出生。
            val intent = Intent().apply {
                component = resolved.first
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val userHandle = XposedHelpers.callStaticMethod(
                android.os.UserHandle::class.java, "of", resolved.second
            )
            XposedHelpers.callMethod(
                Instances.systemContext, "startActivityAsUser",
                arrayOf(Intent::class.java, android.os.Bundle::class.java, android.os.UserHandle::class.java),
                intent, null, userHandle
            )
            log(TAG, "relaunch fullscreen after release task=$taskId component=${resolved.first}")
        }.onFailure { log(TAG, "relaunch fullscreen failed task=$taskId", it) }
    }

    /** 解析任务顶层活动所在进程名（UI 进程，缓存所在）；失败返回 null。 */
    private fun topActivityProcessName(taskInfo: ActivityManager.RunningTaskInfo): String? = runCatching {
        val token = XposedHelpers.getObjectField(taskInfo, "token") ?: return null
        val binder = XposedHelpers.callMethod(token, "asBinder") as? android.os.IBinder ?: return null
        val wcClass = Instances.systemContext.classLoader
            .loadClass("com.android.server.wm.WindowContainer")
        val wc = XposedHelpers.callStaticMethod(wcClass, "fromBinder", binder) ?: return null
        val task = XposedHelpers.callMethod(wc, "asTask") ?: return null
        val activity = XposedHelpers.callMethod(task, "topRunningActivity") ?: return null
        // ActivityRecord#getProcessName（ActivityRecord.java:9267）
        XposedHelpers.callMethod(activity, "getProcessName") as? String
    }.getOrNull()

    /**
     * 小窗会话启动：freeform 目标 + **启动即入承载根**（承载根标识属实现细节，逻辑层不感知）。
     *
     * 源码对齐：`RootWindowContainer#getOrCreateRootTask`（:3156）第一优先取
     * `options.getLaunchRootTask()`；`TaskDisplayArea#getLaunchRootTask`（:1012）校验
     * `mCreatedByOrganizer`（我们的承载根满足）；`ActivityOptions#toBundle`（:2498）携带
     * `KEY_LAUNCH_ROOT_TASK_TOKEN` 过 Binder。
     *
     * 意义：应用从首帧起就处于根级密度覆盖之下，不再经历"先按显示器密度渲染、settle 时再改
     * 密度"的重建 + 缓存残影（QQ 大小混排的根因）。根未就绪则退回默认启动路径（认领兜底）。
     */
    override fun launchAppForSession(component: ComponentName, userId: Int, densityDpi: Int): Boolean = runCatching {
        // 密度先于启动落地：应用任务出生（入根）时承载根密度已就位 → 首帧即会话密度。
        // 落地方式（承载根容器级覆盖 + WCT）是执行层细节，逻辑层只声明目标值。
        SmoothTaskOrganizer.ensureSessionDensity(densityDpi)
        val options = android.app.ActivityOptions.makeBasic()
        XposedHelpers.callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
        SmoothTaskOrganizer.launchRootToken()?.let { rootToken ->
            XposedHelpers.callMethod(options, "setLaunchRootTask", rootToken)
        }

        val intent = Intent().apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        val userHandle = XposedHelpers.callStaticMethod(
            android.os.UserHandle::class.java,
            "of",
            userId
        )
        XposedHelpers.callMethod(
            Instances.systemContext,
            "startActivityAsUser",
            intent,
            options.toBundle(),
            userHandle
        )
        true
    }.onFailure {
        log(TAG, "launchAppForSession failed component=$component user=$userId", it)
    }.getOrDefault(false)

    override fun currentGeometry(taskId: Int): Rect? = geometryOf(taskId)

    override fun forgetTask(taskId: Int) {
        forgetGeometry(taskId)
    }

    override fun requestSnapshotRefresh(taskId: Int) {
        TaskSystemOps.refreshTaskSnapshot(taskId)
    }

    override fun windowingModeOf(taskInfo: ActivityManager.RunningTaskInfo): Int =
        TaskSystemOps.windowingMode(taskInfo)

    override fun isHomeTask(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        TaskSystemOps.isHomeTask(taskInfo)

    override fun isTouchActive(): Boolean = SystemSignals.isTouchActive()

    override fun suppressRecentsGestureFor(durationMs: Long) {
        SystemSignals.suppressRecentsGestureFor(durationMs)
    }

    override fun recentsGestureSuppressUntilMs(): Long = SystemSignals.recentsGestureSuppressUntilMs

    override fun injectHomeKey(): Boolean = TaskSystemOps.injectHomeKey()

    // === 后端 → 宿主回灌（由 SmoothTaskOrganizer 在系统回调内调用） ===

    fun notifyTaskAdopted(taskId: Int) {
        callbacks?.onTaskAdopted(taskId)
    }

    fun notifyTaskVanished(taskId: Int) {
        callbacks?.onTaskVanished(taskId)
    }
}
