package com.buildsession.betterYAMF.xposed.hook.organizer

import android.app.ActivityManager
import android.graphics.Rect
import android.os.Binder
import android.view.SurfaceControl
import android.window.ITaskOrganizer
import com.buildsession.betterYAMF.xposed.hook.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.paramCount
import de.robv.android.xposed.XposedHelpers
import net.bytebuddy.ByteBuddy
import net.bytebuddy.android.AndroidClassLoadingStrategy
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.AllArguments
import net.bytebuddy.implementation.bind.annotation.Origin
import net.bytebuddy.implementation.bind.annotation.RuntimeType
import net.bytebuddy.matcher.ElementMatchers
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * LineageOS 24.0（A17）TaskOrganizer 适配。
 *
 * 该版本模型（源码实锤）：
 * - `TaskOrganizerController.registerTaskOrganizer` 注册时会对**所有任务**做
 *   `task.updateTaskOrganizerState()`：可组织（根任务/组织者创建的父任务下的直接子任务）的
 *   任务会被 `setTaskOrganizer(getTaskOrganizer())` 认领，而 `getTaskOrganizer() = mTaskOrganizers.peekLast()`
 *   ——即"最后注册的 organizer"会**接走全部根任务**（把系统壳顶掉）。
 * - 创建专属根任务的新 API：`createTask(TaskCreationParams)`（返回 TaskAppearedInfo，含 taskInfo+leash）。
 * - `Flags.unifyShellBinders` 时注册还会把过渡播放器转发给最后注册的 organizer（多播列表，附加式）。
 *
 * 因此本模块采用**透明插入**：
 * 1. 注册前捕获上一任 organizer；
 * 2. 先钩 `Task.setTaskOrganizer(ITaskOrganizer, boolean)` 做**选择性路由**：
 *    只有我们悬浮根子树的任务归我们，其余任务强制保持上一任（系统壳行为不变）；
 * 3. 再用 `createTask(TaskCreationParams)` 建专属悬浮根，`WCT.reorder(onTop)`；
 * 4. 窗口任务 reparent 进根 → 成为 organized → `onTaskAppeared(taskInfo, leash)` 交给我们摆位。
 */
object SmoothTaskOrganizer {
    private const val TAG = "reYAMF_Organizer"
    private const val WINDOWING_MODE_FREEFORM = 5

    private val byteBuddyStrategy =
        AndroidClassLoadingStrategy.Wrapping(File("/data/system/reYAMF").also { it.mkdirs() })

    @Volatile
    var registered = false
        private set

    /** 注册前最后一任 organizer（通常是系统壳）；非本子树任务在路由时强制还给它的。 */
    @Volatile
    private var previousOrganizer: Any? = null

    @Volatile
    private var floatingRootTaskId = -1

    @Volatile
    private var floatingRootToken: Any? = null

    @Volatile
    private var floatingRootLeash: SurfaceControl? = null

    private val leashes = ConcurrentHashMap<Int, SurfaceControl>()
    private val logicalBounds = ConcurrentHashMap<Int, Rect>()
    private val originalParentTokens = ConcurrentHashMap<Int, Any>()
    private val adoptedTasks = ConcurrentHashMap.newKeySet<Int>()

    /** 待认领任务：认领前需要先把任务挂到 organizer 上（A17 的 WCT 只接受 organized 容器） */
    private val pendingAdoption = ConcurrentHashMap.newKeySet<Int>()

    /** 正在释放中的任务：期间 organizer 切换产生的 onTaskVanished 不回灌宿主（避免重入） */
    private val releasingTasks = ConcurrentHashMap.newKeySet<Int>()

    /** leash 写入日志节流（诊断用；物理矩形异常时可从这里溯源） */
    private val lastLeashWriteLogAt = ConcurrentHashMap<Int, Long>()

    fun isLeashManaged(taskId: Int): Boolean = adoptedTasks.contains(taskId)

