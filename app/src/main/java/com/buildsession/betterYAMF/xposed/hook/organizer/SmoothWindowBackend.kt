package com.buildsession.betterYAMF.xposed.hook.organizer

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.Surface
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.xposed.hook.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.hook.system.SystemSignals
import com.buildsession.betterYAMF.xposed.hook.system.TaskSystemOps
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.delay
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

    /** 默认（物理）显示器 id（无杀迁移的"回全屏"目标）。 */
    private const val DEFAULT_DISPLAY_ID = 0

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

    override fun openTask(taskId: Int): Boolean {
        // 会话显示器承载：任务已在会话屏上以普通全屏应用身份运行，无任务级承载语义可认领；
        // 仅恢复可聚焦（可交互）后返回。
        if (SessionDisplays.isTaskOnSession(taskId)) {
            setTaskFocusable(taskId, true)
            log(TAG, "openTask task=$taskId (session display; adopt skipped)")
            return true
        }
        // 容器兜底（无会话显示器）：认领 + 置前；不施加任何密度覆盖——小窗不改应用 DPI。
        val adopted = SmoothTaskOrganizer.adoptTask(taskId)
        val moveResult = runCatching { Instances.activityManager.moveTaskToFront(taskId, 0) }
        log(TAG, "openTask task=$taskId adopted=$adopted moveToFront=$moveResult")
        // 用户主动开窗：恢复可聚焦（可交互）
        setTaskFocusable(taskId, true)
        return adopted
    }

    override fun releaseTask(taskId: Int, toFront: Boolean, sessionWindowId: Int): Boolean {
        // 会话几何先清：无论走 organizer 释放还是旧路径兜底，几何缓存都不应残留
        forgetGeometry(taskId)

        // 会话显示器承载：任务随会话屏存在，无容器可释放；回全屏语义 = **无杀进程迁移**——
        // 任务整体迁移回默认显示器（toFront=true 置顶=下拉全屏；false 沉底=关闭小窗转后台），
        // 应用进程/服务/最近任务条目全部保留；不改变任何应用 DPI。
        // [sessionWindowId]：宿主显式声明（会话窗口）——不依赖 displayId 反射查询；且当宿主绑定的
        // taskId 已失配（应用自建新任务）时，按"该会话显示器上的顶层任务"兜底解析（真机实锤）。
        if (sessionWindowId >= 0 || SessionDisplays.isTaskOnSession(taskId)) {
            setTaskFocusable(taskId, true)
            val moved = moveTaskOffSession(taskId, toFront, sessionWindowId)
            log(TAG, "releaseTask task=$taskId (session display; moved off, toFront=$toFront, window=$sessionWindowId) moved=$moved")
            return moved
        }
        // 关键：先把任务恢复为可聚焦，再发释放事务。释放事务内的 lifecycle 复算
        // （ensureActivitiesVisible + resumeFocusedTasksTopActivities）需要任务可聚焦，
        // 才会把回全屏后的任务判为可见/可恢复；否则焦点仍留在（已不可见的）launcher 上，
        // 实测 launcher 会随后把 home 置前（以 QuickstepLaunchHome 播放）→"应用看着退出了"。
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            setTaskFocusable(taskId, true)
            // 无杀进程：组织者释放事务就地恢复任务（表面变换复位/回原父级/前后台语义，
            // 含"平台摘根=外部启动在途"的保持在前判定——SmoothTaskOrganizer#releaseTask
            // 内部 effectiveToFront 已覆盖）；不回收 UI 进程、不冷启动、不改任何密度。
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

    override fun isTaskManaged(taskId: Int): Boolean =
        SmoothTaskOrganizer.isLeashManaged(taskId) || SessionDisplays.isTaskOnSession(taskId)

    override fun managedTaskIds(): IntArray {
        val base = SmoothTaskOrganizer.managedTaskIds().toMutableList()
        if (SessionDisplays.anyActive()) {
            runCatching {
                val tasks = XposedHelpers.callMethod(
                    Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
                ) as? List<*> ?: return@runCatching
                tasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
                    .filter { SessionDisplays.containsDisplay(XposedHelpers.getIntField(it, "displayId")) }
                    .forEach { if (!base.contains(it.taskId)) base.add(it.taskId) }
            }
        }
        return base.toIntArray()
    }

    override fun isTaskInSessionRoot(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        SmoothTaskOrganizer.isTaskInFloatingRoot(taskInfo) ||
                SessionDisplays.containsDisplay(XposedHelpers.getIntField(taskInfo, "displayId"))

    /**
     * 任务是否运行在任一会话显示器上（流程分拣：会话屏任务是"我方启动"的，
     * 与容器路径的"已在会话根内"语义等价，共用同一分拣分支）。
     */
    override fun isTaskInSessionDisplay(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        SessionDisplays.containsDisplay(XposedHelpers.getIntField(taskInfo, "displayId"))

    // ---- 在途过渡保护（迁移前置闸门）------------------------------------------------
    // 真机实证（两次复现 + `dumpsys SurfaceFlinger --list` 图层树 + 过渡日志）：
    // 直连 `moveRootTaskToDisplayOnTopOrBottom` 若在"有过渡在飞"时执行，迁移的表面重挂会被
    // 在途过渡（recents/launcher 的收尾事务）还原：WM 状态 = 会话屏（任务/窗口 displayId=会话屏），
    // 而 SurfaceFlinger 图层仍挂在默认显示器 TDA 下（Task#N parentId=DefaultTaskDisplayArea#9）
    // ——应用实体渲染在物理屏（真机截图）、会话屏空白。
    // 保护：迁移前等待 `TransitionController.isPlaying()==false`（TransitionController.java:587；
    // 取用路径 ATMS.getTransitionController() :1165——`Instances.activityTaskManager` 进程内直取
    // ATMS；兜底 mWindowOrganizerController.mTransitionController）。等待用协程 delay 挂起轮询，
    // 不阻塞系统主线程（过渡推进依赖系统主线程，阻塞等待必然死锁）。
    private const val TRANSITION_IDLE_STEP_MS = 24L
    private const val TRANSITION_IDLE_TIMEOUT_MS = 2000L

    /** 是否有 WM/Shell 过渡在飞（反射失败按"无过渡"处理——闸门不允许卡死迁移）。 */
    private fun transitionsPlaying(): Boolean = runCatching {
        val atm = Instances.activityTaskManager
        val controller = runCatching { XposedHelpers.callMethod(atm, "getTransitionController") }.getOrNull()
            ?: runCatching {
                XposedHelpers.getObjectField(
                    XposedHelpers.getObjectField(atm, "mWindowOrganizerController"),
                    "mTransitionController"
                )
            }.getOrNull()
            ?: return false
        XposedHelpers.callMethod(controller, "isPlaying") as Boolean
    }.getOrDefault(false)

    /** 等所有在途过渡收尾后再执行 [action]（有界；超时按现状执行并告警）。 */
    private fun whenTransitionsIdle(what: String, action: () -> Unit) = runMain {
        if (transitionsPlaying()) {
            log(TAG, "$what waiting for transitions to settle")
        }
        var waited = 0L
        while (transitionsPlaying() && waited < TRANSITION_IDLE_TIMEOUT_MS) {
            delay(TRANSITION_IDLE_STEP_MS)
            waited += TRANSITION_IDLE_STEP_MS
        }
        if (transitionsPlaying()) {
            log(TAG, "$what transition-idle wait timed out (${waited}ms); proceeding")
        }
        action()
    }

    /**
     * 既有任务迁移进入会话显示器（"重启进入会话"的无杀进程替代）。
     *
     * 迁移链（对照 LoS 24.0 源码）：
     * 1. `IActivityTaskManager.moveRootTaskToDisplayOnTopOrBottom`（AIDL:416；
     *    ActivityTaskManagerService.java:3507）→ `RootWindowContainer.moveRootTaskToDisplay`
     *    （:2120）→ `Task.reparent(TaskDisplayArea, onTop)`（Task.java:6506）+
     *    `Task.resumeNextFocusAfterReparent`（:5113）→ `resumeFocusedTasksTopActivities`
     *    + `ensureActivitiesVisible`；
     * 2. **小窗不改应用 DPI**：会话屏密度与默认显示器一致，迁移仅产生窗口尺寸变化——
     *    声明了 screenSize/screenLayout 的应用走 onConfigurationChanged 自行重排，
     *    未声明者由框架按标准语义同进程重建（进程/服务存活，无任何密度适配）；
     * 3. 无 killApplicationProcess、无 removeTask、无冷启动。
     *
     * 失败兜底：任务不是顶层根任务等罕见情形走启动事务迁移（[moveTaskByLaunch]）。
     */
    override fun moveTaskToSession(taskId: Int, windowId: Int): Boolean {
        if (transitionsPlaying()) {
            // 在途过渡期间直连迁移会被其收尾事务还原（见闸门注释）：推迟到过渡收尾后执行；
            // 此处返回 true="已受理"（调用方的冷启动兜底链移入延迟执行体，防重复启动）。
            log(TAG, "moveTaskToSession task=$taskId deferred (transition in flight)")
            whenTransitionsIdle("moveTaskToSession task=$taskId") {
                if (!performMoveToSession(taskId, windowId)) {
                    val info = taskInfoOf(taskId)
                    val component = info?.topActivity ?: info?.baseActivity
                    val userId = info?.let { XposedHelpers.getIntField(it, "userId") } ?: 0
                    if (component != null) {
                        log(TAG, "deferred moveTaskToSession failed; fallback cold launch task=$taskId")
                        launchAppForSession(component, userId, windowId)
                    } else {
                        log(TAG, "deferred moveTaskToSession failed; task gone task=$taskId")
                    }
                }
            }
            return true
        }
        return performMoveToSession(taskId, windowId)
    }

    private fun performMoveToSession(taskId: Int, windowId: Int): Boolean {
        val sessionDisplayId = if (windowId >= 0) SessionDisplays.displayIdFor(windowId) else -1
        if (sessionDisplayId < 0) return false

        // 已在任一会话屏上：目标屏幂等成功；其它窗口承载中的任务不抢夺
        val currentDisplayId = SessionDisplays.displayIdOfTask(taskId)
        if (currentDisplayId != null && SessionDisplays.containsDisplay(currentDisplayId)) {
            if (currentDisplayId == sessionDisplayId) {
                setTaskFocusable(taskId, true)
                return true
            }
            log(TAG, "moveTaskToSession task=$taskId already on another session display=$currentDisplayId; skip")
            return false
        }

        val info = taskInfoOf(taskId) ?: run {
            log(TAG, "moveTaskToSession task=$taskId not found")
            return false
        }
        if (TaskSystemOps.isHomeTask(info)) {
            log(TAG, "moveTaskToSession task=$taskId is home; skip")
            return false
        }

        if (moveTaskToDisplay(taskId, sessionDisplayId, toFront = true)) {
            setTaskFocusable(taskId, true)
            log(TAG, "moveTaskToSession task=$taskId -> display=$sessionDisplayId (in-place, no kill)")
            return true
        }
        if (moveTaskByLaunch(info, sessionDisplayId)) {
            log(TAG, "moveTaskToSession task=$taskId -> display=$sessionDisplayId (launch fallback)")
            return true
        }
        log(TAG, "moveTaskToSession task=$taskId failed")
        return false
    }

    /** 回全屏/关闭收尾：把会话屏上的任务迁移回默认显示器（[toFront]=false 沉底=关闭转后台）。 */
    private fun moveTaskOffSession(taskId: Int, toFront: Boolean, sessionWindowId: Int): Boolean {
        if (transitionsPlaying()) {
            // 同 moveTaskToSession：在途过渡期间直连迁移会被其收尾事务还原（真机实证）。
            log(TAG, "moveTaskOffSession task=$taskId deferred (transition in flight)")
            whenTransitionsIdle("moveTaskOffSession task=$taskId") {
                if (!performMoveOffSession(taskId, toFront, sessionWindowId)) {
                    log(TAG, "deferred moveTaskOffSession failed task=$taskId")
                }
            }
            return true
        }
        return performMoveOffSession(taskId, toFront, sessionWindowId)
    }

    private fun performMoveOffSession(taskId: Int, toFront: Boolean, sessionWindowId: Int): Boolean {
        val info = resolveSessionTask(taskId, sessionWindowId) ?: run {
            // 任务已不在会话屏（如下拉全屏路径的二次释放调用）：no-op 成功
            log(TAG, "moveTaskOffSession task=$taskId: no task on session display (no-op)")
            return true
        }
        if (moveTaskToDisplay(info.taskId, DEFAULT_DISPLAY_ID, toFront)) {
            log(TAG, "moveTaskOffSession task=${info.taskId} -> display $DEFAULT_DISPLAY_ID (onTop=$toFront, in-place, no kill)")
            return true
        }
        if (moveTaskByLaunch(info, DEFAULT_DISPLAY_ID)) {
            log(TAG, "moveTaskOffSession task=${info.taskId} -> display $DEFAULT_DISPLAY_ID (launch fallback)")
            return true
        }
        log(TAG, "moveTaskOffSession task=$taskId failed")
        return false
    }

    /**
     * 解析会话屏上的目标任务：taskId 精确命中（且确实在会话屏上）优先；
     * 失配（应用自建新任务后宿主绑定失效，真机实锤）按"该窗口会话屏上的顶层任务"兜底
     * （getTasks 顺序即根容器自顶向下，首个命中即当前顶层任务）。
     */
    private fun resolveSessionTask(taskId: Int, sessionWindowId: Int): ActivityManager.RunningTaskInfo? {
        val infos = runningTaskInfos()
        val exact = infos.find { it.taskId == taskId }
        if (exact != null && runCatching {
                SessionDisplays.containsDisplay(XposedHelpers.getIntField(exact, "displayId"))
            }.getOrDefault(false)) {
            return exact
        }
        if (sessionWindowId >= 0) {
            val displayId = SessionDisplays.displayIdFor(sessionWindowId)
            if (displayId >= 0) {
                return infos.find {
                    runCatching { XposedHelpers.getIntField(it, "displayId") == displayId }
                        .getOrDefault(false)
                }
            }
        }
        return null
    }

    /**
     * 把任务（须为显示区顶层根任务）移动到目标显示器顶层/底层：
     * `moveRootTaskToDisplayOnTopOrBottom` → `TaskDisplayArea` reparent + 顶层/底部定序
     * （onTop=true 触发 `onTaskMoved` → `notifyTaskMovedToFront`，真机事件日志实锤
     * wm_task_to_front）。返回是否成功送达系统调用。
     */
    private fun moveTaskToDisplay(taskId: Int, displayId: Int, toFront: Boolean): Boolean = runCatching {
        XposedHelpers.callMethod(
            Instances.activityTaskManager,
            "moveRootTaskToDisplayOnTopOrBottom", taskId, displayId, toFront
        )
        true
    }.getOrElse {
        log(TAG, "moveTaskToDisplay failed task=$taskId display=$displayId onTop=$toFront", it)
        false
    }

    /**
     * 启动事务迁移兜底：以任务 baseIntent（剥掉 NEW_DOCUMENT/MULTIPLE_TASK，仅保留 NEW_TASK）
     * 重新请求启动到目标屏——`ActivityStarter.resolveReusableTask`（:3133）按
     * realActivity/affinity 命中同一任务后 `reparentToTargetRootTask`（:3287），
     * 无新实例、无杀进程（真机实锤：同 taskId/同 ActivityRecord 跨屏往返）。
     */
    private fun moveTaskByLaunch(info: ActivityManager.RunningTaskInfo, displayId: Int): Boolean = runCatching {
        val baseIntent = info.baseIntent ?: return false
        val options = android.app.ActivityOptions.makeBasic()
        XposedHelpers.callMethod(options, "setLaunchDisplayId", displayId)
        val intent = Intent(baseIntent).apply {
            // 复用解析要求 baseIntent 不带 MULTIPLE_TASK/NEW_DOCUMENT；NEW_TASK 必需
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val userId = XposedHelpers.getIntField(info, "userId")
        val userHandle = XposedHelpers.callStaticMethod(android.os.UserHandle::class.java, "of", userId)
        XposedHelpers.callMethod(
            Instances.systemContext, "startActivityAsUser",
            arrayOf(Intent::class.java, android.os.Bundle::class.java, android.os.UserHandle::class.java),
            intent, options.toBundle(), userHandle
        )
        true
    }.getOrElse {
        log(TAG, "moveTaskByLaunch failed task=${info.taskId} display=$displayId", it)
        false
    }

    /** 全量任务快照（getTasks；顺序即顶层在前）。 */
    private fun runningTaskInfos(): List<ActivityManager.RunningTaskInfo> = runCatching {
        val tasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: return emptyList()
        tasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
    }.getOrDefault(emptyList())

    private fun taskInfoOf(taskId: Int): ActivityManager.RunningTaskInfo? =
        runningTaskInfos().find { it.taskId == taskId }

    /** 同包可迁移任务（排除已运行在会话屏上的与 Home——不抢夺其它窗口承载中的实例/桌面任务）。 */
    private fun findMovableTaskIdForPackage(packageName: String): Int? =
        runningTaskInfos().firstOrNull { info ->
            val pkg = info.topActivity?.packageName ?: info.baseActivity?.packageName
            pkg == packageName &&
                    !TaskSystemOps.isHomeTask(info) &&
                    runCatching {
                        !SessionDisplays.containsDisplay(XposedHelpers.getIntField(info, "displayId"))
                    }.getOrDefault(false)
        }?.taskId

    /**
     * 小窗会话启动（两条实现路径）：
     *
     * - **会话显示器承载（现行默认）**：宿主窗口已有会话屏 → 同包既有任务就地迁移进会话屏
     *   （无杀进程、不改 DPI）；无既有任务时以普通全屏活动冷启动到会话屏
     *   （NEW_TASK，允许 ATMS 复用既有任务，不制造重复实例）。
     * - **容器兜底（无会话屏）**：freeform 启动 + **启动即入承载根**（承载根标识属实现细节，
     *   逻辑层不感知）。源码对齐：`RootWindowContainer#getOrCreateRootTask`（:3156）第一优先取
     *   `options.getLaunchRootTask()`；`TaskDisplayArea#getLaunchRootTask`（:1012）校验
     *   `mCreatedByOrganizer`（我们的承载根满足）；`ActivityOptions#toBundle`（:2498）携带
     *   `KEY_LAUNCH_ROOT_TASK_TOKEN` 过 Binder。根未就绪则退回默认启动路径（认领兜底）。
     */
    override fun launchAppForSession(component: ComponentName, userId: Int, windowId: Int): Boolean = runCatching {
        // 会话显示器承载（实现路由）：宿主窗口已有会话屏 → 应用以普通全屏活动进入该屏。
        // **小窗不改应用 DPI**：会话屏密度跟随默认显示器，跨屏迁移只产生窗口尺寸变化
        // （标准 Android 窗口行为）；不设 launchWindowingMode/launchRootTask（容器实现细节）。
        val sessionDisplayId = if (windowId >= 0) SessionDisplays.displayIdFor(windowId) else -1
        if (sessionDisplayId >= 0) {
            // 无杀进程优先：同包既有任务就地迁移进入会话屏（应用进程存活、任务身份保留，
            // 不再制造重复实例）——失败才退回冷启动（下面 NEW_TASK 分支）。
            findMovableTaskIdForPackage(component.packageName)?.let { existing ->
                if (moveTaskToDisplay(existing, sessionDisplayId, toFront = true)) {
                    setTaskFocusable(existing, true)
                    log(TAG, "launchAppForSession move-existing task=$existing display=$sessionDisplayId pkg=${component.packageName}")
                    return@runCatching true
                }
                log(TAG, "launchAppForSession move-existing failed task=$existing pkg=${component.packageName}; fallback cold launch")
            }

            val options = android.app.ActivityOptions.makeBasic()
            XposedHelpers.callMethod(options, "setLaunchDisplayId", sessionDisplayId)

            // 关键：只带 FLAG_ACTIVITY_NEW_TASK（不带 NEW_DOCUMENT/MULTIPLE_TASK）——
            // 允许 ATMS 复用既有任务（ActivityStarter.resolveReusableTask :3133），
            // 冷启动同样"一应用一任务"，不再制造重复实例。
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            log(TAG, "launchAppForSession cold display=$sessionDisplayId component=$component user=$userId")
            return@runCatching true
        }

        // 容器兜底路径（无会话显示器）：freeform 启动入承载根；不施加任何密度覆盖——
        // 小窗不改应用 DPI，应用按默认显示器密度与窗口尺寸自行布局。
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

    // === 会话显示器能力（"虚拟屏承载"实现细节收口在 SessionDisplays） ===

    override fun usesDisplaySession(): Boolean = runCatching {
        // 配置读取（旧配置文件无此键 → null → 视为默认启用"显示器承载"）
        YAMFManager.config.useVirtualDisplay != false
    }.getOrDefault(false)

    override fun attachSessionDisplay(windowId: Int, width: Int, height: Int): Int =
        SessionDisplays.createForWindow(windowId, width, height, defaultDisplayDensity())

    /** 默认（物理）显示器当前密度——会话屏沿用（小窗不改应用 DPI，跨屏无密度差异）。 */
    private fun defaultDisplayDensity(): Int = runCatching {
        Instances.systemContext.resources.displayMetrics.densityDpi
    }.getOrDefault(android.util.DisplayMetrics.DENSITY_DEFAULT)

    override fun updateSessionDisplaySurface(windowId: Int, surface: Surface?) {
        SessionDisplays.setSurface(windowId, surface)
    }

    override fun resizeSessionDisplay(windowId: Int, width: Int, height: Int) {
        SessionDisplays.resize(windowId, width, height)
    }

    override fun releaseSessionDisplay(windowId: Int) {
        SessionDisplays.release(windowId)
    }

    override fun sessionDisplayId(windowId: Int): Int = SessionDisplays.displayIdFor(windowId)

    override fun injectSessionPointer(windowId: Int, event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean =
        SessionDisplays.injectPointer(windowId, event, viewWidth, viewHeight)

    override fun injectSessionKey(windowId: Int, keyCode: Int): Boolean =
        SessionDisplays.injectKey(windowId, keyCode)

    // === 后端 → 宿主回灌（由 SmoothTaskOrganizer 在系统回调内调用） ===

    fun notifyTaskAdopted(taskId: Int) {
        callbacks?.onTaskAdopted(taskId)
    }

    fun notifyTaskVanished(taskId: Int) {
        callbacks?.onTaskVanished(taskId)
    }
}
