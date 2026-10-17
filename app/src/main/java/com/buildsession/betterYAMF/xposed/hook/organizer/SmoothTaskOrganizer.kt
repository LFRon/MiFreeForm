package com.buildsession.betterYAMF.xposed.hook.organizer

import android.app.ActivityManager
import android.graphics.Rect
import android.os.Binder
import android.view.SurfaceControl
import android.window.ITaskOrganizer
import com.buildsession.betterYAMF.xposed.hook.freeform.FreeformBackend
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

    /** TaskOrganizerController 引用（init 时保存；解析"另一个 organizer"需要查询其注册表） */
    @Volatile
    private var organizerControllerRef: Any? = null

    /** 悬浮根是否已取得系统级置顶身份（always-on-top）：生效后宿主不得再做抢层动作。 */
    @Volatile
    private var rootAlwaysOnTop = false

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

    /** 承载根是否已具备 always-on-top 置顶身份（生效后宿主应让路，不再抢层）。 */
    fun isRootAlwaysOnTop(): Boolean = rootAlwaysOnTop

    /**
     * 承载根的 WindowContainerToken：启动时注入 `ActivityOptions#setLaunchRootTask`，
     * 让应用任务**出生即在根下**（容器兜底路径的任务归属语义；小窗不改应用 DPI）。
     * 根未就绪时返回 null，调用方退回默认启动路径（认领兜底）。
     */
    fun launchRootToken(): Any? = floatingRootToken

    /** 该任务是否已处于承载根子树（出生即入根或已认领）；供"重启进入会话"判定。 */
    fun isTaskInFloatingRoot(taskInfo: ActivityManager.RunningTaskInfo): Boolean = runCatching {
        // token 为隐藏字段（SDK 存根不含），统一走反射（与本模块既有风格一致）
        val token = XposedHelpers.getObjectField(taskInfo, "token") ?: return false
        val task = resolveTaskByToken(token) ?: return false
        isInOurSubtree(task)
    }.getOrDefault(false)

    /** 受管任务 id（承载根 + 已认领子任务）：供 Launcher 手势目标过滤同步（M6 批次2）。 */
    fun managedTaskIds(): IntArray {
        val ids = ArrayList<Int>(adoptedTasks.size + 1)
        if (floatingRootTaskId > 0) ids.add(floatingRootTaskId)
        ids.addAll(adoptedTasks)
        return ids.toIntArray()
    }

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
            organizerControllerRef = taskOrganizerController

            // 1) 捕获上一任 organizer（透明插入的关键参照）
            runCatching {
                val deque = XposedHelpers.getObjectField(taskOrganizerController, "mTaskOrganizers") as? java.util.ArrayDeque<*>
                previousOrganizer = deque?.peekLast()
                log(TAG, "previous organizer=${previousOrganizer}")
            }

            // 2) 先装选择性路由钩子——注册引发的全量认领必须按我们的规则路由
            hookTaskOrganizerRouting()

            // 2.5) 圆角 opt 源头闸——禁止系统转场收尾恢复我方任务的客户端自绘
            hookRoundedCornerOptCleanup()

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
     * 选择性路由（LoS 24 `Task.setTaskOrganizer(org, skip)` :4540 语义对齐）：
     *
     * 1. **我方子树任务**：确保归我方 organizer——注册横扫把 `getTaskOrganizer()=peekLast()`
     *    （=最后注册者，本机为我方）派给全部任务，我方子树必须在系统改动（配置变更等）后
     *    仍被拉回我方；
     * 2. **系统把"非我方任务"派给我方**（注册横扫 / 我们是 peekLast 的效应）：交还给
     *    **当前仍在册的最近一个其他 organizer**（动态解析）——绝不写回"开机时捕获一次的
     *    旧对象"：SystemUI 重启后旧 ShellTaskOrganizer 已失效，旧实现会把新任务/系统
     *    重新注册全部劫持到死对象上（新壳簿记残缺 → 概率性异常）；
     * 3. **其余调用**（系统正常设置其他 organizer / 置空 setTaskOrganizer(null)）一律不干预。
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
                    val assigned = param.args[0]
                    when {
                        inOurs -> {
                            if (assigned !== organizer) {
                                param.args[0] = organizer
                            }
                        }
                        assigned === organizer -> {
                            val other = resolveShellOrganizer()
                            if (other != null) {
                                param.args[0] = other
                            } else {
                                // 无其他可归还者（壳尚未注册等）：保持任务原组织状态，
                                // 行为与注册前一致
                                param.result = false
                            }
                        }
                    }
                }
            }
            log(TAG, "task organizer routing hook installed")
        }.onFailure {
            log(TAG, "routing hook install failed", it)
        }
    }

    /**
     * 圆角 opt 源头闸：系统转场收尾会遍历 `mRoundedCornerOptTasks` 把 opt 恢复为 true
     * （TransitionController#cleanupRoundedCornerDisableOptTasks → task.getSyncTransaction()，
     * 延迟冲刷、可能晚于我们的即时写入）。我方名下会话任务的 opt 必须恒为 false
     * （否则应用自绘圆角复活 → 旧"镜像残留"类 bug），因此在登记点拦截：
     * 已 adopted 的任务不进系统的"待恢复 true"集合——系统转场开始时的 false
     * （Transition.java:2163）照常保留，等于把系统语义收敛成"只关不开"。
     */
    private fun hookRoundedCornerOptCleanup() {
        runCatching {
            val controllerClass = Instances.systemContext.classLoader
                .loadClass("com.android.server.wm.TransitionController")
            findMethod(controllerClass) {
                name == "onRoundedCornerOptDisabled" && paramCount == 1
            }.hookBefore { param ->
                runCatching {
                    val task = param.args[0] ?: return@runCatching
                    val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }
                        .getOrDefault(-1)
                    if (taskId >= 0 && adoptedTasks.contains(taskId)) {
                        // 跳过原方法 = 不登记 → 收尾不会对我们恢复 true
                        param.result = null
                    }
                }
            }
            log(TAG, "rounded-corner opt gate hook installed")
        }.onFailure {
            log(TAG, "rounded-corner opt gate hook install failed", it)
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

            // 置顶身份：把承载根声明为 always-on-top（TaskDisplayArea 优先级 1——低于 PiP/Dream、
            // 高于一切普通任务）。系统层序规则（getPriority + findPositionForRootTask 夹取）
            // 保证 home/recents/新应用前置时都只能插到我们之下：浮窗"全局置顶"从此是
            // 一次声明的身份，而不是每次抢层（reorder/moveTaskToFront）——后者正是此前与
            // recents 手势/过渡互相打断的根源。宿主侧 keepAboveHome 会据此让路。
            runCatching {
                val token = floatingRootToken
                if (token != null) {
                    val wct = newWct()
                    val tokenClass = Instances.systemContext.classLoader
                        .loadClass("android.window.WindowContainerToken")
                    XposedHelpers.callMethod(
                        wct, "setAlwaysOnTop",
                        arrayOf(tokenClass, java.lang.Boolean.TYPE), token, true
                    )
                    // "重新启动即摘出会话根"（平台官方语义，TaskDisplayArea#getOrCreateRootTask:821-848
                    // 消费该身份：reparentToTda 判定 → candidateTask.reparent(默认TDA)）：
                    // - mReparentLeafTaskIfRelaunch：任意来源 relaunch 根内任务 → 自动摘回默认 TDA
                    //   （= 退出小窗回全屏）；
                    // - ...FromHome：桌面（最近任务点击/图标点击等）发起的 relaunch 同样生效。
                    // 随后由 onTaskVanished → 宿主 onDestroy → releaseTask 收尾（回全屏迁移），
                    // 实现"最近任务点一下 = 结束小窗、切回应用本体"。
                    XposedHelpers.callMethod(
                        wct, "setReparentLeafTaskIfRelaunch",
                        arrayOf(tokenClass, java.lang.Boolean.TYPE), token, true
                    )
                    XposedHelpers.callMethod(
                        wct, "setReparentLeafTaskIfRelaunchFromHome",
                        arrayOf(tokenClass, java.lang.Boolean.TYPE), token, true
                    )
                    applyWct(wct)
                    rootAlwaysOnTop = true
                    log(TAG, "floating root always-on-top + reparentIfRelaunch applied task=$floatingRootTaskId")
                }
            }.onFailure {
                log(TAG, "floating root always-on-top failed", it)
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
            // 认领前的子任务（启动即入根）也在根内，需要根可见（否则启动画面被吞）。
            val visible = adoptedTasks.isNotEmpty() || rootHasChildren()
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

    /** 承载根下是否已有子任务（认领前的启动任务也计入；根可见性判定用）。 */
    private fun rootHasChildren(): Boolean = runCatching {
        val task = resolveTaskByToken(floatingRootToken) ?: return false
        (XposedHelpers.callMethod(task, "getChildCount") as? Int ?: 0) > 0
    }.getOrDefault(false)

    /**
     * 解析注册表里"另一个" organizer（通常是系统壳 ShellTaskOrganizer）。
     *
     * `TaskOrganizerController` 对 starting window / 锁屏遮挡 / 包更新 / 过渡播放等**全局回调**
     * 按 `getTaskOrganizer() = mTaskOrganizers.peekLast()` 派发——谁最后注册谁收。本机实测
     * 我们的 organizer 最后注册（dumpsys 中带 `(active)`），这些回调全部落到我们头上；
     * 我们没有（也不该有）Splash 渲染管线，必须原样转交系统壳，否则冷启动 Splash 消失。
     */
    private fun resolveShellOrganizer(): Any? = runCatching {
        val controller = organizerControllerRef ?: return null
        val deque = XposedHelpers.getObjectField(controller, "mTaskOrganizers")
            as? java.util.ArrayDeque<*> ?: return null
        deque.toList().asReversed().firstOrNull { it != null && it !== organizer }
    }.getOrNull()

    /** 把"最后注册者语义"的全局回调原样转交系统壳（见 [resolveShellOrganizer]）。 */
    private fun forwardToShell(name: String, args: Array<Any?>) {
        val shell = resolveShellOrganizer() ?: previousOrganizer
        if (shell == null) {
            log(TAG, "forward $name skipped: no shell organizer")
            return
        }
        runCatching {
            XposedHelpers.callMethod(shell, name, *args)
            log(TAG, "forward $name -> shell")
        }.onFailure {
            log(TAG, "forward $name failed", it)
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
                        // 启动即入根（ActivityOptions#setLaunchRootTask）后，任务出生即在承载根内，
                        // onTaskAppeared 会**早于认领**到达（这是常态路径）：先收编 leash（WM 在
                        // 回调返回后立即 release 传入对象，见 ownLeash 注释），并让根可见——启动
                        // 闪屏/首帧都在根内，根隐藏会吞掉启动画面。认领后的摆位回灌见 adoptTask 末尾。
                        if (owned != null) {
                            leashes.put(taskId, owned)?.let { old -> if (old !== owned) releaseQuietly(old) }
                        }
                        updateRootVisibility()
                        log(TAG, "onTaskAppeared task=$taskId (pre-adopt, leash cached)")
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
            // 全局回调（系统按"最后注册的 organizer"= peekLast 派发）：原样转交系统壳。
            // 不转交的后果（本 bug 实测根因）：全系统冷启动 Splash 消失——Splash 由系统壳的
            // StartingWindowController 渲染，这些回调被派发到我们（当前最后注册）后被静默丢弃。
            // 若我们不是最后注册者，这些回调本来就直接发给系统壳，这里不会触发。
            "addStartingWindow", "removeStartingWindow", "copySplashScreenView",
            "onAppSplashScreenViewRemoved", "onImeDrawnOnTask", "onBackOnTaskRoot",
            "onKeyguardOccludingTaskChanged",
            "onPackageUpdateRequested", "onPackageUpdateFinished",
            "onTransitionReady", "requestStartTransition" -> forwardToShell(name, args)
            else -> {
                // 其余未知回调：忽略。新增 AIDL 方法时需评估是否属于"全局回调"而需要转交。
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
            // 启动即入根（ActivityOptions#setLaunchRootTask）时父级就是承载根本身：不能把它记为
            // "原父级"，否则释放（回全屏）会把任务又塞回我们根里。回落默认 TaskDisplayArea（null）。
            if (parentTaskId != -1 && parentTaskId != floatingRootTaskId) {
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
                // 会话期间任务切为 FREEFORM：让 WM 知道"承载任务不是全屏遮挡窗口"，
                // 从而保持 后方 Home/壁纸 可见（否则从小窗打开时背景被 WM 隐藏成黑虚空，
                // 真机实证）。圆角病根（A15+ 客户端自绘圆角 + 状态滞留）由
                // toggleClientDrawnRoundedCornersOpt(false) 关掉自绘、交给 SF 裁剪根治，
                // 见下方启用块与 writeLeashTransform 注释。
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

                // ——— 关闭"客户端自绘圆角"优化（A17 原生开关，源码核实）———
                // SurfaceControl.Transaction#toggleClientDrawnRoundedCornersOpt(sc, enable)：
                // enable=false → 圆角裁剪由 SurfaceFlinger 完成、应用不再自绘（系统转场期
                // 同款用法，见 Transition.java:2163；转场收尾由 TransitionController
                // #cleanupRoundedCornerDisableOptTasks 恢复 true）。由此 leash 上的圆角
                // 写入 = SF 真裁剪（透明圆角、壁纸透出），应用侧零圆角状态 → 会话结束零残留。
                runCatching {
                    val surface = resolveTaskByToken(token)?.let {
                        runCatching {
                            XposedHelpers.callMethod(it, "getSurfaceControl") as? SurfaceControl
                        }.getOrNull()
                    } ?: leashes[taskId]
                    if (surface != null) {
                        val tx = SurfaceControl.Transaction()
                        XposedHelpers.callMethod(
                            tx, "toggleClientDrawnRoundedCornersOpt", surface, false
                        )
                        tx.apply()
                        log(TAG, "corner opt: client-drawn disabled (SF crop) task=$taskId")
                    } else {
                        log(TAG, "corner opt: no surface to disable client-drawn task=$taskId")
                    }
                }.onFailure { log(TAG, "corner opt disable failed task=$taskId", it) }
            } catch (t: Throwable) {
                adoptedTasks.remove(taskId)
                logicalBounds.remove(taskId)
                throw t
            }

            updateRootVisibility()
            log(TAG, "adoptTask task=$taskId bounds=$bounds root=$rootToken")
            // 认领完成回灌：leash 已就绪，通知宿主按当前几何摆位并淡入。
            // 启动即入根流程下 onTaskAppeared 早于认领（不会随认领再触发 adopted 分支），统一在此
            // 点火；重复点火幂等（AppWindow#onOrganizerLeash 的重摆位/淡入均幂等）。
            SmoothWindowBackend.notifyTaskAdopted(taskId)
            true
        }.onFailure {
            log(TAG, "adoptTask failed task=$taskId", it)
        }.getOrDefault(false)
    }

    /**
     * 释放：复位 leash 变换（句柄仍有效时）→ reparent 回原父级 + 回全屏 → 交还系统 organizer。
     * [toFront]：reparent 落栈位置——true 置顶（下拉全屏）；false 沉到原父级栈底
     * （关闭小窗：任务转后台、留在最近任务，不抢前台；表面重建帧被上层任务遮挡）。
     */
    fun releaseTask(taskId: Int, toFront: Boolean = true): Boolean {
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
                val info = runningTasks.find { XposedHelpers.getIntField(it, "taskId") == taskId } ?: run {
                    // 任务已不存在（被划掉/进程死）：本地登记必须一并清掉，
                    // 否则 adopted/leash 永久悬挂 → 后续路由与点按全部异常。
                    forgetTask(taskId)
                    updateRootVisibility()
                    return false
                }
                val token = XposedHelpers.getObjectField(info, "token")

                // 平台"重新启动即摘根"通路（TaskDisplayArea 摘根，如最近任务/图标点击）下，
                // 任务在进入本方法前已离开我方根：此时若仍按 toFront=false 沉底，会把
                // "用户点击/通知打开"的意图压到 home 之下（表现为"应用退出"）。
                // 已摘根的场景一律保持在前（onTop=true）语义。
                val taskObj = resolveTaskByToken(token)
                val alreadyOutOfRoot = taskObj != null && !isInOurSubtree(taskObj)
                val effectiveToFront = toFront || alreadyOutOfRoot

                // 复位必须在 reparent 之前做：organizer 从我们切回系统侧时
                // WindowContainer#migrateToNewSurfaceControl 会重建任务表面（新表面无变换），
                // 但若路由判定组织者未变（极端时序）旧表面会保留，先把变换清干净保证回全屏干净。
                // 逐步容错：任一步骤异常不得阻断 tx.apply（否则复位整体丢失，圆角/裁剪残留——真机实证）。
                leashes[taskId]?.let { leash ->
                    val tx = SurfaceControl.Transaction()
                    runCatching { XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f) }
                    runCatching { XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f) }
                    runCatching {
                        XposedHelpers.callMethod(
                            tx, "setWindowCrop",
                            arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), leash, null
                        )
                    }
                    runCatching { XposedHelpers.callMethod(tx, "setCornerRadius", leash, 0f) }
                    // 恢复系统默认：客户端自绘圆角开关复位（同 TransitionController
                    // #cleanupRoundedCornerDisableOptTasks 的转场收尾语义）
                    runCatching {
                        val surface = resolveTaskByToken(token)?.let {
                            runCatching {
                                XposedHelpers.callMethod(it, "getSurfaceControl") as? SurfaceControl
                            }.getOrNull()
                        } ?: leash
                        XposedHelpers.callMethod(
                            tx, "toggleClientDrawnRoundedCornersOpt", surface, true
                        )
                    }
                    // 沉后台场景（effectiveToFront=false）：复位期间保持隐藏，
                    // 避免"复位→reparent"之间以原始变换闪现一帧；回前场景保持可见。
                    if (effectiveToFront) {
                        runCatching { XposedHelpers.callMethod(tx, "show", leash) }
                    } else {
                        runCatching { XposedHelpers.callMethod(tx, "hide", leash) }
                    }
                    runCatching { tx.apply() }.onFailure { log(TAG, "reset leash apply failed task=$taskId", it) }
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
                    token, origToken, effectiveToFront
                )
                applyWct(wct)

                // reparent 之后：对任务"当前表面"再显式复位一次——organizer 交接未重建表面
                // （或极端时序下旧表面保留）时，保证回全屏的窗口不带我们的变换/圆角残留。
                resetTaskSurfaceNow(taskId, token)

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

    /** 对任务"当前表面"做一次显式复位（matrix/position/crop/cornerRadius/shadowRadius），幂等。 */
    private fun resetTaskSurfaceNow(taskId: Int, tokenOverride: Any? = null): Boolean {
        return runCatching {
            val token = tokenOverride ?: run {
                val runningTasks = XposedHelpers.callMethod(
                    Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
                ) as? List<*> ?: return false
                val info = runningTasks.find { XposedHelpers.getIntField(it, "taskId") == taskId } ?: return false
                XposedHelpers.getObjectField(info, "token")
            }
            val taskObj = resolveTaskByToken(token) ?: return false
            val sc = XposedHelpers.callMethod(taskObj, "getSurfaceControl") as? SurfaceControl ?: return false
            val scName = runCatching { sc.toString() }.getOrNull() ?: "?"
            val tx = SurfaceControl.Transaction()
            // 逐步容错：任一步骤异常不得阻断 tx.apply（否则复位整体丢失）。
            runCatching { XposedHelpers.callMethod(tx, "setMatrix", sc, 1f, 0f, 0f, 1f) }
            runCatching { XposedHelpers.callMethod(tx, "setPosition", sc, 0f, 0f) }
            runCatching {
                XposedHelpers.callMethod(
                    tx, "setWindowCrop",
                    arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), sc, null
                )
            }
            runCatching { XposedHelpers.callMethod(tx, "setCornerRadius", sc, 0f) }
            runCatching { XposedHelpers.callMethod(tx, "setShadowRadius", sc, 0f) }
            // 恢复系统默认的客户端自绘圆角开关（表面重建后仍兜底复位）
            runCatching { XposedHelpers.callMethod(tx, "toggleClientDrawnRoundedCornersOpt", sc, true) }
            tx.apply()
            log(TAG, "surface reset task=$taskId sc=$scName")
            true
        }.onFailure { log(TAG, "surface reset failed task=$taskId", it) }.getOrDefault(false)
    }

    /** 计算并写入 leash 变换（非等比拉伸铺满 + crop=逻辑 bounds + 圆角复位 0），不 apply。 */
    private fun writeLeashTransform(
        tx: SurfaceControl.Transaction,
        taskId: Int,
        leash: SurfaceControl,
        logical: Rect,
        physical: Rect
    ): Boolean {
        if (logical.width() <= 0 || logical.height() <= 0) return false
        if (physical.width() <= 0 || physical.height() <= 0) return false
        // 非等比缩放：应用按窗口比例拉伸铺满（用户裁定“不需要保持宽高比”）。
        // 旧实现为等比 contain（min 比例）——窗口比例≠应用比例时会产生黑边，已废弃。
        val scaleX = physical.width().toFloat() / logical.width()
        val scaleY = physical.height().toFloat() / logical.height()
        if (scaleX <= 0f || scaleY <= 0f) return false
        val scaledW = logical.width() * scaleX
        val scaledH = logical.height() * scaleY
        val posX = physical.left + (physical.width() - scaledW) / 2f
        val posY = physical.top + (physical.height() - scaledH) / 2f

        XposedHelpers.callMethod(tx, "setMatrix", leash, scaleX, 0f, 0f, scaleY)
        XposedHelpers.callMethod(tx, "setPosition", leash, posX, posY)
        XposedHelpers.callMethod(tx, "setWindowCrop", leash, logical)
        // 圆角 = 0（应用本体方角，永不自绘、不裁剪）：可见圆角一律由宿主外壳装饰提供 ——
        // 点击态=方角内容 + 顶/底栏手势（"可点击时不需要圆角"）；mini=贴边边框环盖住应用
        // 方角、内沿圆角即视觉圆角（对齐澎湃OS"应用内没有圆角、圆角是装饰加的"模型）。
        // 应用侧自绘圆角仍由 adoptTask 的 toggleClientDrawnRoundedCornersOpt(false) 关闭。
        // ⚠ 铁律（历史教训，勿删）：会话期间"客户端自绘圆角"必须保持关闭，否则应用会把
        // 圆角镜像为自身像素级裁剪且清不掉（残角值跟随写入值、活体证据 630→212px），
        // 只能旋转/重启应用消除。开关不放这里逐帧写（性能）：系统语义 = 转场作用域状态机
        // （转场开始 false、空闲收尾 true），且已用 TransitionController 源头闸拦掉对我方
        // 任务的"恢复 true"，再配合 adopt / reapplyLeash / release 三个状态点写入即可。
        // 局部容错：圆角复位失败不得阻断 matrix/crop 的几何落地。
        runCatching { XposedHelpers.callMethod(tx, "setCornerRadius", leash, 0f) }
            .onFailure { log(TAG, "leash corner reset failed task=$taskId", it) }
        return true
    }

    /**
     * 静止态：任务真实 bounds = 窗口矩形 —— WCT setBounds（应用按窗口尺寸自适应重排，
     * 不拉伸快照），并把 leash 复位为"表面 = bounds"（矩阵单位/位置=原点/裁切清除/圆角 0）。
     * 之后 applyLeash 的缩放以新 logical（本 bounds 尺寸）为基准；两者互斥使用。
     */
    fun applyRealBounds(taskId: Int, physical: Rect): Boolean {
        val task = FreeformBackend.taskById(taskId) ?: run {
            log(TAG, "applyRealBounds: task not found task=$taskId")
            return false
        }
        return runCatching {
            // ⚠ WindowContainerToken 必须取自 RunningTaskInfo.token：
            // 服务端 Task#getToken() 恒为 null（仅远端镜像持有 mRemoteToken）——
            // 曾致本方法全程静默 return false、真实重排从未生效（真机 logcat 实证）。
            val info = taskInfoById(taskId) ?: run {
                log(TAG, "applyRealBounds: no taskInfo task=$taskId")
                return false
            }
            val token = XposedHelpers.getObjectField(info, "token") ?: run {
                log(TAG, "applyRealBounds: no token task=$taskId")
                return false
            }
            val current = runCatching { XposedHelpers.callMethod(task, "getBounds") as? Rect }.getOrNull()
            logicalBounds[taskId] = Rect(0, 0, physical.width(), physical.height())

            if (current == physical) {
                // 任务 bounds 已就位：只需普通事务把 leash 从缩放态复位（幂等，无中间帧）
                resetLeashToBounds(taskId, physical)
                return true
            }

            val leash = leashes[taskId]
            val wct = newWct()
            if (leash != null) {
                // ⚠ 关键（LoS 24.0 源码对齐）：复位必须与 bounds 变更**原子同步**应用。
                // WindowContainerTransaction#setBoundsChangeTransaction 语义："the server will
                // apply [the transaction] in sync with the next bounds change"（见 WOC
                // applyChanges → tr.setMainWindowSizeChangeTransaction）。
                // 旧实现（WCT 异步落 bounds + 立即普通事务复位）会产生中间帧：
                // "全屏表面 + 已复位变换" = 全屏闪帧，随后跳回窗口 —— 真机"拉伸↔全屏横跳"根因。
                val reset = SurfaceControl.Transaction()
                XposedHelpers.callMethod(reset, "setMatrix", leash, 1f, 0f, 0f, 1f)
                XposedHelpers.callMethod(reset, "setPosition", leash, physical.left.toFloat(), physical.top.toFloat())
                XposedHelpers.callMethod(
                    reset, "setWindowCrop",
                    arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), leash, null
                )
                XposedHelpers.callMethod(reset, "setCornerRadius", leash, 0f)
                XposedHelpers.callMethod(wct, "setBoundsChangeTransaction", token, reset)
            }
            XposedHelpers.callMethod(wct, "setBounds", token, physical)
            applyWct(wct)
            // bounds 落点：无任何密度覆盖——应用密度恒为默认显示器值（小窗不改应用 DPI）。
            log(TAG, "real bounds task=$taskId rect=$physical (no density override)")
            true
        }.onFailure { log(TAG, "applyRealBounds failed task=$taskId rect=$physical", it) }
            .getOrDefault(false)
    }

    /** 取任务 RunningTaskInfo（token 的唯一可靠来源；与 release 路径同一取法）。 */
    private fun taskInfoById(taskId: Int): Any? = runCatching {
        val runningTasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: return null
        runningTasks.find { XposedHelpers.getIntField(it, "taskId") == taskId }
    }.getOrNull()

    /** leash 复位到静止态：表面即 bounds（幂等；过渡收尾亦可重申）。 */
    fun resetLeashToBounds(taskId: Int, physical: Rect) {
        val leash = leashes[taskId] ?: return
        runCatching {
            val tx = SurfaceControl.Transaction()
            XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f)
            XposedHelpers.callMethod(tx, "setPosition", leash, physical.left.toFloat(), physical.top.toFloat())
            XposedHelpers.callMethod(
                tx, "setWindowCrop",
                arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), leash, null
            )
            XposedHelpers.callMethod(tx, "setCornerRadius", leash, 0f)
            tx.apply()
        }.onFailure { log(TAG, "resetLeashToBounds failed task=$taskId", it) }
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
        val physical = SmoothWindowBackend.geometryOf(taskId) ?: return
        applyLeash(taskId, physical)
        // 转场收尾重施加（系统语义：cleanupRoundedCornerDisableOptTasks 会在转场空闲后把
        // opt 恢复 true；认领前被登记、或源头闸尚未生效的残余路径仍可能翻回）。目标表面
        // 与系统保持一致 = 任务当前表面（task.getSurfaceControl()），解析失败退回 leash。
        runCatching {
            val surface = runCatching {
                FreeformBackend.taskById(taskId)?.let {
                    XposedHelpers.callMethod(it, "getSurfaceControl") as? SurfaceControl
                }
            }.getOrNull() ?: leashes[taskId]
            if (surface != null) {
                val tx = SurfaceControl.Transaction()
                XposedHelpers.callMethod(tx, "toggleClientDrawnRoundedCornersOpt", surface, false)
                tx.apply()
            }
        }
    }

    /** 把当前 leash 变换写入给定事务（供过渡 start/finish 事务合并使用）。 */
    fun writeLeashInto(tx: SurfaceControl.Transaction, taskId: Int): Boolean {
        val leash = leashes[taskId] ?: return false
        val logical = logicalBounds[taskId] ?: return false
        val physical = SmoothWindowBackend.geometryOf(taskId) ?: return false
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
