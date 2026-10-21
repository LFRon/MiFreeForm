package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import android.app.ActivityManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.app.TaskInfo
import android.util.DisplayMetrics
import android.view.Display
import android.view.SurfaceControl
import android.window.ITaskOrganizer
import com.buildsession.betterYAMF.window.api.WindowRect
import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.adapters.common.system.ShellOwnershipServer
import com.buildsession.betterYAMF.xposed.adapters.common.system.EntryProtocolIdentity
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.logError
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
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
import kotlin.math.roundToInt

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
 * 1. 按原生登记表动态解析系统 organizer；
 * 2. 先钩 `Task.setTaskOrganizer(ITaskOrganizer, boolean)` 做**选择性路由**：
 *    只有我们悬浮根子树的任务归我们，其余任务强制保持上一任（系统壳行为不变）；
 * 3. 再用 `createTask(TaskCreationParams)` 建专属悬浮根，`WCT.reorder(onTop)`；
 * 4. 窗口任务 reparent 进根 → 成为 organized → `onTaskAppeared(taskInfo, leash)` 交给我们摆位。
 */
object SmoothTaskOrganizer {
    private const val TAG = "reYAMF_Organizer"
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val UNAVAILABLE_REPORT_INTERVAL_MS = 5000L

    private val byteBuddyStrategy =
        AndroidClassLoadingStrategy.Wrapping(File("/data/system/reYAMF").also { it.mkdirs() })

    @Volatile
    var registered = false
        private set
    private var routingInstalled = false
    private var markerInstalled = false
    private var cornerGateInstalled = false
    private var initializing = false
    private var retry: Runnable? = null
    private var failures = 0
    @Volatile private var initialization = "not-started"
    private var reportedInitialization = ""
    private var lastInitializationFailure: Throwable? = null
    private var rootInitializationStep = "not-started"
    private var lastUnavailableReportAt: Long? = null

    val entryReady: Boolean get() = registered && routingInstalled && markerInstalled && cornerGateInstalled &&
        canStageInitialPresentation && rootAlwaysOnTop &&
        ShellOwnershipServer.ready && floatingRootToken != null && floatingRootLeash?.isValid == true
    fun readiness(): String = "$initialization epoch=${EntryProtocolIdentity.epoch} registered=$registered routing=$routingInstalled marker=$markerInstalled " +
        "staging=$canStageInitialPresentation corner=$cornerGateInstalled shell=${ShellOwnershipServer.ready} root=$floatingRootTaskId " +
        "rootStep=$rootInitializationStep token=${floatingRootToken != null} leash=${floatingRootLeash?.isValid == true} alwaysOnTop=$rootAlwaysOnTop"

    /** Query-time replay: boot errors may already have fallen out of logd's main ring buffer.
     * Read cached resource facts only; a Launcher DOWN must not retry WM initialization.
     */
    fun reportEntryUnavailable() {
        if (entryReady) return
        val now = android.os.SystemClock.uptimeMillis()
        if (lastUnavailableReportAt?.let { now - it < UNAVAILABLE_REPORT_INTERVAL_MS } == true) return
        lastUnavailableReportAt = now
        logWarning(TAG, "entry capability unavailable: ${readiness()} shellState=${ShellOwnershipServer.readiness()} " +
            "build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}",
            ShellOwnershipServer.failureCause() ?: lastInitializationFailure)
    }

    private class RegistrationRead(val toUs: Boolean, val previous: RegistrationRead?) {
        val leashes = RegistrationLeases<SurfaceControl>(::releaseQuietly)
    }
    private val registrationRead = ThreadLocal<RegistrationRead?>()

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
    private class InitialPresentation(val physical: Rect) {
        @Volatile var surface: SurfaceControl? = null
        @Volatile var fencedSurface: SurfaceControl? = null
        // WM's resize transaction committed; this is not a native buffer-content probe.
        @Volatile var resizeCommitted = false
    }
    private val initialPresentations = ConcurrentHashMap<Int, InitialPresentation>()
    @Volatile var canStageInitialPresentation = false
        private set

    fun cancelInitialPresentation(taskId: Int) { initialPresentations.remove(taskId) }

    /** LoS SurfaceControl:4088 supports fractional crop; probe once for older platforms. */
    private val fractionalCrop by lazy {
        XposedHelpers.findMethodExactIfExists(SurfaceControl.Transaction::class.java, "setCrop",
            SurfaceControl::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).also {
            if (it == null) logWarning(TAG, "fractional crop unavailable; viewport edges use integer crop precision")
        }
    }

    /**
     * 已塑形任务（ColorOS 16 缩放逻辑的关键状态）：首次静止结算已把任务真实 bounds
     * 固定为"虚拟尺寸"——应用据此布局一次；此后同一任务的一切尺寸变化只做表面等比
     * 缩放（leash），bounds 不再改动（应用零重排、无损放大缩小）。
     * 释放 / 任务消失 / 重新认领即失效。
     */
    private val shapedTasks = ConcurrentHashMap.newKeySet<Int>()

    private val originalParentTokens = ConcurrentHashMap<Int, Any>()
    private val overrideLeases = ConcurrentHashMap<Int, TaskOverrideLease>()
    private val adoptedTasks = ConcurrentHashMap.newKeySet<Int>()
    private val adoptionsInProgress = ConcurrentHashMap.newKeySet<Int>()
    private val callbackHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 待认领任务：认领前需要先把任务挂到 organizer 上（A17 的 WCT 只接受 organized 容器） */
    private val pendingAdoption = ConcurrentHashMap.newKeySet<Int>()

    /** 正在释放中的任务：期间 organizer 切换产生的 onTaskVanished 不回灌宿主（避免重入） */
    private val releasingTasks = ConcurrentHashMap.newKeySet<Int>()

    /** leash 写入日志节流（诊断用；物理矩形异常时可从这里溯源） */
    private val lastLeashWriteLogAt = ConcurrentHashMap<Int, Long>()

    fun isLeashManaged(taskId: Int): Boolean = adoptedTasks.contains(taskId)

    fun currentLogicalViewport(taskId: Int): Rect? =
        if (isLeashManaged(taskId)) logicalBounds[taskId]?.let(::Rect) else null

    fun isFloatingRootTask(taskId: Int): Boolean = taskId > 0 && taskId == floatingRootTaskId

    /** 承载根是否已具备 always-on-top 置顶身份（生效后宿主应让路，不再抢层）。 */
    fun isRootAlwaysOnTop(): Boolean = rootAlwaysOnTop