    /**
     * 自持 leash 句柄。
     *
     * 关键源码事实（LineageOS 24.0 `TaskOrganizerController.TaskOrganizerCallbacks`）：
     * callback 里的 leash 是 `new SurfaceControl(task.getSurfaceControl(), reason)`，
     * 回调返回后 WM 立即 `finally { leash.release(); }`。本模块注册在 system_server 进程内
     * （本地直调，不是 binder 转发），拿到的就是同一个 Java 对象——直接存下来会立刻变成
     * 已释放句柄，之后所有事务调用都会抛异常（checkNotReleased），表现为"窗口永远整屏"。
     * 因此必须在回调同步期内复制出自己的句柄（复制构造会新增 native 引用，不受 WM 释放影响）。
     */
    private fun ownLeash(source: SurfaceControl?): SurfaceControl? {
        if (source == null) return null
        return runCatching {
            XposedHelpers.newInstance(SurfaceControl::class.java, source, "reYAMF_leash") as SurfaceControl
        }.onFailure {
            log(TAG, "ownLeash failed (fallback to source object)", it)
        }.getOrElse { source }
    }

    private fun releaseQuietly(sc: SurfaceControl?) {
        if (sc == null) return
        runCatching { XposedHelpers.callMethod(sc, "release") }
    }

    /** 用任务的 WindowContainerToken 解析 Task 对象（同一进程内直取）。 */
    private fun resolveTaskByToken(token: Any?): Any? {
        if (token == null) return null
        return runCatching {
            val binder = XposedHelpers.callMethod(token, "asBinder") as? android.os.IBinder
                ?: return null
            val wcClass = Instances.systemContext.classLoader
                .loadClass("com.android.server.wm.WindowContainer")
            val wc = XposedHelpers.callStaticMethod(wcClass, "fromBinder", binder) ?: return null
            XposedHelpers.callMethod(wc, "asTask")
        }.getOrNull()
    }

    private fun isTaskOrganized(task: Any): Boolean =
        (runCatching { XposedHelpers.callMethod(task, "isOrganized") }.getOrNull() as? Boolean) == true

    val organizer: ITaskOrganizer by lazy {
        ByteBuddy()
            .subclass(ITaskOrganizer.Stub::class.java)
            .method(ElementMatchers.isDeclaredBy(ITaskOrganizer::class.java))
            .intercept(MethodDelegation.to(object {
                @RuntimeType
                fun intercept(@AllArguments allArguments: Array<Any?>, @Origin method: Method) {
                    runCatching { handle(method.name, allArguments) }
                        .onFailure { log(TAG, "callback ${method.name} failed", it) }
                }
            }))
            .make()
            .load(SmoothTaskOrganizer::class.java.classLoader, byteBuddyStrategy)
            .loaded
            .getDeclaredConstructor()
            .newInstance()
    }

    fun init() {
        if (registered) return
        runCatching {
            val organizerController = XposedHelpers.callMethod(
                Instances.activityTaskManager, "getWindowOrganizerController"
            )
            val taskOrganizerController = XposedHelpers.getObjectField(
                organizerController, "mTaskOrganizerController"
            )

            // 1) 捕获上一任 organizer（透明插入的关键参照）
            runCatching {
                val deque = XposedHelpers.getObjectField(taskOrganizerController, "mTaskOrganizers") as? java.util.ArrayDeque<*>
                previousOrganizer = deque?.peekLast()
                log(TAG, "previous organizer=${previousOrganizer}")
            }

            // 2) 先装选择性路由钩子——注册引发的全量认领必须按我们的规则路由
            hookTaskOrganizerRouting()

            // 3) 注册
            val appeared = XposedHelpers.callMethod(
                taskOrganizerController, "registerTaskOrganizer", organizer
            )
            registered = true
            log(TAG, "registered, already-organized=$appeared")

            // 4) 创建专属悬浮根（新 API：createTask(TaskCreationParams)）
            createFloatingRoot(taskOrganizerController)
        }.onFailure {
            log(TAG, "init failed", it)
        }
    }

