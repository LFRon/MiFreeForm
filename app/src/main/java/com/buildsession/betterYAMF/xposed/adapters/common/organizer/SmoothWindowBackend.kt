package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.api.WindowOperation
import com.buildsession.betterYAMF.window.api.CommandStatus
import com.buildsession.betterYAMF.window.api.ContentAlignment
import com.buildsession.betterYAMF.window.api.FullscreenHandoffResult
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.xposed.adapters.common.freeform.FreeformBackend
import com.buildsession.betterYAMF.xposed.adapters.common.system.SystemSignals
import com.buildsession.betterYAMF.xposed.adapters.common.system.TaskSystemOps
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logError
import com.buildsession.betterYAMF.xposed.utils.logWarning
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
    override fun existingLogicalViewport(taskId: Int): Rect? = SmoothTaskOrganizer.currentLogicalViewport(taskId)
    private const val TAG = "reYAMF_Backend"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    @Volatile
    private var callbacks: WindowBackendCallbacks? = null

    /** 会话几何缓存：唯一写入者 = 本后端（applyGeometry/settleGeometry 写、release/forget 清）。 */
    private val geometryByTask = ConcurrentHashMap<Int, Rect>()

    /** Reconstructible presentation cache. Transition reassertions must preserve opacity/corners. */
    data class Appearance(
        val cornerRadiusPx: Float = 0f, val alpha: Float = 1f,
        val contentAlignment: ContentAlignment = ContentAlignment.CENTER
    )
    private val appearanceByTask = ConcurrentHashMap<Int, Appearance>()
    fun appearanceOf(taskId: Int): Appearance = appearanceByTask[taskId] ?: Appearance()
    private val frameSynchronizedTasks = ConcurrentHashMap.newKeySet<Int>()
    private val invalidatedPresentations = ConcurrentHashMap.newKeySet<Int>()
    private val preparations = ConcurrentHashMap<Int, Preparation>()
    /** Native ownership fact, recorded before the serial host receives its notification. */
    private val lostNativeTasks = ConcurrentHashMap.newKeySet<Int>()
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val taskCallbacks = TaskCallbackDispatcher { work -> mainHandler.post { work() } }
    private val visibilityRefresh by lazy {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        TaskVisibilityRefresh({ work -> main.post { work() } }, SmoothTaskOrganizer::currentTaskVisibility) { taskId, visible ->
            // Visibility callbacks alone are delivered on main and consumed in this same turn.
            callbacks?.onTaskVisibleChanged(taskId, visible)
        }
    }
    override val supportsRetainedEntry: Boolean
        get() = SmoothTaskOrganizer.registered && SmoothTaskOrganizer.canStageInitialPresentation &&
            SmoothTaskOrganizer.launchRootToken() != null
    fun hasSynchronizedPresentation(taskId: Int): Boolean = frameSynchronizedTasks.contains(taskId)

    /** Coalesced signal, never an independent geometry write beside a View buffer. */
    fun requestSynchronizedReassert(taskId: Int): Boolean {
        if (!hasSynchronizedPresentation(taskId)) return false
        if (invalidatedPresentations.add(taskId)) {
            taskCallbacks.dispatch(taskId) { callbacks?.onPresentationInvalidated(taskId) }
        }
        return true
    }

    /** 供实现层（SmoothTaskOrganizer / FreeformBackend）读取的几何访问器。 */
    fun geometryOf(taskId: Int): Rect? = geometryByTask[taskId]

    /** 供实现层清理的几何访问器（任务消失等；幂等）。 */
    fun forgetGeometry(taskId: Int) {
        taskCallbacks.forget(taskId)
        FreeformBackend.forgetPresentation(taskId)
        visibilityRefresh.forget(taskId)
        SmoothTaskOrganizer.cancelInitialPresentation(taskId)
        preparations.remove(taskId)?.cancel()
        geometryByTask.remove(taskId)
        appearanceByTask.remove(taskId)
        frameSynchronizedTasks.remove(taskId)
        invalidatedPresentations.remove(taskId)
    }

    override fun initialize(callbacks: WindowBackendCallbacks) {
        // 先绑回调再初始化：初始化过程中可能触发任务事件
        this.callbacks = callbacks
        SmoothTaskOrganizer.init()
        FullscreenTaskHandoff.initialize()
    }

    // Installed exclusively by YAMFManager.systemReady in system_server, using its system-UID
    // ActivityManager instance; permissions belong to that process, not the module APK manifest.
    @SuppressLint("MissingPermission")
    override fun openTask(taskId: Int, initialBounds: Rect?, logicalBounds: Rect?): Boolean {
        if (taskId <= 0 || SmoothTaskOrganizer.isFloatingRootTask(taskId)) {
            logWarning(TAG, "refuse application adoption for internal/invalid task=$taskId")
            return false
        }
        FullscreenTaskHandoff.cancel(taskId)
        lostNativeTasks.remove(taskId)
        taskCallbacks.begin(taskId)
        // 认领 + 置前；不施加任何密度覆盖——小窗不改应用 DPI。
        if (initialBounds != null) {
            geometryByTask[taskId] = Rect(initialBounds)
            appearanceByTask[taskId] = Appearance(alpha = 0f)
        }
        setTaskFocusable(taskId, true)
        val adopted = SmoothTaskOrganizer.adoptTask(taskId, initialBounds, logicalBounds)
        // reparent(onTop) + reorder(root,onTop) already resumes the task. A second moveTaskToFront
        // starts an unrelated TO_FRONT/snapshot transition after recents has just finished.
        val moved = if (adopted && initialBounds == null) runCatching {
            Instances.activityManager.moveTaskToFront(taskId, 0)
        }.isSuccess else adopted
        log(TAG) { "openTask task=$taskId adopted=$adopted staged=${initialBounds != null} moved=$moved" }
        // 用户主动开窗：恢复可聚焦（可交互）
        setTaskFocusable(taskId, true)
        return adopted
    }

    override fun releaseTask(taskId: Int, toFront: Boolean): Boolean {
        // An invalid request must not turn our shared presentation root into fullscreen.
        if (taskId <= 0 || SmoothTaskOrganizer.isFloatingRootTask(taskId)) {
            logWarning(TAG, "refuse application release for internal/invalid task=$taskId")
            return false
        }
        if (lostNativeTasks.contains(taskId)) {
            // A queued old failure may race the ownership-lost event. Resource disposal is
            // allowed; the task now belongs to somebody else and must not enter legacy WCT.
            forgetGeometry(taskId)
            return true
        }
        // 会话几何先清：无论走 organizer 释放还是旧路径兜底，几何缓存都不应残留
        forgetGeometry(taskId)

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

            val classLoader = Instances.systemContext.classLoader
            val wctClass = classLoader.loadClass("android.window.WindowContainerTransaction")
            val wct = XposedHelpers.newInstance(wctClass)
            val token = XposedHelpers.getObjectField(taskInfo, "token")
            XposedHelpers.callMethod(wct, "setWindowingMode", token, WINDOWING_MODE_FULLSCREEN)
            // Clear the session override even if an external launch already selected fullscreen.
            XposedHelpers.callMethod(wct, "setBounds", token, Rect())

            val windowOrganizerController =
                XposedHelpers.callMethod(Instances.activityTaskManager, "getWindowOrganizerController")
            XposedHelpers.callMethod(windowOrganizerController, "applyTransaction", wct)
            true
        }.onFailure {
            log(TAG, "releaseTask failed task=$taskId", it)
        }.getOrDefault(false)
    }

    override fun handoffFullscreen(
        taskId: Int, onReleased: () -> Unit, onComplete: (FullscreenHandoffResult) -> Unit
    ): WindowOperation {
        val oldGeometry = geometryByTask[taskId]?.let(::Rect)
        val oldAppearance = appearanceByTask[taskId]
        val wasSynchronized = frameSynchronizedTasks.contains(taskId)
        val task = resolveTaskObject(taskId)
        return FullscreenTaskHandoff.start(taskId, task, { releaseTask(taskId, toFront = true) }, onReleased) { result ->
            if (result.ownershipKnown && !result.taskReleased && SmoothTaskOrganizer.isLeashManaged(taskId)) {
                // A rejected release may already have invalidated the old backend callbacks/cache.
                // Restore only the still-owned session, never an already migrated native surface.
                taskCallbacks.begin(taskId)
                if (oldGeometry != null) geometryByTask[taskId] = oldGeometry
                if (oldAppearance != null) appearanceByTask[taskId] = oldAppearance
                if (wasSynchronized) frameSynchronizedTasks.add(taskId)
            }
            onComplete(result)
        }
    }

    override fun applyGeometry(taskId: Int, physicalBounds: Rect) {
        geometryByTask[taskId] = physicalBounds
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            SmoothTaskOrganizer.applyLeash(taskId, physicalBounds)
        } else {
            FreeformBackend.reassertTaskById(taskId)
        }
    }

    override fun preparePresentation(
        taskId: Int, normalBounds: Rect, onComplete: (CommandStatus) -> Unit
    ): WindowOperation {
        val operation = Preparation(taskId, Rect(normalBounds), onComplete)
        preparations.put(taskId, operation)?.cancel()
        operation.start()
        return operation
    }

    /** Platform waiters belong to one invocation, never merely to a reusable task id. */
    private class Preparation(
        private val taskId: Int,
        private val bounds: Rect,
        private val onComplete: (CommandStatus) -> Unit
    ) : WindowOperation {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var completed = false
        private var stage = "viewport commit"
        private var frameCallback: android.view.Choreographer.FrameCallback? = null
        private val timeout = Runnable {
            if (!completed) {
                logError(TAG, "prepare timeout task=$taskId stage=$stage")
                finish(CommandStatus.FAILED)
            }
        }

        fun start() {
            if (!SmoothTaskOrganizer.isLeashManaged(taskId)) {
                finish(CommandStatus.FAILED)
                return
            }
            geometryByTask[taskId] = Rect(bounds)
            appearanceByTask[taskId] = Appearance(alpha = 0f)
            SmoothTaskOrganizer.applyLeash(taskId, bounds)
            // LoS BLASTSyncEngine's default sync timeout is a failure bound, never a start delay.
            handler.postDelayed(timeout, 5000L)
            if (!SmoothTaskOrganizer.settleGeometry(taskId, bounds) {
                    handler.post { awaitStableSurface() }
                }) finish(CommandStatus.FAILED)
        }

        private fun awaitStableSurface() {
            if (completed || preparations[taskId] !== this) return
            stage = "system transition"
            if (!SmoothTaskOrganizer.isLeashManaged(taskId)) finish(CommandStatus.FAILED)
            else if (SmoothTaskOrganizer.isTaskSurfaceStable(taskId)) finish(CommandStatus.DONE)
            else {
                val callback = android.view.Choreographer.FrameCallback {
                    frameCallback = null
                    awaitStableSurface()
                }
                frameCallback = callback
                android.view.Choreographer.getInstance().postFrameCallback(callback)
            }
        }

        private fun finish(status: CommandStatus) {
            if (completed) return
            completed = true
            handler.removeCallbacksAndMessages(null)
            frameCallback?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
            frameCallback = null
            val owned = preparations.remove(taskId, this)
            val result = if (!owned) CommandStatus.SUPERSEDED
                else if (status == CommandStatus.DONE &&
                    (!SmoothTaskOrganizer.isLeashManaged(taskId) || !geometryByTask.containsKey(taskId))) CommandStatus.FAILED
                else status
            if (result == CommandStatus.DONE) {
                SmoothTaskOrganizer.cancelInitialPresentation(taskId)
                frameSynchronizedTasks.add(taskId)
            }
            // Report outside an organizer call or the native commit callback's stack.
            handler.post { onComplete(result) }
        }

        override fun cancel() {
            if (android.os.Looper.myLooper() == handler.looper) finish(CommandStatus.SUPERSEDED)
            else handler.post { finish(CommandStatus.SUPERSEDED) }
        }
    }

    override fun writePresentationInto(
        tx: android.view.SurfaceControl.Transaction,
        taskId: Int,
        physicalBounds: Rect,
        cornerRadiusPx: Float,
        alpha: Float,
        contentAlignment: ContentAlignment
    ): Boolean {
        if (!SmoothTaskOrganizer.isLeashManaged(taskId)) return false
        invalidatedPresentations.remove(taskId)
        geometryByTask[taskId] = Rect(physicalBounds)
        appearanceByTask[taskId] = Appearance(cornerRadiusPx, alpha.coerceIn(0f, 1f), contentAlignment)
        return SmoothTaskOrganizer.writeLeashTransformInto(tx, taskId, physicalBounds)
    }

    override fun writeGeometryInto(
        tx: android.view.SurfaceControl.Transaction,
        taskId: Int,
        physicalBounds: Rect
    ) {
        geometryByTask[taskId] = physicalBounds
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            SmoothTaskOrganizer.writeLeashTransformInto(tx, taskId, physicalBounds)
        } else {
            // 降级（未接管任务）：与 applyGeometry 同语义，兼容路径自行落一次变换
            FreeformBackend.reassertTaskById(taskId)
        }
    }

    override fun createShellContainer(taskId: Int, name: String): android.view.SurfaceControl? =
        SmoothTaskOrganizer.createShellContainer(taskId, name)

    override fun raiseShellContainer(taskId: Int, container: android.view.SurfaceControl) {
        SmoothTaskOrganizer.raiseShellContainer(taskId, container)
    }

    override fun destroyShellContainer(container: android.view.SurfaceControl) {
        SmoothTaskOrganizer.destroyShellContainer(container)
    }

    override fun settleGeometry(taskId: Int, contentBox: Rect) {
        geometryByTask[taskId] = contentBox
        if (SmoothTaskOrganizer.isLeashManaged(taskId)) {
            // 首次塑形 / 纯缩放保持 / 恒等复位 由 organizer 依任务状态判定
            //（ColorOS 16 缩放逻辑：虚拟尺寸恒定，其后尺寸变化一律纯表面缩放、应用零重排）
            SmoothTaskOrganizer.settleGeometry(taskId, contentBox)
        } else {
            // 兼容路径（未接管）：走旧变换重断言
            FreeformBackend.reassertTaskById(taskId)
        }
    }

    override fun reapplyGeometry(taskId: Int) {
        if (requestSynchronizedReassert(taskId)) return
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
            val wm = XposedHelpers.getObjectField(task, "mWmService")
            synchronized(XposedHelpers.getObjectField(wm, "mGlobalLock")) {
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
                        log(TAG) { "task=$taskId demoted focus (float on top)" }
                    }
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

    @SuppressLint("MissingPermission") // Same system_server-only execution boundary as openTask.
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
        SmoothTaskOrganizer.isLeashManaged(taskId)

    override fun managedTaskIds(): IntArray = SmoothTaskOrganizer.managedTaskIds()

    override fun isTaskInSessionRoot(taskInfo: ActivityManager.RunningTaskInfo): Boolean =
        SmoothTaskOrganizer.isTaskInFloatingRoot(taskInfo)

    /**
     * 小窗会话启动：freeform 启动 + **启动即入承载根**（承载根标识属实现细节，逻辑层不感知）。
     * 源码对齐：`RootWindowContainer#getOrCreateRootTask`（:3156）第一优先取
     * `options.getLaunchRootTask()`；`TaskDisplayArea#getLaunchRootTask`（:1012）校验
     * `mCreatedByOrganizer`（我们的承载根满足）；`ActivityOptions#toBundle`（:2498）携带
     * `KEY_LAUNCH_ROOT_TASK_TOKEN` 过 Binder。根未就绪则退回默认启动路径（认领兜底）。
     */
    override fun launchAppForSession(component: ComponentName, userId: Int, windowId: Int): Boolean = runCatching {
        // freeform 启动入承载根；不施加任何密度覆盖——小窗不改应用 DPI，
        // 应用按默认显示器密度与窗口尺寸自行布局。
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
        FullscreenTaskHandoff.cancel(taskId)
        forgetGeometry(taskId)
    }

    override fun requestSnapshotRefresh(taskId: Int) {
        TaskSystemOps.refreshTaskSnapshot(taskId)
    }

    override fun windowingModeOf(taskInfo: ActivityManager.RunningTaskInfo): Int =
        TaskSystemOps.windowingMode(taskInfo)

    override fun isHomeTask(taskInfo: android.app.TaskInfo): Boolean =
        TaskSystemOps.isHomeTask(taskInfo)

    override fun isTouchActive(): Boolean = SystemSignals.isTouchActive()

    override fun suppressRecentsGestureFor(durationMs: Long) {
        SystemSignals.suppressRecentsGestureFor(durationMs)
    }

    override fun isTaskSurfaceStable(taskId: Int): Boolean =
        SmoothTaskOrganizer.isTaskSurfaceStable(taskId)

    override fun recentsGestureSuppressUntilMs(): Long = SystemSignals.recentsGestureSuppressUntilMs

    override fun injectHomeKey(): Boolean = TaskSystemOps.injectHomeKey()

    // === 后端 → 宿主回灌（由 SmoothTaskOrganizer 在系统回调内调用） ===

    fun notifyTaskAdopted(taskId: Int) {
        taskCallbacks.dispatch(taskId) {
            if (SmoothTaskOrganizer.isLeashManaged(taskId)) callbacks?.onTaskAdopted(taskId)
        }
    }

    internal fun notifyTaskVanished(taskId: Int, layout: TaskOverrideLease? = null) {
        lostNativeTasks.add(taskId)
        taskCallbacks.dispatch(taskId) {
            if (SmoothTaskOrganizer.isLeashManaged(taskId)) return@dispatch
            // Deliver the ownership fact BEFORE cancellation can synchronously complete an old
            // preparation/fullscreen command. Otherwise its failure would request release again.
            try { callbacks?.onTaskOwnershipLost(taskId) } finally {
                if (!SmoothTaskOrganizer.isLeashManaged(taskId)) {
                    forgetGeometry(taskId)
                }
            }
        }
        // Resource retirement must also run if the old host already closed and invalidated its
        // notification ticket. The lease/current-native checks protect a replacement owner.
        mainHandler.post {
            runCatching { SmoothTaskOrganizer.reconcileLostOwnership(taskId, layout) }
                .onFailure { logError(TAG, "lost task layout cleanup failed task=$taskId", it) }
        }
    }

    /** 任务可见性翻转回灌（组织者 onTaskInfoChanged 驱动；见接口注释）。 */
    fun notifyTaskVisible(taskId: Int, visible: Boolean) {
        visibilityRefresh.request(taskId)
    }
}