    /**
     * 承载根的 WindowContainerToken：启动时注入 `ActivityOptions#setLaunchRootTask`，
     * 让应用任务**出生即在根下**（容器兜底路径的任务归属语义；小窗不改应用 DPI）。
     * 根未就绪时返回 null，调用方退回默认启动路径（认领兜底）。
     */
    fun launchRootToken(): Any? = floatingRootToken?.takeIf { entryReady }

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
            log(TAG, "ownLeash failed; refusing a borrowed callback handle", it)
        }.getOrNull()
    }

    private fun releaseQuietly(sc: SurfaceControl?) {
        if (sc == null) return
        runCatching { sc.release() }
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

    /** Current WM ownership, read under its global lock by marker/registration/write guards. */
    internal fun ownsNativeTask(task: Any?): Boolean {
        if (task == null) return false
        return isInOurSubtree(task) || pendingAdoption.contains(XposedHelpers.getIntField(task, "mTaskId")) ||
            XposedHelpers.getObjectField(task, "mTaskOrganizer") === organizer
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
        if (android.os.Looper.myLooper() != callbackHandler.looper) {
            callbackHandler.post { init() }; return
        }
        if (initializing) return
        retry?.let(callbackHandler::removeCallbacks); retry = null
        initializing = true
        var step = "controller"
        try {
            initialization = "initializing"
            val organizerController = XposedHelpers.callMethod(
                Instances.activityTaskManager, "getWindowOrganizerController"
            )
            // Same AIDL accessor used by framework TaskOrganizer; no private controller field.
            val taskOrganizerController = checkNotNull(XposedHelpers.callMethod(organizerController, "getTaskOrganizerController"))
            organizerControllerRef = taskOrganizerController
            step = "routing"
            if (!routingInstalled) { check(hookTaskOrganizerRouting()); routingInstalled = true }
            step = "task-info-marker"
            if (!markerInstalled) { hookTaskInfoMarker(); markerInstalled = true }
            step = "initial-staging"
            if (!canStageInitialPresentation) hookInitialSurfaceStaging()
            check(canStageInitialPresentation) { "Initial surface staging unavailable" }
            step = "corner-gate"
            if (!cornerGateInstalled) hookRoundedCornerOptCleanup()
            check(cornerGateInstalled) { "Rounded-corner cleanup gate unavailable" }
            // Shell sees global transition snapshots even for tasks assigned to our organizer.
            // Never create/adopt a freeform root before the exact running Shell acknowledges it.
            if (!ShellOwnershipServer.ready) {
                initialization = "waiting-shell-ack"
                return
            }
            step = "organizer-registration"
            val lock = XposedHelpers.getObjectField(taskOrganizerController, "mGlobalLock")
            fun nativeRegistered(): Boolean = synchronized(lock) {
                val states = XposedHelpers.getObjectField(taskOrganizerController, "mTaskOrganizerStates") as Map<*, *>
                states.containsKey(organizer.asBinder())
            }
            registered = nativeRegistered()
            if (!registered) {
                // The native call can register successfully, then fail while enumerating tasks.
                // Read its actual registry on recovery, rather than repeating a global sweep.
                XposedHelpers.callMethod(taskOrganizerController, "registerTaskOrganizer", organizer)
                registered = nativeRegistered()
                check(registered) { "Native organizer registration did not complete" }
            }
            step = "floating-root"
            createFloatingRoot(taskOrganizerController)
            check(entryReady) { "Floating root is incomplete" }
            initialization = "ready"
            failures = 0
            lastInitializationFailure = null
        } catch (error: Throwable) {
            initialization = "failed:$step"
            lastInitializationFailure = error
            logError(TAG, "backend initialization step=$step attempt=${failures + 1} ${readiness()}", error)
            // Fault recovery only. No animation starts on this timer; completed steps are retained.
            if (++failures <= 5 && ShellOwnershipServer.ready) {
                val work = Runnable { retry = null; init() }
                retry = work
                callbackHandler.postDelayed(work, (1000L shl (failures - 1)).coerceAtMost(16000L))
            }
        } finally {
            initializing = false
            publishReadiness()
        }
    }

    fun shellReadinessChanged(ready: Boolean) {
        callbackHandler.post {
            failures = 0
            if (ready) init() else {
                retry?.let(callbackHandler::removeCallbacks); retry = null
                initialization = "waiting-shell-ack"
                publishReadiness()
            }
        }
    }

    private fun publishReadiness() {
        val state = readiness()
        if (state == reportedInitialization) return
        reportedInitialization = state
        if (!entryReady) logWarning(TAG, "entry unavailable: $state build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}")
        else log(TAG) { "entry backend ready: $state build=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" }
        SmoothWindowBackend.notifyEntryCapabilitiesChanged()
    }

    /** Task.fillTaskInfo clears/rebuilds cookies in its three-argument implementation. Stamp the
     * CURRENT WM ownership while still in that call, before any snapshot can reach Shell. The
     * marker disappears naturally after reparent out; no sticky id cache can misclassify reuse.
     */
    private fun hookTaskInfoMarker() {
        val loader = Instances.systemContext.classLoader
        val cookies = TaskInfo::class.java.getField("launchCookies")
        loader.loadClass("com.android.server.wm.Task").getDeclaredMethod("fillTaskInfo", TaskInfo::class.java,
            Boolean::class.javaPrimitiveType, loader.loadClass("com.android.server.wm.TaskDisplayArea"))
            .hookAfter { call ->
                if (call.throwable != null) return@hookAfter
                runCatching {
                    val task = call.thisObject
                    if (ownsNativeTask(task)) {
                        @Suppress("UNCHECKED_CAST") val list = cookies.get(call.args[0]) as MutableList<android.os.IBinder>
                        if (!list.contains(ShellOwnershipServer.cookie)) list.add(ShellOwnershipServer.cookie)
                    }
                }.onFailure { ShellOwnershipServer.invalidate("TaskInfo ownership marker failed", it) }
            }
    }

    /** LoS WindowContainer:728 revokes the old SC on organizer changes. Hide and position the
     * replacement INSIDE that WM transaction, before an onTaskAppeared/main-thread round trip.
     */
    private fun hookInitialSurfaceStaging() {
        runCatching {
            val wc = Instances.systemContext.classLoader.loadClass("com.android.server.wm.WindowContainer")
            findMethod(wc) { name == "migrateToNewSurfaceControl" && paramCount == 1 }.hookAfter { param ->
                runCatching {
                    val task = XposedHelpers.callMethod(param.thisObject, "asTask") ?: return@runCatching
                    val id = XposedHelpers.getIntField(task, "mTaskId")
                    val stage = initialPresentations[id] ?: return@runCatching
                    val tx = param.args[0] as? SurfaceControl.Transaction ?: return@runCatching
                    val surface = XposedHelpers.callMethod(task, "getSurfaceControl") as? SurfaceControl ?: return@runCatching
                    val logical = logicalBounds[id] ?: return@runCatching
                    tx.setAlpha(surface, 0f)
                    check(writeLeashTransform(tx, id, surface, logical, stage.physical))
                    stage.surface = surface
                    stage.resizeCommitted = false
                    stage.fencedSurface = null
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (initialPresentations[id] === stage && stage.fencedSurface !== surface) {
                            runCatching { fenceInitialDraw(id, task, stage) }
                                .onFailure { log(TAG, "replacement draw fence failed task=$id", it) }
                        }
                    }
                    log("reYAMF_EntryV2") { "stage migrated task=$id surface=$surface physical=${stage.physical} logical=$logical" }
                }.onFailure { log(TAG, "initial surface staging failed", it) }
            }
            canStageInitialPresentation = true
        }.onFailure { log(TAG, "initial surface staging unavailable", it) }
    }

    /** Keep the established Task:4664 resize synchronization. Task owns the draw handler and
     * merges this owned transaction into the redraw transaction; never close WM's transaction.
     * This acknowledges the resize commit, not inspection of a new client buffer. Preparation
     * additionally checks the current window's drawn state and native transition completion.
     */
    private fun fenceInitialDraw(taskId: Int, task: Any, stage: InitialPresentation) {
        val wm = XposedHelpers.getObjectField(task, "mWmService")
        synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
            val surface = XposedHelpers.callMethod(task, "getSurfaceControl") as SurfaceControl
            val logical = requireNotNull(logicalBounds[taskId])
            val wmTx = XposedHelpers.callMethod(task, "getSyncTransaction") as SurfaceControl.Transaction
            wmTx.setAlpha(surface, 0f)
            check(writeLeashTransform(wmTx, taskId, surface, logical, stage.physical))
            stage.surface = surface
            stage.fencedSurface = surface
            stage.resizeCommitted = false
            val tx = SurfaceControl.Transaction()
            var handedToWm = false
            try {
                tx.setAlpha(surface, 0f)
                check(writeLeashTransform(tx, taskId, surface, logical, stage.physical))
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                tx.addTransactionCommittedListener(java.util.concurrent.Executor { main.post(it) }) {
                    tx.close()
                    if (initialPresentations[taskId] === stage && stage.surface === surface) {
                        stage.resizeCommitted = true
                        log("reYAMF_EntryV2") { "initial resize committed task=$taskId surface=$surface" }
                    }
                }
                handedToWm = true
                XposedHelpers.callMethod(task, "setMainWindowSizeChangeTransaction", tx)
                XposedHelpers.callMethod(task, "scheduleAnimation")
            } finally {
                if (!handedToWm) tx.close()
            }
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
    private fun hookTaskOrganizerRouting(): Boolean = runCatching {
        val loader = Instances.systemContext.classLoader
        val taskClass = loader.loadClass("com.android.server.wm.Task")
        // Warm the generated local Stub before installing callbacks into WM's locked path.
        val ours = organizer
        val stateClass = loader.loadClass("com.android.server.wm.TaskOrganizerController\$TaskOrganizerState")
        val callbacksClass = loader.loadClass("com.android.server.wm.TaskOrganizerController\$TaskOrganizerCallbacks")
        val stateCallbacks = stateClass.getDeclaredField("mOrganizer").apply { isAccessible = true }
        val callbackOwner = callbacksClass.getDeclaredField("mTaskOrganizer").apply { isAccessible = true }
        val snapshot = stateClass.getDeclaredMethod("addTaskWithoutCallback", taskClass, String::class.java).apply { isAccessible = true }
        val prepare = stateClass.getDeclaredMethod("prepareLeash", taskClass, String::class.java).apply { isAccessible = true }
        val register = loader.loadClass("com.android.server.wm.TaskOrganizerController")
            .getDeclaredMethod("registerTaskOrganizer", ITaskOrganizer::class.java).apply { isAccessible = true }
        // The public getter can be inherited from package-private BaseParceledListSlice.
        val getList = loader.loadClass("android.content.pm.ParceledListSlice").getMethod("getList")
            .apply { isAccessible = true }
        val appeared = loader.loadClass("android.window.TaskAppearedInfo")
        val getInfo = appeared.getMethod("getTaskInfo")
        val getLeash = appeared.getMethod("getLeash")
        val cookies = TaskInfo::class.java.getField("launchCookies")
        val routing = findMethod(taskClass) { name == "setTaskOrganizer" && paramCount == 2 }.hookBefore { param ->
            runCatching {
                val task = param.thisObject
                val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
                val inOurs = isInOurSubtree(task) || pendingAdoption.contains(taskId)
                val assigned = param.argument(0)
                when {
                    // Native detach/removal must be allowed to clear ownership, including an
                    // organizer-created root. Forcing our owner back here defeats its cleanup.
                    assigned == null -> Unit
                    inOurs -> {
                        if (assigned !== ours) {
                            param.args[0] = ours
                        }
                        // A Shell registration uses skipTaskAppeared=true and consumes its
                        // snapshot list. Our redirected tasks are NOT in that list, so deliver
                        // their callbacks to our owner instead of suppressing them too.
                        param.args[1] = false
                    }
                    assigned === ours -> {
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
            }.onFailure {
                // Never let an adapter failure turn our registration into a claim on every root.
                if (param.args[0] === ours) param.result = false
                logError(TAG, "selective organizer routing failed; preserve native ownership", it)
            }
        }
        val additional = mutableListOf<NativeHooks.Registration>()
        try {
            additional += NativeHooks.hookMethod(snapshot, object : NativeHooks.Callback() {
                private fun isOurState(call: NativeHooks.Call) = callbackOwner.get(stateCallbacks.get(call.thisObject)) === ours
                override fun beforeHookedMethod(call: NativeHooks.Call) {
                    if (isOurState(call) != ownsNativeTask(call.args[0])) {
                        // registerTaskOrganizer enumerates ALL organized tasks even after routing.
                        // Copy its snapshot without changing the wrong state's mOrganizedTasks
                        // or the task's mTaskAppearedSent. This also protects a new Shell state.
                        call.result = prepare.invoke(call.thisObject, *call.args)
                    }
                }
                override fun afterHookedMethod(call: NativeHooks.Call) {
                    if (call.throwable == null)
                        (call.result as? SurfaceControl)?.let { registrationRead.get()?.leashes?.record(it) }
                }
            })
            additional += NativeHooks.hookMethod(register, object : NativeHooks.Callback() {
                override fun beforeHookedMethod(call: NativeHooks.Call) {
                    val scope = RegistrationRead((call.args[0] as ITaskOrganizer).asBinder() == ours.asBinder(), registrationRead.get())
                    registrationRead.set(scope)
                    call.setObjectExtra("miwindow.organizerRegistration", scope)
                }
                override fun afterHookedMethod(call: NativeHooks.Call) {
                    val scope = call.getObjectExtra("miwindow.organizerRegistration") as? RegistrationRead ?: return
                    try {
                        if (call.throwable == null && call.result != null) {
                            @Suppress("UNCHECKED_CAST") val list = getList.invoke(call.result) as MutableList<Any>
                            val iterator = list.iterator()
                            while (iterator.hasNext()) {
                                val item = iterator.next()
                                val info = getInfo.invoke(item)
                                val owned = (cookies.get(info) as List<*>).contains(ShellOwnershipServer.cookie)
                                if (scope.toUs != owned) {
                                    iterator.remove()
                                    val leash = getLeash.invoke(item) as SurfaceControl
                                    scope.leashes.discard(leash)
                                }
                            }
                            if (!scope.toUs) {
                                // Kept snapshots transfer to the native caller/Binder marshaller.
                                list.forEach { scope.leashes.transfer(getLeash.invoke(it) as SurfaceControl) }
                            }
                        }
                    } catch (error: Throwable) {
                        if (!scope.toUs && call.throwable == null && call.result != null)
                            scope.leashes.preserveUncertainReturn()
                        ShellOwnershipServer.invalidate("Organizer snapshot isolation failed", error)
                    } finally {
                        // Our local registration consumes no returned snapshot; failures also
                        // retire copies not handed to the caller. Never release WM's own leash.
                        try { scope.leashes.close() } finally { registrationRead.set(scope.previous) }
                    }
                }
            })
        } catch (error: Throwable) {
            additional.asReversed().forEach { it.unhook() }; routing.unhook(); throw error
        }
        log(TAG) { "task organizer routing hook installed" }
        true
    }.onFailure {
        log(TAG, "routing hook install failed", it)
    }.getOrDefault(false)

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
            log(TAG) { "rounded-corner opt gate hook installed" }
            cornerGateInstalled = true
        }.onFailure {
            log(TAG, "rounded-corner opt gate hook install failed", it)
        }
    }

    private fun isInOurSubtree(task: Any): Boolean {
        var current: Any? = task
        var guard = 0
        while (current != null && guard++ < 16) {
            val id = runCatching { XposedHelpers.getIntField(current, "mTaskId") }.getOrDefault(-1)
            if (id > 0 && id == floatingRootTaskId ||
                XposedHelpers.getObjectField(current, "mLaunchCookie") == ShellOwnershipServer.cookie) return true
            current = runCatching { XposedHelpers.callMethod(current, "getParent") }.getOrNull()?.let { parent ->
                runCatching { XposedHelpers.callMethod(parent, "asTask") }.getOrNull()
            }
        }
        return false
    }

    private fun createFloatingRoot(taskOrganizerController: Any) {
        rootInitializationStep = "check-shell"
        check(ShellOwnershipServer.ready)
        rootInitializationStep = "validate-existing-root"
        if (floatingRootToken != null && resolveTaskByToken(floatingRootToken) == null) {
            retireFloatingRoot()
        }
        if (floatingRootToken == null) {
            rootInitializationStep = "scan-cookie-owned-roots"
            // A prior createTask may have created the native root before a local copy/setup failed.
            // Recover that cookie-owned root instead of leaving an orphan and creating another.
            val roots = XposedHelpers.callMethod(taskOrganizerController, "getRootTasks", 0, null) as List<*>
            val existing = roots.filterIsInstance<ActivityManager.RunningTaskInfo>().firstOrNull { info ->
                resolveTaskByToken(XposedHelpers.getObjectField(info, "token"))?.let { task ->
                    XposedHelpers.getObjectField(task, "mLaunchCookie") == ShellOwnershipServer.cookie
                } == true
            }
            if (existing != null) {
                floatingRootTaskId = existing.taskId
                floatingRootToken = XposedHelpers.getObjectField(existing, "token")
            }
        }
        if (floatingRootToken == null) {
            rootInitializationStep = "build-task-params"
            val classLoader = Instances.systemContext.classLoader
            val builderClass = classLoader.loadClass("android.window.TaskCreationParams\$Builder")
            val builder = XposedHelpers.newInstance(builderClass)
            XposedHelpers.callMethod(builder, "setName", "reYAMF_floating_root")
            XposedHelpers.callMethod(builder, "setDisplayId", 0)
            XposedHelpers.callMethod(builder, "setWindowingMode", WINDOWING_MODE_FREEFORM)
            XposedHelpers.callMethod(builder, "setLaunchCookie", ShellOwnershipServer.cookie)
            val params = XposedHelpers.callMethod(builder, "build")

            rootInitializationStep = "create-native-task"
            val appearedInfo = checkNotNull(XposedHelpers.callMethod(taskOrganizerController, "createTask", params)) {
                "createTask returned null (display/freeform/organizer prerequisites)"
            }
            val taskInfo = XposedHelpers.callMethod(appearedInfo, "getTaskInfo") as ActivityManager.RunningTaskInfo
            val rawLeash = XposedHelpers.callMethod(appearedInfo, "getLeash") as SurfaceControl
            // Record the native identity before any fallible local setup.
            floatingRootTaskId = taskInfo.taskId
            floatingRootToken = XposedHelpers.getObjectField(taskInfo, "token")
            rootInitializationStep = "retain-and-hide-root-leash"
            try {
                val owned = checkNotNull(ownLeash(rawLeash))
                // Hide the empty container before fallible organizer/AOT setup. A failed setup
                // must not leave a top-level black container covering the current application.
                try { SurfaceControl.Transaction().use { tx -> XposedHelpers.callMethod(tx, "hide", owned); tx.apply() } }
                catch (error: Throwable) { releaseQuietly(owned); throw error }
                bindRootLeash(owned)
            }
            finally { releaseQuietly(rawLeash) }
            log(TAG) { "floating root created task=$floatingRootTaskId" }
        }
        rootInitializationStep = "validate-root-organizer"
        val rootTask = checkNotNull(resolveTaskByToken(floatingRootToken)) { "Floating root token is stale" }
        val wm = XposedHelpers.getObjectField(rootTask, "mWmService")
        synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
            if (XposedHelpers.callMethod(rootTask, "getParent") == null) {
                retireFloatingRoot()
                throw IllegalStateException("Floating root detached during initialization")
            }
            XposedHelpers.callMethod(rootTask, "updateTaskOrganizerState")
            if (XposedHelpers.getObjectField(rootTask, "mTaskOrganizer") !== organizer)
                XposedHelpers.callMethod(rootTask, "setTaskOrganizer", organizer, false)
            check(XposedHelpers.getObjectField(rootTask, "mTaskOrganizer") === organizer) { "Floating root organizer mismatch" }
            if (floatingRootLeash?.isValid != true) {
                val current = XposedHelpers.callMethod(rootTask, "getSurfaceControl") as? SurfaceControl
                bindRootLeash(checkNotNull(ownLeash(current)))
            }
        }

        // TaskDisplayArea priority 1 keeps the root above ordinary tasks and below PiP/Dream.
        // Establish this once; per-frame reorder/moveTaskToFront would fight native recents.
        if (!rootAlwaysOnTop) {
            rootInitializationStep = "configure-root-hierarchy"
            val token = checkNotNull(floatingRootToken)
            val wct = newWct()
            val tokenClass = Instances.systemContext.classLoader.loadClass("android.window.WindowContainerToken")
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", arrayOf(tokenClass, java.lang.Boolean.TYPE), token, true)
            // TaskDisplayArea#getOrCreateRootTask honors these on relaunch: recent/icon launches
            // leave our root, and the normal ownership-lost path retires the old window host.
            XposedHelpers.callMethod(wct, "setReparentLeafTaskIfRelaunch",
                arrayOf(tokenClass, java.lang.Boolean.TYPE), token, true)
            XposedHelpers.callMethod(wct, "setReparentLeafTaskIfRelaunchFromHome",
                arrayOf(tokenClass, java.lang.Boolean.TYPE), token, true)
            applyWct(wct)
            check(floatingRootToken === token) { "Floating root changed during setup" }
            rootAlwaysOnTop = true
            log(TAG) { "floating root always-on-top + reparentIfRelaunch applied task=$floatingRootTaskId" }
        }
        rootInitializationStep = "update-root-visibility"
        updateRootVisibility()
        rootInitializationStep = "ready"
    }

    private fun bindRootLeash(owned: SurfaceControl) {
        val previous = floatingRootLeash
        val old = leashes.put(floatingRootTaskId, owned)
        floatingRootLeash = owned
        if (old !== owned) releaseQuietly(old)
        if (previous !== owned && previous !== old) releaseQuietly(previous)
    }

    private fun retireFloatingRoot() {
        rootInitializationStep = "retired"
        val previous = floatingRootLeash
        val old = leashes.remove(floatingRootTaskId)
        floatingRootLeash = null; floatingRootToken = null; floatingRootTaskId = -1; rootAlwaysOnTop = false
        releaseQuietly(old)
        if (previous !== old) releaseQuietly(previous)
        adoptedTasks.toList().forEach { child ->
            val layout = overrideLeases[child]
            forgetTask(child)
            SmoothWindowBackend.notifyTaskVanished(child, layout)
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
            log(TAG) { "floating root reordered onTop" }
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
            SurfaceControl.Transaction().use { tx ->
                if (visible) {
                    XposedHelpers.callMethod(tx, "show", leash)
                } else {
                    XposedHelpers.callMethod(tx, "hide", leash)
                }
                tx.apply()
            }
            log(TAG) { "floating root visibility=$visible" }
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
        val shell = resolveShellOrganizer()
        if (shell == null) {
            logWarning(TAG, "forward $name skipped: no shell organizer")
            return
        }
        runCatching {
            XposedHelpers.callMethod(shell, name, *args)
            log(TAG) { "forward $name -> shell" }
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
                if (taskId == floatingRootTaskId && !matchesRootToken(info)) return
                // 必须在回调同步期内复制句柄：WM 在回调返回后立即 release 传入对象（见 ownLeash 注释）
                val owned = ownLeash(leash) ?: run {
                    leashes.remove(taskId)?.let(::releaseQuietly)
                    if (taskId == floatingRootTaskId) {
                        floatingRootLeash = null
                        callbackHandler.post { init() }
                    }
                    return
                }
                when {
                    taskId == floatingRootTaskId -> {
                        val old = leashes.put(taskId, owned)
                        if (old !== owned) releaseQuietly(old)
                        val prev = floatingRootLeash
                        floatingRootLeash = owned
                        if (prev !== owned && prev !== old) releaseQuietly(prev)
                        log(TAG) { "floating root leash appeared" }
                        callbackHandler.post { init() }
                    }
                    adoptedTasks.contains(taskId) -> {
                        leashes.put(taskId, owned)?.let { old -> if (old !== owned) releaseQuietly(old) }
                        log(TAG) { "onTaskAppeared task=$taskId (adopted)" }
                        SmoothWindowBackend.notifyTaskAdopted(taskId)
                    }
                    else -> {
                        // 启动即入根（ActivityOptions#setLaunchRootTask）后，任务出生即在承载根内，
                        // onTaskAppeared 会**早于认领**到达（这是常态路径）：先收编 leash（WM 在
                        // 回调返回后立即 release 传入对象，见 ownLeash 注释），并让根可见——启动
                        // 闪屏/首帧都在根内，根隐藏会吞掉启动画面。认领后的摆位回灌见 adoptTask 末尾。
                        leashes.put(taskId, owned)?.let { old -> if (old !== owned) releaseQuietly(old) }
                        updateRootVisibility()
                        log(TAG) { "onTaskAppeared task=$taskId (pre-adopt, leash cached)" }
                    }
                }
            }
            "onTaskVanished" -> {
                val info = args.getOrNull(0) as? ActivityManager.RunningTaskInfo ?: return
                val taskId = info.taskId
                if (taskId == floatingRootTaskId && !matchesRootToken(info)) return
                if (adoptionsInProgress.contains(taskId)) {
                    // The next adoption publishes its records before its WCT. An older queued
                    // vanish must be rechecked after that call returns, not revoke those records.
                    callbackHandler.post { handle("onTaskVanished", arrayOf(info)) }
                    return
                }
                // An organizer callback may be queued across a new adoption. Re-read native
                // ownership rather than revoking the new session from the old TaskInfo snapshot.
                if (isCurrentlyOurTask(info)) return
                if (taskId == floatingRootTaskId) {
                    logError(TAG, "floating root vanished; invalidate its remaining hosts")
                    retireFloatingRoot()
                    callbackHandler.post { failures = 0; init() }
                    return
                }
                val wasAdopted = adoptedTasks.remove(taskId)
                val lostLayout = overrideLeases.remove(taskId)
                leashes.remove(taskId)?.let(::releaseQuietly)
                cancelInitialPresentation(taskId)
                logicalBounds.remove(taskId)
                shapedTasks.remove(taskId)
                originalParentTokens.remove(taskId)
                lastLeashWriteLogAt.remove(taskId)
                if (wasAdopted) {
                    updateRootVisibility()
                    if (releasingTasks.contains(taskId)) {
                        log(TAG) { "onTaskVanished task=$taskId (releasing, skip notify)" }
                    } else {
                        log(TAG) { "onTaskVanished task=$taskId" }
                        SmoothWindowBackend.notifyTaskVanished(taskId, lostLayout)
                    }
                }
            }
            "onTaskInfoChanged" -> {
                val info = args.getOrNull(0) as? ActivityManager.RunningTaskInfo ?: return
                if (adoptedTasks.contains(info.taskId)) {
                    log(TAG) { "onTaskInfoChanged task=${info.taskId} visible=${info.isVisible}" }
                    // 可见性翻转回灌宿主：熄屏/亮屏/锁屏解锁不产生"置前"事件，缺失本回灌时
                    // 外壳会永久停留隐藏态（"解锁后装饰消失、resize 手柄不可触达"真根因）。
                    val visible = info.isVisible
                    // A TaskInfo may already be superseded when the main thread handles it.
                    // Always invalidate; the backend coalesces and reads current WM state.
                    SmoothWindowBackend.notifyTaskVisible(info.taskId, visible)
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
    fun adoptTask(taskId: Int, initialBounds: Rect? = null, selectedLogicalBounds: Rect? = null): Boolean {
        check(adoptionsInProgress.add(taskId)) { "Task adoption is already in progress: $taskId" }
        return try { performAdoption(taskId, initialBounds, selectedLogicalBounds) }
        finally { pendingAdoption.remove(taskId); adoptionsInProgress.remove(taskId) }
    }

    private fun performAdoption(taskId: Int, initialBounds: Rect?, selectedLogicalBounds: Rect?): Boolean {
        if (taskId <= 0 || isFloatingRootTask(taskId)) return false
        if (!entryReady) return false
        if (initialBounds != null && !canStageInitialPresentation) return false
        if (adoptedTasks.contains(taskId) && leashes[taskId]?.isValid == true) {
            // Replacing a shell must not reshape an already-owned application's viewport.
            SmoothWindowBackend.notifyTaskAdopted(taskId)
            return true
        }
        val rootToken = launchRootToken()
        if (rootToken == null) {
            logWarning(TAG, "adoptTask deferred: floating root not ready")
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

            val dm = displayMetrics()
            val bounds = if (initialBounds != null) {
                check(initialBounds.width() > 0 && initialBounds.height() > 0)
                Rect(requireNotNull(selectedLogicalBounds) { "CORE must select the logical viewport before adoption" }).also { check(!it.isEmpty) }
            } else Rect(0, 0, dm.widthPixels, dm.heightPixels)
            val stage = initialBounds?.let { InitialPresentation(Rect(it)) }
            if (stage != null) initialPresentations[taskId] = stage

            // 关键顺序：reparent 的 WCT 在 applyTransaction 内同步触发
            // task.updateTaskOrganizerState → setTaskOrganizer → sendTaskAppeared（onTaskAppeared），
            // 因此必须先把 taskId 登记为 adopted 并写好逻辑 bounds，回调才能命中并保存 leash。
            adoptedTasks.add(taskId)
            // A supplied initial viewport is laid out once in this same adoption WCT.
            logicalBounds[taskId] = bounds
            shapedTasks.remove(taskId)
            // Publish ownership only after callback geometry/staging is ready. WCT applies
            // configuration before hierarchy, so keep this marker for its whole native call.
            pendingAdoption.add(taskId)

            try {
                // A17 的 sanitizeTransaction 会静默剥掉一切指向"未 organized"容器的 WCT 操作
                // （WindowOrganizerController#sanitizeTransaction，Slog.wtf）：认领前必须确保任务
                // 已挂到 organizer 上。正常情况下 SystemUI(shell) 已组织全部 root 任务；否则
                // （systemReady 早期、shell 重启等）显式触发一次 updateTaskOrganizerState，
                // 由路由钩子经 pendingAdoption 把该任务临时路由给我们。
                val task = resolveTaskByToken(token)
                if (task != null) {
                    overrideLeases[taskId] = TaskOverrideLease(task,
                        WindowRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
                    FreeformBackend.rememberTask(taskId, task)
                    if (!isTaskOrganized(task)) {
                        runCatching { XposedHelpers.callMethod(task, "updateTaskOrganizerState") }
                            .onFailure { log(TAG, "updateTaskOrganizerState failed task=$taskId", it) }
                        if (!isTaskOrganized(task)) {
                            logError(TAG, "ERROR adoptTask task=$taskId cannot be organized (no fallback), abort")
                            adoptedTasks.remove(taskId)
                            logicalBounds.remove(taskId)
                            overrideLeases.remove(taskId)
                            return false
                        }
                    }
                } else {
                    logWarning(TAG, "adoptTask task=$taskId: task object unresolved, proceeding")
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
                applyWct(wct)
                if (stage != null) {
                    val task = requireNotNull(resolveTaskByToken(token))
                    fenceInitialDraw(taskId, task, stage)
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
                        SurfaceControl.Transaction().use { tx ->
                            XposedHelpers.callMethod(
                                tx, "toggleClientDrawnRoundedCornersOpt", surface, false
                            )
                            tx.apply()
                        }
                        log(TAG) { "corner opt: client-drawn disabled (SF crop) task=$taskId" }
                    } else {
                        log(TAG) { "corner opt: no surface to disable client-drawn task=$taskId" }
                    }
                }.onFailure { log(TAG, "corner opt disable failed task=$taskId", it) }
            } catch (t: Throwable) {
                // WCT may already have migrated/reparented the task. Keep its ownership records
                // until the host's failed-open rollback releases the CURRENT surface and parent.
                // Removing them here would incorrectly select the legacy fullscreen fallback.
                throw t
            }

            updateRootVisibility()
            log(TAG) { "adoptTask task=$taskId bounds=$bounds root=$rootToken" }
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
                    SurfaceControl.Transaction().use { tx ->
                        runCatching { XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f) }
                        runCatching { XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f) }
                        runCatching {
                            XposedHelpers.callMethod(
                                tx, "setWindowCrop",
                                arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), leash, null
                            )
                        }
                        runCatching { XposedHelpers.callMethod(tx, "setCornerRadius", leash, 0f) }
                        runCatching { tx.setAlpha(leash, 1f) }
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
                }

                val wct = newWct()
                XposedHelpers.callMethod(wct, "setWindowingMode", token, 1 /* FULLSCREEN */)
                // LoS DesktopTasksController#addMoveToFullscreenChanges clears the freeform
                // override as well. Otherwise the session's virtual size survives fullscreen.
                XposedHelpers.callMethod(wct, "setBounds", token, Rect())
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

                // Organizer 交接会迁移表面；新表面默认隐藏，旧 leash 的 show 不会继承。
                // 在 WM 同步事务内复位当前表面，并请求 WM 按实际可见性完成交接。
                resetTaskSurfaceNow(taskId, token)

                // 任务已离开我们的组织（organizer 变更时表面已迁移到新 SurfaceControl），释放自持句柄
                leashes.remove(taskId)?.let { releaseQuietly(it) }
                adoptedTasks.remove(taskId)
                logicalBounds.remove(taskId)
                shapedTasks.remove(taskId)
                originalParentTokens.remove(taskId)
                overrideLeases.remove(taskId)
                lastLeashWriteLogAt.remove(taskId)
                updateRootVisibility()
                log(TAG) { "releaseTask task=$taskId" }
                true
            }.onFailure {
                log(TAG, "releaseTask failed task=$taskId", it)
            }.getOrDefault(false)
        } finally {
            releasingTasks.remove(taskId)
        }
    }

    /** 复位任务当前表面并交还可见性；不复用已被 organizer 迁移作废的 leash。 */
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
            val reset = TaskSurfaceHandoff.finish(taskObj) { surface, transaction ->
                val sc = surface as SurfaceControl
                val tx = transaction as SurfaceControl.Transaction
                XposedHelpers.callMethod(tx, "setMatrix", sc, 1f, 0f, 0f, 1f)
                tx.setPosition(sc, 0f, 0f)
                XposedHelpers.callMethod(tx, "setWindowCrop",
                    arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), sc, null)
                XposedHelpers.callMethod(tx, "setCornerRadius", sc, 0f)
                runCatching { XposedHelpers.callMethod(tx, "setShadowRadius", sc, 0f) }
                    .onFailure { log(TAG, "restore shadow radius failed task=$taskId", it) }
                tx.setAlpha(sc, 1f)
                // Optional native corner optimization must not prevent visibility recovery.
                runCatching { XposedHelpers.callMethod(tx, "toggleClientDrawnRoundedCornersOpt", sc, true) }
                    .onFailure { log(TAG, "restore corner opt failed task=$taskId", it) }
            }
            log(TAG) { "surface handoff task=$taskId reset=$reset (WM visibility queued)" }
            reset
        }.onFailure { log(TAG, "surface reset failed task=$taskId", it) }.getOrDefault(false)
    }

    /** Uniform scale, cover crop and visual-radius compensation; never changes app bounds. */
    private fun writeLeashTransform(
        tx: SurfaceControl.Transaction,
        taskId: Int,
        leash: SurfaceControl,
        logical: Rect,
        physical: Rect
    ): Boolean {
        val appearance = SmoothWindowBackend.appearanceOf(taskId)
        val transform = ViewportTransform.compute(
            logical.width(), logical.height(), physical.left, physical.top,
            physical.width(), physical.height(), appearance.cornerRadiusPx, appearance.contentAlignment
        ) ?: return false
        XposedHelpers.callMethod(tx, "setMatrix", leash, transform.scale, 0f, 0f, transform.scale)
        tx.setPosition(leash, transform.x, transform.y)
        val crop = fractionalCrop
        if (crop != null) {
            crop.invoke(tx, leash, transform.cropLeft, transform.cropTop,
                transform.cropLeft + transform.cropWidth, transform.cropTop + transform.cropHeight)
        } else {
            XposedHelpers.callMethod(tx, "setWindowCrop", leash, Rect(
                transform.cropLeft.roundToInt(), transform.cropTop.roundToInt(),
                (transform.cropLeft + transform.cropWidth).roundToInt(),
                (transform.cropTop + transform.cropHeight).roundToInt()
            ))
        }
        // Client-drawn rounded-corner optimization remains disabled for the whole session (adopt /
        // transition cleanup guard / release). These are SF-only corners, never app-owned pixels.
        XposedHelpers.callMethod(tx, "setCornerRadius", leash, transform.cornerRadius)
        tx.setAlpha(leash, appearance.alpha)
        XposedHelpers.callMethod(tx, if (appearance.visible) "show" else "hide", leash)
        return true
    }

    /** LOS 24.0 WCT#setFocusable -> WOC#applyChanges (CHANGE_FOCUSABLE) -> lifecycle /
     * traversal. WindowContainer#setFocusable alone changes only a field, leaving InputMonitor
     * and focused activity stale. Only mode boundaries call this; no reflection/IPC per frame.
     * Surface hiding stays in the caller's synchronized frame transaction, never WCT#setHidden.
     */
    internal fun setPresentationFocusable(taskId: Int, focusable: Boolean): Boolean = runCatching {
        val lease = overrideLeases[taskId] ?: return false
        val task = lease.task
        val wm = XposedHelpers.getObjectField(task, "mWmService")
        synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
            if (overrideLeases[taskId] !== lease || !isLeashManaged(taskId) || !isInOurSubtree(task)) return false
            val current = XposedHelpers.getBooleanField(task, "mIsFocusable")
            if (focusable && !lease.focusSuppressed) return true
            if (current == focusable) {
                if (focusable) lease.focusSuppressed = false
                return true
            }
            val info = taskInfoById(taskId) ?: return false
            val token = XposedHelpers.getObjectField(info, "token")
            if (resolveTaskByToken(token) !== task) return false
            val wct = newWct()
            XposedHelpers.callMethod(wct, "setFocusable", token, focusable)
            // Remember before apply: cleanup must cover an exception after partial native work.
            if (!focusable) lease.focusSuppressed = true
            applyWct(wct)
            if (focusable) lease.focusSuppressed = false
            log(TAG) { "presentation focus allowed=$focusable task=$taskId" }
            true
        }
    }.onFailure { logError(TAG, "presentation focus failed task=$taskId allowed=$focusable", it) }.getOrDefault(false)

    /**
     * 静止结算（ColorOS 16 缩放逻辑 / 澎湃OS3 小窗观感）：
     * - **首次结算**：把任务真实 bounds 固定为**虚拟尺寸 = 目标显示宽 × 窗口宽高比**——近全屏宽，
     *   应用按全屏宽度布局**一次**，窗口显示其**等比缩小视图**（UI 元素 = 全屏版的缩放、
     *   内容更密；竖屏保持短边宽，支持旋转的 Home 横屏时采用其横屏显示宽，
     *   显示 scale = 可见宽/虚拟宽）；leash 以 [SurfaceControl.Transaction] 与 bounds 变更
     *   **原子同步**写入缩放态。
     * - **此后一切尺寸/位置变化**（缩放 / mini 还原 / 移动）：只做**表面等比缩放**
     *   （leash 变换；任务 bounds 不再改动）——应用零重排，即"无损放大缩小"的落地点。
     * - 尺寸与位置都回到任务 bounds 本身：leash 恒等复位（历史兜底路径，幂等）。
     */
    fun settleGeometry(taskId: Int, physical: Rect, onPrepared: (() -> Unit)? = null): Boolean {
        val task = FreeformBackend.taskById(taskId) ?: run {
            logError(TAG, "settleGeometry: task not found task=$taskId")
            return false
        }
        if (physical.width() <= 0 || physical.height() <= 0) return false
        return runCatching {
            val current = runCatching { XposedHelpers.callMethod(task, "getBounds") as? Rect }.getOrNull()

            if (!shapedTasks.contains(taskId)) {
                // 首次塑形：采用交接后目标显示器的宽度；应用只在此阶段确定逻辑视口。
                // 观感依据（用户裁定）：应用内 UI 元素应为全屏版的等比缩小（澎湃OS3/ColorOS），
                // 而非按小窗宽度重排的"大元素"版本。
                // ⚠ WindowContainerToken 必须取自 RunningTaskInfo.token：
                // 服务端 Task#getToken() 恒为 null（仅远端镜像持有 mRemoteToken）——
                // 曾致本方法全程静默 return false、真实重排从未生效（真机 logcat 实证）。
                val info = taskInfoById(taskId) ?: run {
                    logError(TAG, "settleGeometry: no taskInfo task=$taskId")
                    return false
                }
                val token = XposedHelpers.getObjectField(info, "token") ?: run {
                    logError(TAG, "settleGeometry: no token task=$taskId")
                    return false
                }

                val virtual = Rect(checkNotNull(logicalBounds[taskId]) { "No selected task viewport" })
                logicalBounds[taskId] = virtual

                if (current == virtual) {
                    // bounds 已就位：只把 leash 写到缩放态（幂等路径）
                    shapedTasks.add(taskId)
                    applyLeash(taskId, physical)
                    onPrepared?.invoke()
                    return true
                }

                val leash = leashes[taskId]
                val wct = newWct()
                if (leash != null) {
                    // ⚠ 关键（LoS 24.0 源码对齐）：leash 变换必须与 bounds 变更**原子同步**应用。
                    // WindowContainerTransaction#setBoundsChangeTransaction 语义："the server will
                    // apply [the transaction] in sync with the next bounds change"（见 WOC
                    // applyChanges → tr.setMainWindowSizeChangeTransaction）。
                    // 否则（WCT 异步落 bounds + 立即普通事务）会出现中间帧："全屏表面 + 尚未
                    // 缩放" = 全屏闪帧（真机"拉伸↔全屏横跳"根因同源）。
                    val tx = SurfaceControl.Transaction()
                    if (!writeLeashTransform(tx, taskId, leash, virtual, physical)) {
                        tx.close()
                        return false
                    }
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    tx.addTransactionCommittedListener(java.util.concurrent.Executor { handler.post(it) }) {
                        tx.close()
                        onPrepared?.invoke()
                    }
                    XposedHelpers.callMethod(wct, "setBoundsChangeTransaction", token, tx)
                    // Same-process WCT holds this exact object, not a copy. Task:4686 merges it
                    // with the app's next draw; close only after that transaction commits.
                } else {
                    return false
                }
                XposedHelpers.callMethod(wct, "setBounds", token, virtual)
                applyWct(wct)
                shapedTasks.add(taskId)
                // 虚拟尺寸落点：无任何密度覆盖——应用密度恒为默认显示器值（小窗不改应用 DPI）。
                log(TAG) { "shape virtual bounds task=$taskId virtual=$virtual visible=$physical" }
                return true
            }

            // ——— 已塑形：虚拟尺寸恒定，尺寸/位置变化 = 纯表面缩放（应用零重排）———
            if (current != null && current == physical) {
                // 回到虚拟几何本身：恒等复位（幂等兜底）
                resetLeashToBounds(taskId, physical)
            } else {
                // 缩放/移动：leash 承载（等比；策略层已保证宽高比与虚拟尺寸一致）
                applyLeash(taskId, physical)
            }
            onPrepared?.invoke()
            true
        }.onFailure { log(TAG, "settleGeometry failed task=$taskId rect=$physical", it) }
            .getOrDefault(false)
    }

    /** Read the current display rather than a resources configuration from before Home rotated. */
    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also { metrics ->
        checkNotNull(Instances.systemContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)).getRealMetrics(metrics)
        check(metrics.widthPixels > 0 && metrics.heightPixels > 0)
    }


    /** 取任务 RunningTaskInfo（token 的唯一可靠来源；与 release 路径同一取法）。 */
    private fun taskInfoById(taskId: Int): Any? = runCatching {
        val runningTasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: return null
        runningTasks.find { XposedHelpers.getIntField(it, "taskId") == taskId }
    }.getOrNull()

    /** 转场控制器句柄（ATMS.getTransitionController；A17 真实归属，见 WindowContainer 同源取法）。 */
    private val transitionController: Any? by lazy {
        runCatching {
            XposedHelpers.callMethod(Instances.activityTaskManager, "getTransitionController")
        }.onFailure {
            logWarning(TAG, "transition controller unavailable (stable-gate degrades to isAnimating)", it)
        }.getOrNull()
    }

    /**
     * 任务表面是否已"静止"（没有任何系统转场/表面动画在写它的 leash）。
     *
     * 投放登场"起跑闸"：用户从最近任务投放时，Launcher 手势提交会让系统继续播放"应用退场"
     * 转场——它逐帧写的就是同一张任务表面。若我们同时起飞（逐帧写 leash），两边各写一半 →
     * 真机逐帧实锤（4.mp4 f0119-f0182）：内容面在"贴窗缩放态 ↔ 全宽虚拟尺寸态"之间以帧为
     * 粒度交替 10+ 次，最终外壳与内容错位。起跑闸 = 等系统转场读完再接管，保持单写入者。
     *
     * A17 实现：TransitionController#isParticipant(wc) 覆盖 collecting/playing 两态；
     * 叠加 Task#isAnimating 兜底旧式表面动画。能力探测失败时退化为仅 isAnimating。
     */
    fun isTaskSurfaceStable(taskId: Int): Boolean {
        val task = runCatching {
            taskInfoById(taskId)?.let { resolveTaskByToken(XposedHelpers.getObjectField(it, "token")) }
        }.getOrNull() ?: FreeformBackend.taskById(taskId) ?: return false
        return runCatching {
            val wm = XposedHelpers.getObjectField(task, "mWmService")
            synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
                initialPresentations[taskId]?.let { stage ->
                    if (!stage.resizeCommitted) return@runCatching false
                    val current = XposedHelpers.callMethod(task, "getSurfaceControl")
                    if (current !== stage.surface) return@runCatching false
                    val window = XposedHelpers.callMethod(task, "getTopVisibleAppMainWindow")
                        ?: return@runCatching false
                    if (XposedHelpers.callMethod(window, "isDrawn") != true) return@runCatching false
                }
                if (XposedHelpers.callMethod(task, "isAnimating") == true) return@runCatching false
                val controller = transitionController ?: return@runCatching true
                XposedHelpers.callMethod(controller, "isParticipant", task) == false
            }
        }.getOrDefault(false) // Unknown readiness never grants visual ownership.
    }

    /**
     * leash 恒等复位（矩阵单位/位置=物理原点/裁切清除/圆角 0）。
     * 仅用于"回到虚拟几何本身"与释放兜底等历史路径；正常缩放模型下静止态走
     * [applyLeash]（保持缩放），不得误用本方法把缩放态复位掉。
     */
    fun resetLeashToBounds(taskId: Int, physical: Rect) {
        val leash = leashes[taskId] ?: return
        runCatching {
            SurfaceControl.Transaction().use { tx ->
                XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f)
                XposedHelpers.callMethod(tx, "setPosition", leash, physical.left.toFloat(), physical.top.toFloat())
                XposedHelpers.callMethod(
                    tx, "setWindowCrop",
                    arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java), leash, null
                )
                XposedHelpers.callMethod(tx, "setCornerRadius", leash, 0f)
                tx.apply()
            }
        }.onFailure { log(TAG, "resetLeashToBounds failed task=$taskId", it) }
    }

    /** 对窗口任务 leash 摆位：等比缩放（整屏→卡片）+ 居中 + crop=整屏 + 圆角。 */
    fun applyLeash(taskId: Int, physical: Rect) {
        val leash = leashes[taskId] ?: return
        val logical = logicalBounds[taskId] ?: return
        runCatching {
            SurfaceControl.Transaction().use { tx ->
                if (!writeLeashTransform(tx, taskId, leash, logical, physical)) return@runCatching
                tx.apply()
            }
            if (com.buildsession.betterYAMF.BuildConfig.DEBUG) {
                val now = android.os.SystemClock.uptimeMillis()
                val last = lastLeashWriteLogAt[taskId] ?: 0L
                if (now - last >= 500L) {
                    lastLeashWriteLogAt[taskId] = now
                    log(TAG) { "leash write task=$taskId physical=$physical logical=$logical" }
                }
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
        if (SmoothWindowBackend.requestSynchronizedReassert(taskId)) return
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
                SurfaceControl.Transaction().use { tx ->
                    XposedHelpers.callMethod(tx, "toggleClientDrawnRoundedCornersOpt", surface, false)
                    tx.apply()
                }
            }
        }
    }

    /**
     * 动画帧单事务路径（ColorOS 单写者模型）：把 leash 结算变换写入宿主提供的事务，
     * 由宿主与外壳容器表面合并后统一 apply —— 外壳与内容同一 SF 帧原子落地。
     * 仅做写入与容错，不 apply、不落日志（逐帧高频路径）。
     */
    fun writeLeashTransformInto(
        tx: SurfaceControl.Transaction,
        taskId: Int,
        physical: Rect
    ): Boolean {
        val leash = leashes[taskId] ?: return false
        val logical = logicalBounds[taskId] ?: return false
        return runCatching { writeLeashTransform(tx, taskId, leash, logical, physical) }
            .getOrDefault(false)
    }

    /**
     * 创建壳容器表面（对齐 ColorOS `FlexibleTaskAttachBlurManager.getParentSurface`）：
     * 挂在**任务父容器**之下、相对层序紧贴任务 leash 之上（+1）——壳与任务成为同层兄弟，
     * 系统转场/任务重挂都带动它，且 WMS 完全不参与（壳几何自此是纯表面事务）。
     * 任务或 leash 未就绪返回 null（宿主回落 WMS 窗口路径）。
     */
    fun createShellContainer(taskId: Int, name: String): SurfaceControl? = runCatching {
        val leash = leashes[taskId] ?: return null
        val task = runCatching {
            taskInfoById(taskId)?.let { resolveTaskByToken(XposedHelpers.getObjectField(it, "token")) }
        }.getOrNull() ?: FreeformBackend.taskById(taskId) ?: return null
        val parentSc = runCatching {
            val parent = XposedHelpers.callMethod(task, "getParent")
            XposedHelpers.callMethod(parent, "getSurfaceControl") as? SurfaceControl
        }.getOrNull() ?: return null

        val builder = SurfaceControl.Builder()
        XposedHelpers.callMethod(builder, "setContainerLayer")
        XposedHelpers.callMethod(builder, "setName", name)
        XposedHelpers.callMethod(builder, "setCallsite", "reYAMF#createShellContainer")
        XposedHelpers.callMethod(builder, "setParent", parentSc)
        val container = XposedHelpers.callMethod(builder, "build") as? SurfaceControl ?: return null
        try {
            check(container.isValid) { "Shell container is invalid" }
            SurfaceControl.Transaction().use { tx ->
                XposedHelpers.callMethod(tx, "setRelativeLayer", container, leash, 1)
                tx.apply()
            }
        } catch (error: Throwable) {
            container.release()
            throw error
        }
        log(TAG) { "shell container created task=$taskId name=$name" }
        container
    }.onFailure { log(TAG, "createShellContainer failed task=$taskId", it) }.getOrNull()

    /** 重断言壳容器层级：相对任务 leash 重新压上（回置顶等场景；幂等）。 */
    fun raiseShellContainer(taskId: Int, container: SurfaceControl) {
        val leash = leashes[taskId] ?: return
        runCatching {
            if (!container.isValid) return
            SurfaceControl.Transaction().use { tx ->
                XposedHelpers.callMethod(tx, "setRelativeLayer", container, leash, 1)
                tx.apply()
            }
        }.onFailure { log(TAG, "raiseShellContainer failed task=$taskId", it) }
    }

    /** 释放壳容器表面（幂等）。 */
    fun destroyShellContainer(container: SurfaceControl) {
        try {
            runCatching {
                SurfaceControl.Transaction().use { tx ->
                    if (container.isValid) XposedHelpers.callMethod(tx, "remove", container)
                    tx.apply()
                }
            }.onFailure { log(TAG, "destroyShellContainer failed", it) }
        } finally {
            container.release()
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
        if (!SmoothWindowBackend.appearanceOf(taskId).visible) return
        val leash = leashes[taskId] ?: return
        runCatching {
            SurfaceControl.Transaction().use { tx ->
                XposedHelpers.callMethod(tx, "show", leash)
                tx.apply()
            }
        }
    }

    fun forgetTask(taskId: Int) {
        cancelInitialPresentation(taskId)
        adoptedTasks.remove(taskId)
        leashes.remove(taskId)?.let { releaseQuietly(it) }
        logicalBounds.remove(taskId)
        shapedTasks.remove(taskId)
        originalParentTokens.remove(taskId)
        overrideLeases.remove(taskId)
        lastLeashWriteLogAt.remove(taskId)
    }

    /** Called on main for a still-owned task; WM hierarchy/visibility are read under its lock. */
    fun currentTaskVisibility(taskId: Int): Boolean? {
        if (!isLeashManaged(taskId)) return null
        val task = FreeformBackend.taskById(taskId) ?: return null
        return runCatching {
            val wm = XposedHelpers.getObjectField(task, "mWmService")
            synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
                if (!isLeashManaged(taskId)) null
                else (XposedHelpers.callMethod(task, "getTaskInfo") as ActivityManager.RunningTaskInfo).isVisible
            }
        }.onFailure { log(TAG, "visibility read failed task=$taskId", it) }.getOrNull()
    }

    private fun isCurrentlyOurTask(info: ActivityManager.RunningTaskInfo): Boolean = runCatching {
        val task = resolveTaskByToken(XposedHelpers.getObjectField(info, "token")) ?: return@runCatching false
        val wm = XposedHelpers.getObjectField(task, "mWmService")
        synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
            val lease = overrideLeases[info.taskId]
            (lease != null && lease.task !== task) ||
                XposedHelpers.callMethod(task, "isAttached") == true &&
                XposedHelpers.getObjectField(task, "mTaskOrganizer") === organizer
        }
    }.onFailure { logWarning(TAG, "cannot read current vanished-task ownership task=${info.taskId}", it) }.getOrDefault(false)

    private fun matchesRootToken(info: ActivityManager.RunningTaskInfo): Boolean {
        val current = floatingRootToken ?: return true
        val reported = XposedHelpers.getObjectField(info, "token") ?: return false
        return XposedHelpers.callMethod(reported, "asBinder") == XposedHelpers.callMethod(current, "asBinder")
    }

    /** The old leash was revoked by WM. Reconcile only a still-matching session viewport under
     * the WM lock; do not reparent/reorder, force fullscreen, or reset the new owner's surface.
     * TaskDisplayArea:825-860 can move the task out before changing its inherited window mode.
     */
    internal fun reconcileLostOwnership(taskId: Int, lease: TaskOverrideLease?) {
        if (lease == null || isLeashManaged(taskId)) return
        val task = lease.task
        val wm = XposedHelpers.getObjectField(task, "mWmService")
        synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
            if (isLeashManaged(taskId) || XposedHelpers.callMethod(task, "isAttached") != true || isInOurSubtree(task)) return
            val info = taskInfoById(taskId) ?: return
            val token = XposedHelpers.getObjectField(info, "token")
            val currentTask = resolveTaskByToken(token) ?: return
            val bounds = XposedHelpers.callMethod(currentTask, "getRequestedOverrideBounds") as Rect
            val configuration = XposedHelpers.callMethod(currentTask, "getRequestedOverrideConfiguration")
            val wc = XposedHelpers.getObjectField(configuration, "windowConfiguration")
            val mode = XposedHelpers.callMethod(wc, "getWindowingMode") as Int
            val restore = lease.restoration(currentTask,
                WindowRect(bounds.left, bounds.top, bounds.right, bounds.bottom), mode)
            val restoreFocus = lease.canRestoreFocus(currentTask, XposedHelpers.getBooleanField(currentTask, "mIsFocusable"))
            if (restore == null && !restoreFocus) return
            val wct = newWct()
            if (restore != null) {
                XposedHelpers.callMethod(wct, "setBounds", token, Rect())
                if (restore.clearFreeformMode) XposedHelpers.callMethod(wct, "setWindowingMode", token, 0)
            }
            if (restoreFocus) XposedHelpers.callMethod(wct, "setFocusable", token, true)
            applyWct(wct)
            if (restoreFocus) lease.focusSuppressed = false
            log(TAG) { "retired viewport task=$taskId inheritedMode=${restore?.clearFreeformMode} focus=$restoreFocus" }
        }
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