    /**
     * 选择性路由：只有我们悬浮根子树里的任务归我们；其余任务（根任务、系统壳的任务等）
     * 强制保持上一任 organizer，避免注册把我们变成全局接管者。
     */
    private fun hookTaskOrganizerRouting() {
        runCatching {
            val taskClass = Instances.systemContext.classLoader.loadClass("com.android.server.wm.Task")
            findMethod(taskClass) {
                name == "setTaskOrganizer" && paramCount == 2
            }.hookBefore { param ->
                runCatching {
                    val task = param.thisObject
                    val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
                    val inOurs = isInOurSubtree(task) || pendingAdoption.contains(taskId)
                    val desired = if (inOurs) organizer else previousOrganizer
                    if (desired != null) {
                        if (param.args[0] !== desired) {
                            param.args[0] = desired
                        }
                    } else if (param.args[0] === organizer) {
                        // 没有上一任可归还时不抢（保持未组织状态，行为与注册前一致）
                        param.result = false
                    }
                }
            }
            log(TAG, "task organizer routing hook installed")
        }.onFailure {
            log(TAG, "routing hook install failed", it)
        }
    }

    private fun isInOurSubtree(task: Any): Boolean {
        if (floatingRootTaskId < 0) return false
        var current: Any? = task
        var guard = 0
        while (current != null && guard++ < 16) {
            val id = runCatching { XposedHelpers.getIntField(current, "mTaskId") }.getOrDefault(-1)
            if (id == floatingRootTaskId) return true
            current = runCatching { XposedHelpers.callMethod(current, "getParent") }.getOrNull()?.let { parent ->
                runCatching { XposedHelpers.callMethod(parent, "asTask") }.getOrNull()
            }
        }
        return false
    }

    private fun createFloatingRoot(taskOrganizerController: Any) {
        runCatching {
            val classLoader = Instances.systemContext.classLoader
            val builderClass = classLoader.loadClass("android.window.TaskCreationParams\$Builder")
            val builder = XposedHelpers.newInstance(builderClass)
            XposedHelpers.callMethod(builder, "setName", "reYAMF_floating_root")
            XposedHelpers.callMethod(builder, "setDisplayId", 0)
            XposedHelpers.callMethod(builder, "setWindowingMode", WINDOWING_MODE_FREEFORM)
            runCatching { XposedHelpers.callMethod(builder, "setLaunchCookie", Binder()) }
            val params = XposedHelpers.callMethod(builder, "build")

            val appearedInfo = XposedHelpers.callMethod(taskOrganizerController, "createTask", params)
            if (appearedInfo == null) {
                log(TAG, "createTask returned null")
                return
            }
            val taskInfo = XposedHelpers.callMethod(appearedInfo, "getTaskInfo") as ActivityManager.RunningTaskInfo
            val rawLeash = XposedHelpers.callMethod(appearedInfo, "getLeash") as SurfaceControl
            // 本地直调时 createTask 返回的 leash 不会被 WM 释放；仍统一收编为自持句柄并释放原句柄，
            // 保证全链路只有"我们自己的句柄"一种来源。
            val owned = ownLeash(rawLeash) ?: rawLeash
            if (owned !== rawLeash) releaseQuietly(rawLeash)
            floatingRootTaskId = taskInfo.taskId
            floatingRootToken = XposedHelpers.getObjectField(taskInfo, "token")
            leashes[floatingRootTaskId] = owned
            floatingRootLeash = owned
            log(TAG, "floating root created task=$floatingRootTaskId")

            // 显式让悬浮根归属我们：注册发生在 SystemUI(shell) 之前，且 isInOurSubtree 依赖
            // floatingRootTaskId（此时已写入），可稳定通过路由钩子；否则 root 会以"未 organized"
            // 状态存在——WCT.reorder 会被 sanitizeTransaction 剥掉、表面语义依靠 WM 默认行为。
            runCatching {
                val rootTask = resolveTaskByToken(floatingRootToken)
                if (rootTask != null) {
                    XposedHelpers.callMethod(rootTask, "updateTaskOrganizerState")
                    log(TAG, "floating root organizer state ensured")
                } else {
                    log(TAG, "floating root task object unresolved")
                }
            }.onFailure {
                log(TAG, "ensure floating root organizer state failed", it)
            }

            // 空根先隐藏（空 container 若在最上层会整屏渲染成黑色遮住一切），
            // 认领第一个窗口时才 reorder(onTop)+show。
            runCatching {
                val tx = SurfaceControl.Transaction()
                XposedHelpers.callMethod(tx, "hide", owned)
                tx.apply()
            }
        }.onFailure {
            log(TAG, "createFloatingRoot failed", it)
        }
    }

    /**
     * 把悬浮根 reorder 到显示最上层（浮窗"全局置顶在系统壳/其他应用之上"语义的官方姿势）。
     *
     * 关键：窗口任务被 reparent 进悬浮根后，`moveTaskToFront(子任务)` 只会在**根内部**重排，
     * 无法把承载根抬到 home/其他应用之上；实测 HOME 注入后根会被压到 home 之下，
     * 任务被判不可见（visibleRequested=false）→ 应用内容整体消失。
     */
    fun bringRootToFront(): Boolean {
        if (floatingRootTaskId < 0) return false
        val rootToken = floatingRootToken ?: return false
        return runCatching {
            val wct = newWct()
            XposedHelpers.callMethod(wct, "reorder", rootToken, true)
            applyWct(wct)
            log(TAG, "floating root reordered onTop")
            true
        }.onFailure {
            log(TAG, "bringRootToFront failed", it)
        }.getOrDefault(false)
    }

    private fun updateRootVisibility() {
        val rootTaskId = floatingRootTaskId
        val leash = floatingRootLeash ?: return
        if (rootTaskId < 0) return
        runCatching {
            val visible = adoptedTasks.isNotEmpty()
            val tx = SurfaceControl.Transaction()
            if (visible) {
                XposedHelpers.callMethod(tx, "show", leash)
            } else {
                XposedHelpers.callMethod(tx, "hide", leash)
            }
            tx.apply()
            log(TAG, "floating root visibility=$visible")
        }.onFailure {
            log(TAG, "updateRootVisibility failed", it)
        }
    }

    private fun handle(name: String, args: Array<Any?>) {
        when (name) {
            "onTaskAppeared" -> {
                val info = args.getOrNull(0) as? ActivityManager.RunningTaskInfo ?: return
                val leash = args.getOrNull(1) as? SurfaceControl ?: return
                val taskId = info.taskId
                // 必须在回调同步期内复制句柄：WM 在回调返回后立即 release 传入对象（见 ownLeash 注释）
                val owned = ownLeash(leash)
                when {
                    taskId == floatingRootTaskId -> {
                        if (owned != null) {
                            leashes.put(taskId, owned)?.let { old -> if (old !== owned) releaseQuietly(old) }
                            val prev = floatingRootLeash
                            floatingRootLeash = owned
                            if (prev != null && prev !== owned) releaseQuietly(prev)
                        }
                        log(TAG, "floating root leash appeared")
                    }
                    adoptedTasks.contains(taskId) -> {
                        if (owned != null) {
                            leashes.put(taskId, owned)?.let { old -> if (old !== owned) releaseQuietly(old) }
                        }
                        log(TAG, "onTaskAppeared task=$taskId (adopted)")
                        SmoothWindowBackend.notifyTaskAdopted(taskId)
                    }
                    else -> {
                        // 路由之后不应出现；防御：保持可见+单位变换
                        runCatching {
                            val tx = SurfaceControl.Transaction()
                            XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f)
                            XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f)
                            XposedHelpers.callMethod(tx, "show", leash)
                            tx.apply()
                        }
                        log(TAG, "onTaskAppeared task=$taskId (passthrough)")
                    }
                }
            }
            "onTaskVanished" -> {
                val info = args.getOrNull(0) as? ActivityManager.RunningTaskInfo ?: return
                val taskId = info.taskId
                if (adoptedTasks.remove(taskId)) {
                    leashes.remove(taskId)?.let { releaseQuietly(it) }
                    logicalBounds.remove(taskId)
                    originalParentTokens.remove(taskId)
                    updateRootVisibility()
                    if (releasingTasks.contains(taskId)) {
                        log(TAG, "onTaskVanished task=$taskId (releasing, skip notify)")
                    } else {
                        log(TAG, "onTaskVanished task=$taskId")
                        SmoothWindowBackend.notifyTaskVanished(taskId)
                    }
                }
            }
            "onTaskInfoChanged" -> {
                val info = args.getOrNull(0) as? ActivityManager.RunningTaskInfo ?: return
                if (adoptedTasks.contains(info.taskId)) {
                    log(TAG, "onTaskInfoChanged task=${info.taskId} visible=${info.isVisible}")
                }
            }
            else -> {
                // 过渡播放器等其它回调：多播列表设计，附加式，无需处理
            }
        }
    }

    /** 把已运行的任务认领进悬浮根（organized）。 */
    fun adoptTask(taskId: Int): Boolean {
        if (!registered) return false
        val rootToken = floatingRootToken
        if (rootToken == null) {
            log(TAG, "adoptTask deferred: floating root not ready")
            return false
        }
        return runCatching {
            val runningTasks = XposedHelpers.callMethod(
                Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
            ) as? List<*> ?: return false
            val info = runningTasks.find { XposedHelpers.getIntField(it, "taskId") == taskId } ?: return false
            val token = XposedHelpers.getObjectField(info, "token")
            val parentTaskId = runCatching { XposedHelpers.getIntField(info, "parentTaskId") }.getOrDefault(-1)
            if (parentTaskId != -1) {
                runningTasks.find { XposedHelpers.getIntField(it, "taskId") == parentTaskId }?.let {
                    originalParentTokens[taskId] = XposedHelpers.getObjectField(it, "token")
                }
            }

            val dm = Instances.systemContext.resources.displayMetrics
            val bounds = Rect(0, 0, dm.widthPixels, dm.heightPixels)

            // 关键顺序：reparent 的 WCT 在 applyTransaction 内同步触发
            // task.updateTaskOrganizerState → setTaskOrganizer → sendTaskAppeared（onTaskAppeared），
            // 因此必须先把 taskId 登记为 adopted 并写好逻辑 bounds，回调才能命中并保存 leash。
            adoptedTasks.add(taskId)
            logicalBounds[taskId] = bounds

            try {
                // A17 的 sanitizeTransaction 会静默剥掉一切指向"未 organized"容器的 WCT 操作
                // （WindowOrganizerController#sanitizeTransaction，Slog.wtf）：认领前必须确保任务
                // 已挂到 organizer 上。正常情况下 SystemUI(shell) 已组织全部 root 任务；否则
                // （systemReady 早期、shell 重启等）显式触发一次 updateTaskOrganizerState，
                // 由路由钩子经 pendingAdoption 把该任务临时路由给我们。
                val task = resolveTaskByToken(token)
                if (task != null) {
                    FreeformBackend.rememberTask(taskId, task)
                    if (!isTaskOrganized(task)) {
                        pendingAdoption.add(taskId)
                        try {
                            runCatching { XposedHelpers.callMethod(task, "updateTaskOrganizerState") }
                                .onFailure { log(TAG, "updateTaskOrganizerState failed task=$taskId", it) }
                        } finally {
                            pendingAdoption.remove(taskId)
                        }
                        if (!isTaskOrganized(task)) {
                            log(TAG, "ERROR adoptTask task=$taskId cannot be organized (no fallback), abort")
                            adoptedTasks.remove(taskId)
                            logicalBounds.remove(taskId)
                            return false
                        }
                    }
                } else {
                    log(TAG, "adoptTask task=$taskId: task object unresolved, proceeding")
                }

                val wct = newWct()
                XposedHelpers.callMethod(wct, "setWindowingMode", token, WINDOWING_MODE_FREEFORM)
                XposedHelpers.callMethod(wct, "setBounds", token, bounds)
                // LineageOS 24.0：reparent 为三参 (child, parent, onTop)
                XposedHelpers.callMethod(wct, "reparent", token, rootToken, true)
                XposedHelpers.callMethod(wct, "reorder", rootToken, true)
                runCatching { applyWct(wct) }.onFailure {
                    adoptedTasks.remove(taskId)
                    logicalBounds.remove(taskId)
                    throw it
                }
            } catch (t: Throwable) {
                adoptedTasks.remove(taskId)
                logicalBounds.remove(taskId)
                throw t
            }

            updateRootVisibility()
            log(TAG, "adoptTask task=$taskId bounds=$bounds root=$rootToken")
            true
        }.onFailure {
            log(TAG, "adoptTask failed task=$taskId", it)
        }.getOrDefault(false)
    }

    /** 释放：复位 leash 变换（句柄仍有效时）→ reparent 回原父级 + 回全屏 → 交还系统 organizer。 */
    fun releaseTask(taskId: Int): Boolean {
        if (!adoptedTasks.contains(taskId)) return false
        // 释放期间抑制 onTaskVanished 回灌：organizer 切换产生的 vanish 不是"任务被移除"，
        // 若据此回调宿主销毁窗口会与释放流程重入（曾把宿主拖入 legacy 兜底并清掉任务缓存）。
        releasingTasks.add(taskId)
        try {
            return runCatching {
                val origToken = originalParentTokens[taskId]
                val runningTasks = XposedHelpers.callMethod(
                    Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
                ) as? List<*> ?: return false
                val info = runningTasks.find { XposedHelpers.getIntField(it, "taskId") == taskId } ?: return false
                val token = XposedHelpers.getObjectField(info, "token")

                // 复位必须在 reparent 之前做：organizer 从我们切回系统侧时
                // WindowContainer#migrateToNewSurfaceControl 会重建任务表面（新表面无变换），
                // 但若路由判定组织者未变（极端时序）旧表面会保留，先把变换清干净保证回全屏干净。
                leashes[taskId]?.let { leash ->
                    runCatching {
                        val tx = SurfaceControl.Transaction()
                        XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f)
                        XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f)
                        XposedHelpers.callMethod(
                            tx, "setWindowCrop",
                            arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), leash, null
                        )
                        XposedHelpers.callMethod(tx, "setCornerRadius", leash, 0f)
                        XposedHelpers.callMethod(tx, "show", leash)
                        tx.apply()
                    }.onFailure { log(TAG, "reset leash failed task=$taskId", it) }
                }

                val wct = newWct()
                XposedHelpers.callMethod(wct, "setWindowingMode", token, 1 /* FULLSCREEN */)
                // 原父级为默认 TaskDisplayArea（parentTaskId=-1）时传 null：
                // WindowOrganizerController#sanitizeAndApplyHierarchyOpForTask 对 null 取默认 TDA。
                // null 实参无法走普通 varargs 解析，必须显式给出参数类型。
                val tokenClass = Instances.systemContext.classLoader
                    .loadClass("android.window.WindowContainerToken")
                XposedHelpers.callMethod(
                    wct, "reparent",
                    arrayOf(tokenClass, tokenClass, java.lang.Boolean.TYPE),
                    token, origToken, true
                )
                applyWct(wct)

                // 任务已离开我们的组织（organizer 变更时表面已迁移到新 SurfaceControl），释放自持句柄
                leashes.remove(taskId)?.let { releaseQuietly(it) }
                adoptedTasks.remove(taskId)
                logicalBounds.remove(taskId)
                originalParentTokens.remove(taskId)
                updateRootVisibility()
                log(TAG, "releaseTask task=$taskId")
                true
            }.onFailure {
                log(TAG, "releaseTask failed task=$taskId", it)
            }.getOrDefault(false)
        } finally {
            releasingTasks.remove(taskId)
        }
    }

    /** 计算并写入 leash 变换（等比缩放 + 居中 + crop=逻辑 bounds + 圆角），不 apply。 */
    private fun writeLeashTransform(
        tx: SurfaceControl.Transaction,
        taskId: Int,
        leash: SurfaceControl,
        logical: Rect,
        physical: Rect
    ): Boolean {
        if (logical.width() <= 0 || logical.height() <= 0) return false
        if (physical.width() <= 0 || physical.height() <= 0) return false
        val scale = min(
            physical.width().toFloat() / logical.width(),
            physical.height().toFloat() / logical.height()
        )
        if (scale <= 0f) return false
        val scaledW = logical.width() * scale
        val scaledH = logical.height() * scale
        val posX = physical.left + (physical.width() - scaledW) / 2f
        val posY = physical.top + (physical.height() - scaledH) / 2f
        val radius = YAMFManager.smoothCornerRadiusPx[taskId] ?: 0

        XposedHelpers.callMethod(tx, "setMatrix", leash, scale, 0f, 0f, scale)
        XposedHelpers.callMethod(tx, "setPosition", leash, posX, posY)
        XposedHelpers.callMethod(tx, "setWindowCrop", leash, logical)
        if (radius > 0) {
            XposedHelpers.callMethod(tx, "setCornerRadius", leash, radius / scale)
        }
        return true
    }

    /** 对窗口任务 leash 摆位：等比缩放（整屏→卡片）+ 居中 + crop=整屏 + 圆角。 */
    fun applyLeash(taskId: Int, physical: Rect) {
        val leash = leashes[taskId] ?: return
        val logical = logicalBounds[taskId] ?: return
        runCatching {
            val tx = SurfaceControl.Transaction()
            if (!writeLeashTransform(tx, taskId, leash, logical, physical)) return@runCatching
            XposedHelpers.callMethod(tx, "show", leash)
            tx.apply()
            val now = android.os.SystemClock.uptimeMillis()
            val last = lastLeashWriteLogAt[taskId] ?: 0L
            if (now - last >= 500L) {
                lastLeashWriteLogAt[taskId] = now
                log(TAG, "leash write task=$taskId physical=$physical logical=$logical")
            }
        }.onFailure {
            log(TAG, "applyLeash failed task=$taskId", it)
        }
    }

    /**
     * 稳态重施加（过渡结束后调用）。
     * WM 的 `Transition.buildFinishTransaction → resetSurfaceTransform` 会把任务表面
     * matrix 重置为单位阵、position 重置为 bounds 原点（已在 24.0 源码核对）；
     * shell 应用 finish 事务后必须由我们把 leash 变换重新写回，否则缩放会周期性被冲掉。
     */
    fun reapplyLeash(taskId: Int) {
        val physical = YAMFManager.smoothFreeformBounds[taskId] ?: return
        applyLeash(taskId, physical)
    }

    /** 把当前 leash 变换写入给定事务（供过渡 start/finish 事务合并使用）。 */
    fun writeLeashInto(tx: SurfaceControl.Transaction, taskId: Int): Boolean {
        val leash = leashes[taskId] ?: return false
        val logical = logicalBounds[taskId] ?: return false
        val physical = YAMFManager.smoothFreeformBounds[taskId] ?: return false
        return runCatching { writeLeashTransform(tx, taskId, leash, logical, physical) }
            .getOrDefault(false)
    }

    fun showLeash(taskId: Int) {
        val leash = leashes[taskId] ?: return
        runCatching {
            val tx = SurfaceControl.Transaction()
            XposedHelpers.callMethod(tx, "show", leash)
            tx.apply()
        }
    }

    fun forgetTask(taskId: Int) {
        adoptedTasks.remove(taskId)
        leashes.remove(taskId)?.let { releaseQuietly(it) }
        logicalBounds.remove(taskId)
        originalParentTokens.remove(taskId)
    }

    private fun newWct(): Any {
        val wctClass = Instances.systemContext.classLoader.loadClass("android.window.WindowContainerTransaction")
        return XposedHelpers.newInstance(wctClass)
    }

    private fun applyWct(wct: Any) {
        val organizerController = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getWindowOrganizerController"
        )
        XposedHelpers.callMethod(organizerController, "applyTransaction", wct)
    }
}
