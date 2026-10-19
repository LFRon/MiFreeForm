package com.buildsession.betterYAMF.xposed.hook.freeform

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.view.SurfaceControl
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.hook.organizer.SmoothWindowBackend
import com.buildsession.betterYAMF.xposed.hook.organizer.TaskCallbackDispatcher
import com.buildsession.betterYAMF.xposed.hook.system.SystemSignals
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.window.session.WindowSessions
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

object FreeformBackend {
    private const val TAG = "reYAMF_FreeformBackend"
    private const val WINDOWING_MODE_FREEFORM = 5

    private val platformPolicy by lazy { FreeformPolicies.forSdk(Build.VERSION.SDK_INT) }
    // Accessed only under the compile-time build flag, so release never initializes log caches.
    private object Diagnostics {
        val lastLogByTask = ConcurrentHashMap<Int, String>()
        val snapshotLogged = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    }
    private val defangedSinks = ConcurrentHashMap.newKeySet<String>()

    // 任务对象弱引用缓存：物理矩形变化（拖动/缩放）时无需 WM 事件即可主动重施加变换
    private val taskRefs = ConcurrentHashMap<Int, java.lang.ref.WeakReference<Any>>()

    fun reassertTaskById(taskId: Int) {
        val task = taskRefs[taskId]?.get() ?: return
        val policy = platformPolicy
        applyTaskTransform(task, policy.clearTaskCrop)
    }

    fun taskById(taskId: Int): Any? = taskRefs[taskId]?.get()

    /** 缓存任务对象（organizer 认领时登记，供焦点/流程等复用）。 */
    fun rememberTask(taskId: Int, task: Any) {
        taskRefs[taskId] = java.lang.ref.WeakReference(task)
    }

    /**
     * 释放某任务我们施加过的表面变换（缩放/位置/裁剪/圆角）。
     * 必须使用缓存的 Task 对象——RunningTaskInfo 上没有 mSurfaceControl（此前传错对象导致
     * 释放静默失败：关窗回全屏后应用仍是缩放残影，"应用被弄没了"的元凶）。
     */
    fun releaseTaskTransforms(taskId: Int) {
        val task = taskRefs.remove(taskId)?.get() ?: return
        runCatching {
            val surfaceControl = XposedHelpers.getObjectField(task, "mSurfaceControl") as? SurfaceControl ?: return
            val transaction = XposedHelpers.callMethod(task, "getSyncTransaction") as SurfaceControl.Transaction
            XposedHelpers.callMethod(transaction, "setMatrix", surfaceControl, 1f, 0f, 0f, 1f)
            XposedHelpers.callMethod(transaction, "setPosition", surfaceControl, 0f, 0f)
            XposedHelpers.callMethod(
                transaction,
                "setWindowCrop",
                arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java),
                surfaceControl,
                null
            )
            XposedHelpers.callMethod(transaction, "setCornerRadius", surfaceControl, 0f)
            // 恢复系统默认的客户端自绘圆角开关（与 TransitionController 转场收尾语义一致；
            // 不恢复的话应用在回归全屏后仍保持"不自绘"状态，属我们不应留的全局副作用）
            XposedHelpers.callMethod(
                transaction, "toggleClientDrawnRoundedCornersOpt", surfaceControl, true
            )
        }.onFailure {
            log(TAG, "releaseTaskTransforms failed task=$taskId", it)
        }
    }

    // 过渡/重排期间的变换跳过会留下“欠账”：过渡没有可靠的结束锚点，
    // 这里自调度到主线程重试，直到稳态（尺寸一致）真实施加一次为止。
    private val reassertHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reassertCallbacks = TaskCallbackDispatcher { work ->
        reassertHandler.postDelayed({ work() }, REASSERT_DELAY_MS)
    }
    private val pendingReassertTasks = ConcurrentHashMap<Int, Any>()
    private val pendingReassertAttempts = ConcurrentHashMap<Int, Int>()
    private const val REASSERT_DELAY_MS = 250L
    private const val REASSERT_MAX_ATTEMPTS = 12

    private fun schedulePendingReassert(task: Any) {
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
        if (taskId < 0) return
        if (pendingReassertTasks.putIfAbsent(taskId, task) != null) return
        pendingReassertAttempts[taskId] = 0
        reassertCallbacks.begin(taskId)
        reassertCallbacks.dispatch(taskId) { runPendingReassert(taskId) }
    }

    private fun clearPendingReassert(taskId: Int) {
        reassertCallbacks.forget(taskId)
        pendingReassertTasks.remove(taskId)
        pendingReassertAttempts.remove(taskId)
    }

    /** A released presentation cannot leave delayed work targeting a later session. */
    fun forgetPresentation(taskId: Int) {
        clearPendingReassert(taskId)
        if (BuildConfig.DEBUG) {
            Diagnostics.lastLogByTask.remove(taskId)
            Diagnostics.snapshotLogged.remove(taskId)
        }
    }

    private fun runPendingReassert(taskId: Int) {
        val task = pendingReassertTasks[taskId] ?: return
        if (!WindowSessions.hostedTasks.contains(taskId)) {
            clearPendingReassert(taskId)
            return
        }
        val attempts = (pendingReassertAttempts[taskId] ?: 0) + 1
        if (attempts > REASSERT_MAX_ATTEMPTS) {
            logWarning(TAG, "reassert give-up task=$taskId attempts=$attempts")
            clearPendingReassert(taskId)
            return
        }
        pendingReassertAttempts[taskId] = attempts
        if (isTaskAnimating(task)) {
            reassertCallbacks.dispatch(taskId) { runPendingReassert(taskId) }
            return
        }
        val policy = platformPolicy
        applyTaskTransform(task, policy.clearTaskCrop)
        if (pendingReassertTasks.containsKey(taskId)) {
            reassertCallbacks.dispatch(taskId) { runPendingReassert(taskId) }
        }
    }

    private inline fun logOnce(taskId: Int, message: () -> String) {
        if (BuildConfig.DEBUG) {
            val text = message()
            if (Diagnostics.lastLogByTask.put(taskId, text) != text) {
                log(TAG) { text }
            }
        }
    }

    private inline fun logOnceTask(task: Any, message: () -> String) {
        if (BuildConfig.DEBUG) {
            val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
            logOnce(taskId, message)
        }
    }

    private fun isTaskAnimating(task: Any): Boolean =
        runCatching { XposedHelpers.callMethod(task, "isAnimating") as Boolean }.getOrDefault(false)

    private fun reassertTaskTransform(task: Any, clearCrop: Boolean, source: String) {
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
        if (SmoothWindowBackend.hasSynchronizedPresentation(taskId)) {
            // prepareSurfaces is called for every traversal. It must neither write ahead of the
            // shell buffer nor create an endless invalidate -> draw -> prepareSurfaces loop.
            if (source != "prepareSurfaces") SmoothWindowBackend.requestSynchronizedReassert(taskId)
            clearPendingReassert(taskId)
            return
        }
        if (isTaskAnimating(task)) {
            schedulePendingReassert(task)
            logOnceTask(task) { "transform skip source=$source reason=animating" }
            return
        }
        applyTaskTransform(task, clearCrop)
    }

    fun init(classLoader: ClassLoader) {
        val policy = platformPolicy
        if (BuildConfig.DEBUG) {
            log(TAG) { "init with policy: ${policy.displayName}" }
        }
        runCatching { hookRelaunch(classLoader) }.onFailure { log(TAG, "hookRelaunch failed", it) }
        runCatching { hookAdditionalRelaunchPoints(classLoader) }.onFailure { log(TAG, "hookAdditionalRelaunchPoints failed", it) }
        runCatching { hookSurfaceScaling(classLoader, policy) }.onFailure { log(TAG, "hookSurfaceScaling failed", it) }
        if (policy.reassertTaskTransforms) {
            runCatching { hookSurfaceReassertions(classLoader, policy) }.onFailure { log(TAG, "hookSurfaceReassertions failed", it) }
        }
        runCatching { hookInputScaling(classLoader) }.onFailure { log(TAG, "hookInputScaling failed", it) }
        runCatching { hookTransitionParticipation(classLoader) }.onFailure { log(TAG, "hookTransitionParticipation failed", it) }
        runCatching { hookInputSinkDefang(classLoader) }.onFailure { log(TAG, "hookInputSinkDefang failed", it) }
        runCatching { hookGlobalTouchTracker(classLoader) }.onFailure { log(TAG, "hookGlobalTouchTracker failed", it) }
    }

    /**
     * 全局触摸跟踪：WMS 自己注册的 PointerEventListener（输入 dump 里的
     * PointerEventDispatcherOverlay）能收到全部指针事件，用它维护"手指是否在屏上"，
     * 让回置顶只在无触摸时执行——否则 moveTaskToFront 会让输入分发器取消进行中的手势
     * 并把触摸重新指向小窗（“上滑划一半突然划到 Scene 里”的机制）。
     */
    private fun hookGlobalTouchTracker(classLoader: ClassLoader) {
        val candidates = listOf(
            // LoS 24：android.view.PointerEventDispatcher 已移除；WMS 每显示一台
            // （DisplayContent.java:1308 创建），全部指针事件经其 onInputEvent 进入
            // （wm/PointerEventDispatcher.java:43，随后才转发给各 PointerEventListener）。
            "com.android.server.wm.PointerEventDispatcher",
            "com.android.server.input.PointerEventDispatcher"
        )
        val cls = candidates.firstNotNullOfOrNull { runCatching { classLoader.loadClass(it) }.getOrNull() }
        if (cls == null) {
            logWarning(TAG, "PointerEventDispatcher not found, skip touch tracker")
            return
        }
        // ⚠ 历史 bug：旧实现钩 `onPointerEvent`——该名字是**监听器接口**的方法（由 dispatcher
        // 转发时调用），dispatcher 类本身没有此方法 → findMethod 抛 NoSuchMethodException →
        // 整个钩子静默未安装（isTouchActive 恒 false）。正确锚点 = dispatcher 自己的 onInputEvent。
        findMethod(cls) { name == "onInputEvent" && paramCount == 1 }.hookBefore { param ->
            runCatching {
                val event = param.args[0] as? android.view.MotionEvent ?: return@runCatching
                val now = android.os.SystemClock.uptimeMillis()
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_POINTER_DOWN ->
                        SystemSignals.lastTouchDownAtMs = now
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        SystemSignals.lastTouchUpAtMs = now
                }
            }
        }
        if (BuildConfig.DEBUG) {
            log(TAG) { "global touch tracker installed on ${cls.name}" }
        }
    }

    /**
     * LineageOS 24.0 的 ActivityRecordInputSink（隐藏触摸拦截面）：
     * - 无 updateRegion（旧锚点已不存在）；拦截由 `InputConfig.NOT_TOUCHABLE` 控制；
     * - ActivityRecord 上有官方开关 `mActivityRecordInputSinkEnabled`，为 false 时 sink 自动放行穿透。
     * 正确做法：钩 `getInputWindowHandleWrapper`（每次取用 handle 前），对平滑任务
     * 置该开关为 false，并当次强制 NOT_TOUCHABLE 立即生效。
     */
    private fun hookInputSinkDefang(classLoader: ClassLoader) {
        val sinkClass = runCatching {
            classLoader.loadClass("com.android.server.wm.ActivityRecordInputSink")
        }.getOrNull() ?: run {
            logWarning(TAG, "ActivityRecordInputSink class missing, skip sink defang hook")
            return
        }
        val notTouchable = runCatching {
            val inputConfigClass = classLoader.loadClass("android.os.InputConfig")
            XposedHelpers.getStaticIntField(inputConfigClass, "NOT_TOUCHABLE")
        }.getOrDefault(2)

        findMethod(sinkClass) {
            name == "getInputWindowHandleWrapper" && paramCount == 1
        }.hookAfter { param ->
            runCatching {
                val sink = param.thisObject
                val activityRecord = XposedHelpers.getObjectField(sink, "mActivityRecord") ?: return@runCatching
                val task = runCatching { XposedHelpers.callMethod(activityRecord, "getTask") }.getOrNull()
                    ?: return@runCatching
                val taskId = XposedHelpers.getIntField(task, "mTaskId")
                if (!WindowSessions.hostedTasks.contains(taskId)) return@runCatching

                // 官方开关：永久停用该 Activity 的输入吞噬
                runCatching { XposedHelpers.setBooleanField(activityRecord, "mActivityRecordInputSinkEnabled", false) }
                // 当次立即生效（穿透）
                val wrapper = param.result ?: return@runCatching
                runCatching {
                    XposedHelpers.callMethod(wrapper, "setInputConfigMasked", notTouchable, notTouchable)
                }
            }.onFailure {
                log(TAG, "sink defang failed", it)
            }
        }
        if (BuildConfig.DEBUG) {
            log(TAG) { "input sink defang installed (getInputWindowHandleWrapper, NOT_TOUCHABLE=$notTouchable)" }
        }
    }

    /**
     * 参与 AOSP 的过渡系统（WMShell 组织者同款机制）：
     * Transition.onTransactionReady 的提交事务是过渡的“落点帧”。平滑任务的位置/缩放/
     * 圆角/crop 直接写进这个事务——随过渡原子提交，不再从外部与过渡抢表面，
     * 也不会再出现“过渡结束后没人归位”的残留（这是历次错位事故的公共根因）。
     */
    private fun hookTransitionParticipation(classLoader: ClassLoader) {
        val transitionClass = runCatching { classLoader.loadClass("com.android.server.wm.Transition") }.getOrNull() ?: run {
            logWarning(TAG, "Transition class missing, skip participation hook")
            return
        }
        findMethod(transitionClass) {
            name == "onTransactionReady" && paramCount == 2
        }.hookAfter { param ->
            runCatching {
                val transaction = param.args[1] as? SurfaceControl.Transaction ?: return@runCatching
                val participants = XposedHelpers.getObjectField(param.thisObject, "mParticipants") as? Collection<*> ?: return@runCatching
                val policy = platformPolicy
                participants.forEach { wc ->
                    if (wc == null) return@forEach
                    val task = runCatching { XposedHelpers.callMethod(wc, "asTask") }.getOrNull() ?: return@forEach
                    val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
                    if (taskId < 0) return@forEach
                    if (com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)) {
                        // leash 任务：把稳态变换并入过渡事务（本地播放/兜底路径生效；
                        // shell 播放时以 finish 事务回写 + finish 后重施加为准）
                        com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer
                            .writeLeashInto(transaction, taskId)
                        return@forEach
                    }
                    if (!WindowSessions.hostedTasks.contains(taskId)) return@forEach
                    if (SmoothWindowBackend.geometryOf(taskId) == null) return@forEach
                    // 过渡的落点帧直接复用同一套变换：位置/缩放/圆角/crop 随过渡原子提交，
                    // 与其它锚点幂等一致，不再与过渡抢表面
                    applyTaskTransformOnTransaction(task, transaction, policy.clearTaskCrop)
                    logOnce(taskId) { "transition commit transform task=$taskId" }
                }
            }.onFailure {
                log(TAG, "transition participation failed", it)
            }
        }
        // finish 事务：WM `Transition.buildFinishTransaction → resetSurfaceTransform` 会把任务表面
        // matrix 重置为单位阵、position 归零（LineageOS 24.0 源码实锤）。在事务交给 shell 之前把
        // leash 变换重新写回，保证动画落点帧仍是缩放后的小窗。
        findMethod(transitionClass) {
            name == "buildFinishTransaction" && paramCount == 3
        }.hookAfter { param ->
            runCatching {
                val transaction = param.args[0] as? SurfaceControl.Transaction ?: return@runCatching
                val info = param.args[1] ?: return@runCatching
                val changes = runCatching { XposedHelpers.callMethod(info, "getChanges") as? List<*> }
                    .getOrNull() ?: return@runCatching
                changes.forEach { change ->
                    if (change == null) return@forEach
                    val container = runCatching { XposedHelpers.callMethod(change, "getContainer") }.getOrNull()
                    val wc = wcFromToken(container) ?: return@forEach
                    val task = runCatching { XposedHelpers.callMethod(wc, "asTask") }.getOrNull() ?: return@forEach
                    val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
                    if (taskId < 0 ||
                        !com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)
                    ) return@forEach
                    com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer
                        .writeLeashInto(transaction, taskId)
                }
            }.onFailure {
                log(TAG, "finish transaction leash reassert failed", it)
            }
        }
        // 过渡真正结束（shell 已 apply finish 事务）后，再权威重施加一次 leash 变换。
        findMethod(transitionClass) {
            name == "finishTransition" && paramCount == 1
        }.hookAfter { param ->
            runCatching {
                val participants = XposedHelpers.getObjectField(param.thisObject, "mParticipants") as? Collection<*> ?: return@runCatching
                participants.forEach { wc ->
                    if (wc == null) return@forEach
                    val task = runCatching { XposedHelpers.callMethod(wc, "asTask") }.getOrNull()
                        ?: runCatching { XposedHelpers.callMethod(wc, "getTask") }.getOrNull()
                        ?: return@forEach
                    val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
                    if (taskId >= 0 &&
                        com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)
                    ) {
                        com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.reapplyLeash(taskId)
                    }
                }
            }.onFailure {
                log(TAG, "post-finish leash reassert failed", it)
            }
        }
        if (BuildConfig.DEBUG) {
            log(TAG) { "transition participation installed (onTransactionReady/finish)" }
        }
    }

    /** 通过 WindowContainerToken 解析 WindowContainer（同进程直取）。 */
    private fun wcFromToken(token: Any?): Any? {
        if (token == null) return null
        return runCatching {
            val binder = XposedHelpers.callMethod(token, "asBinder") as? android.os.IBinder
                ?: return null
            val cls = Instances.systemContext.classLoader
                .loadClass("com.android.server.wm.WindowContainer")
            XposedHelpers.callStaticMethod(cls, "fromBinder", binder)
        }.getOrNull()
    }

    /**
     * 是否强制禁止该 Activity 因配置变更而重启。
     *
     * ⚠ 铁律（2026-10 真机实锤 + 源码对证）：宿主小窗任务（hostedTasks）**不可**抑制重启。
     * 旧实现对宿主任务一律返回 true，导致密度覆盖"只上报、不重绘"：
     * `WindowContainerTransaction.setDensityDpi`（WindowContainerTransaction.java:197）的配置
     * 能完整到达应用进程（dumpsys 里 ActivityRecord.mLastReportedConfigurations /
     * ProcessRecord.mLastReportedConfiguration 均已是目标 dpi），但
     * `ActivityRecord.ensureActivityConfiguration`（ActivityRecord.java:8550）第 8672 行
     * `shouldRelaunchLocked` 被钩子强制为 false、第 8695 行 `relaunchActivityLocked` 又被整体跳过
     * ——应用永远按旧密度的像素渲染（"dpi 不生效"表象的根因）。
     *
     * 平滑小窗（真实重排架构）下 leash 持有的是 Task 表面，Activity 重建不影响会话稳定性，
     * 因此宿主任务必须放行系统标准语义：声明了 screenSize/smallestScreenSize 的应用
     * 纯尺寸变化自然不重建（顺滑重排）；未声明 density 的应用在密度变化时正常重建重绘
     * （这正是密度覆盖生效的必要条件）。
     *
     * 旧 freeform 兼容路径（非宿主任务）仍保留用户可选的全局抑制开关。
     */
    private fun shouldForcePreventRelaunch(activityRecord: Any): Boolean {
        return runCatching {
            val task = XposedHelpers.callMethod(activityRecord, "getTask") ?: return false
            val taskId = XposedHelpers.getIntField(task, "mTaskId")

            // 宿主小窗任务：一律放行系统标准重启语义（见上方铁律说明）。
            if (WindowSessions.hostedTasks.contains(taskId)) return false
            // 释放/重排途中（leash 句柄仍在）同样不得抑制：密度/尺寸变更必须让平台按标准语义
            // 重建 Activity，否则出现"回全屏不重绘、仍是小密度"（真机实证）。
            if (com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)) return false

            val isForcePrevent = runCatching { YAMFManager.config.forcePreventRelaunch }.getOrDefault(false)
            if (isForcePrevent) {
                val windowingMode = XposedHelpers.callMethod(task, "getWindowingMode") as Int

                if (windowingMode == WINDOWING_MODE_FREEFORM) {
                    return true
                }
            }
            false
        }.getOrElse {
            // log(TAG, "Error in shouldForcePreventRelaunch", it)
            false
        }
    }

    private fun hookRelaunch(classLoader: ClassLoader) {
        val activityRecordClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
        
        // Hook all overloads of shouldRelaunchLocked
        runCatching {
            activityRecordClass.declaredMethods.forEach { method ->
                if (method.name == "shouldRelaunchLocked") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldForcePreventRelaunch(param.thisObject)) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        }
    }

    private fun hookAdditionalRelaunchPoints(classLoader: ClassLoader) {
        val activityRecordClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")

        // Hook all overloads of ensureActivityConfiguration
        runCatching {
            activityRecordClass.declaredMethods.forEach { method ->
                if (method.name == "ensureActivityConfiguration") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == true && shouldForcePreventRelaunch(param.thisObject)) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        }

        // Hook all overloads of relaunchActivityLocked to log and potentially suppress
        runCatching {
            activityRecordClass.declaredMethods.forEach { method ->
                if (method.name == "relaunchActivityLocked") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldForcePreventRelaunch(param.thisObject)) {
                                log(TAG) { "Suppressing relaunchActivityLocked for ${param.thisObject}" }
                                param.result = null // Skip original method
                            }
                        }
                    })
                }
            }
        }
    }

    private fun hookSurfaceScaling(classLoader: ClassLoader, policy: FreeformPolicy) {
        val taskClass = classLoader.loadClass("com.android.server.wm.Task")
        
        findMethod(taskClass) {
            name == "onConfigurationChanged" && paramCount == 1
        }.hookAfter { param ->
            applyTaskTransform(param.thisObject, policy.clearTaskCrop)
        }
    }

    private fun hookSurfaceReassertions(classLoader: ClassLoader, policy: FreeformPolicy) {
        val taskClass = classLoader.loadClass("com.android.server.wm.Task")

        findMethod(taskClass) {
            name == "updateSurfaceSize" && paramCount == 1
        }.hookAfter { param ->
            if (isTaskAnimating(param.thisObject)) {
                schedulePendingReassert(param.thisObject)
                logOnceTask(param.thisObject) { "transform skip source=updateSurfaceSize reason=animating" }
                return@hookAfter
            }
            val transaction = param.args[0] as? SurfaceControl.Transaction ?: return@hookAfter
            applyTaskTransformOnTransaction(param.thisObject, transaction, policy.clearTaskCrop)
        }

        findMethod(taskClass) {
            name == "resetSurfaceControlTransforms" && paramCount == 0
        }.hookAfter { param ->
            reassertTaskTransform(param.thisObject, policy.clearTaskCrop, "resetSurfaceControlTransforms")
        }

        findMethod(taskClass) {
            name == "prepareSurfaces" && paramCount == 0
        }.hookAfter { param ->
            reassertTaskTransform(param.thisObject, policy.clearTaskCrop, "prepareSurfaces")
        }

        if (BuildConfig.DEBUG) {
            log(TAG) { "reassert anchors installed (clearCrop=${policy.clearTaskCrop}, file=Task)" }
        }
    }

    private fun applyTaskTransform(task: Any, clearCrop: Boolean) {
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
        if (taskId >= 0 &&
            com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)
        ) {
            clearPendingReassert(taskId)
            // leash 归 organizer：稳态锚点（配置变更/表面重置等）只重施加 leash 变换；
            // 动画进行中跳过（过渡结束有专门的 finish 钩子做权威重施加）。
            if (!isTaskAnimating(task)) {
                com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.reapplyLeash(taskId)
            }
            return
        }
        val transaction = runCatching {
            XposedHelpers.callMethod(task, "getSyncTransaction") as SurfaceControl.Transaction
        }.getOrNull() ?: return
        applyTaskTransformOnTransaction(task, transaction, clearCrop)
    }

    private fun applyTaskTransformOnTransaction(
        task: Any,
        transaction: SurfaceControl.Transaction,
        clearCrop: Boolean
    ) {
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
        if (taskId >= 0) {
            taskRefs[taskId] = java.lang.ref.WeakReference(task)
        }
        // organizer 路径：leash 归我们所有，旧的外部变换全部让路
        if (com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)) {
            return
        }
        if (!WindowSessions.hostedTasks.contains(taskId)) {
            logOnce(taskId) { "transform skip task=$taskId reason=untracked" }
            return
        }
        val physicalBounds = SmoothWindowBackend.geometryOf(taskId)
        if (physicalBounds == null) {
            logOnce(taskId) { "transform skip task=$taskId reason=no-physical-bounds" }
            return
        }

        val config = XposedHelpers.callMethod(task, "getConfiguration") as Configuration
        val windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration")
        val spoofedBounds = XposedHelpers.callMethod(windowConfig, "getBounds") as Rect

        // 缩放把"应用实际布局尺寸（spoofed，可能被应用自身的 min 尺寸兼容放大）"
        // 映射回目标矩形（physical）：scale = physical/spoofed + 居中 + crop=spoofed。
        // 过渡进行中 config 会经过中间值，落定后由 transition 提交事务/pending 重断言归位。
        val transform = FreeformSurfaceMath.compute(
            physicalLeft = physicalBounds.left,
            physicalTop = physicalBounds.top,
            physicalWidth = physicalBounds.width(),
            physicalHeight = physicalBounds.height(),
            spoofedWidth = spoofedBounds.width(),
            spoofedHeight = spoofedBounds.height()
        )
        if (transform == null) {
            logOnce(taskId) { "transform skip task=$taskId reason=empty-spoofed-bounds" }
            return
        }

        val surfaceControl = XposedHelpers.getObjectField(task, "mSurfaceControl") as? SurfaceControl
        if (surfaceControl == null) {
            logOnce(taskId) { "transform skip task=$taskId reason=no-surface" }
            return
        }

        if (BuildConfig.DEBUG && Diagnostics.snapshotLogged.add(taskId)) {
            val requested = runCatching {
                XposedHelpers.callMethod(task, "getRequestedOverrideBounds") as Rect
            }.getOrNull()
            log(TAG) { "transform context task=$taskId configBounds=$spoofedBounds requested=$requested physical=$physicalBounds" }
        }
        logOnce(taskId) { "transform task=$taskId scale=(${transform.scaleX},${transform.scaleY}) spoofed=$spoofedBounds physical=$physicalBounds" }

        XposedHelpers.callMethod(transaction, "setMatrix", surfaceControl, transform.scaleX, 0f, 0f, transform.scaleY)
        XposedHelpers.callMethod(transaction, "setPosition", surfaceControl, transform.positionX, transform.positionY)

        if (clearCrop) {
            runCatching {
                XposedHelpers.callMethod(
                    transaction,
                    "setWindowCrop",
                    arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java),
                    surfaceControl,
                    Rect(0, 0, spoofedBounds.width(), spoofedBounds.height())
                )
            }
        }

        // 本次变换已按当前 config 施加；若这是过渡落定后（transition 提交/pending）的一次，欠账清掉。
        clearPendingReassert(taskId)
    }

    private fun hookInputScaling(classLoader: ClassLoader) {
        val inputMonitorClass = classLoader.loadClass("com.android.server.wm.InputMonitor")
        
        // populateInputWindowHandle(InputWindowHandleWrapper inputWindowHandle, WindowState w)
        XposedHelpers.findAndHookMethod(inputMonitorClass, "populateInputWindowHandle",
            "com.android.server.wm.InputWindowHandleWrapper",
            "com.android.server.wm.WindowState",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val windowState = param.args[1]
                    applyChromeTouchRegion(param.args[0], windowState)
                    if (defangSmoothInputSink(param.args[0], windowState)) return

                    val task = XposedHelpers.callMethod(windowState, "getTask") ?: return
                    val taskId = XposedHelpers.getIntField(task, "mTaskId")
                    
                    if (WindowSessions.hostedTasks.contains(taskId) &&
                        !com.buildsession.betterYAMF.xposed.hook.organizer.SmoothTaskOrganizer.isLeashManaged(taskId)
                    ) {
                        val physicalBounds = SmoothWindowBackend.geometryOf(taskId) ?: return
                        val config = XposedHelpers.callMethod(task, "getConfiguration") as Configuration
                        val windowConfig = XposedHelpers.getObjectField(config, "windowConfiguration")
                        val spoofedBounds = XposedHelpers.callMethod(windowConfig, "getBounds") as Rect
                        
                        val transform = FreeformSurfaceMath.compute(
                            physicalLeft = physicalBounds.left,
                            physicalTop = physicalBounds.top,
                            physicalWidth = physicalBounds.width(),
                            physicalHeight = physicalBounds.height(),
                            spoofedWidth = spoofedBounds.width(),
                            spoofedHeight = spoofedBounds.height()
                        ) ?: return
                        
                        val inputWindowHandleWrapper = param.args[0]
                        runCatching {
                            XposedHelpers.callMethod(
                                inputWindowHandleWrapper,
                                "setReplaceTouchableRegionWithCrop",
                                false
                            )
                            XposedHelpers.callMethod(
                                inputWindowHandleWrapper,
                                "setTouchableRegionCrop",
                                arrayOf<Class<*>>(SurfaceControl::class.java),
                                null
                            )
                        }
                        val region = surfaceTouchableRegion(windowState)
                            ?: frameBasedSurfaceRegion(windowState)
                            ?: return
                        runCatching {
                            // 区域是"表面（逻辑尺寸）坐标"，按逻辑尺寸裁剪（不是屏幕坐标的 frame）
                            region.op(0, 0, spoofedBounds.width(), spoofedBounds.height(), Region.Op.INTERSECT)
                        }
                        XposedHelpers.callMethod(
                            inputWindowHandleWrapper,
                            "setTouchableRegion",
                            region
                        )
                        // 输入坐标系跟随表面缩放（应用按 spoofed 布局，触摸需按 1/scale 映射回去）
                        XposedHelpers.callMethod(
                            inputWindowHandleWrapper,
                            "setScaleFactor",
                            FreeformSurfaceMath.inputScale(transform.scaleX)
                        )
                        // WM 的 frame 是按任务 bounds（整屏）算的“幻影矩形”，会比可见卡片大得多，
                        // 导致卡片外（其他应用上）的滑动被命中到本应用。把 frame 覆写为
                        // “卡片原点 + 本窗口逻辑矩形×scale”，命中区与坐标映射即精确对齐可见区域。
                        runCatching {
                            val handle = XposedHelpers.getObjectField(inputWindowHandleWrapper, "mHandle")
                            val frameRect = XposedHelpers.getObjectField(handle, "frame") as Rect
                            val wmFrame = XposedHelpers.callMethod(windowState, "getFrame") as Rect
                            val scale = transform.scaleX
                            val newLeft = physicalBounds.left + (wmFrame.left * scale).toInt()
                            val newTop = physicalBounds.top + (wmFrame.top * scale).toInt()
                            val newRight = physicalBounds.left + (wmFrame.right * scale).toInt()
                            val newBottom = physicalBounds.top + (wmFrame.bottom * scale).toInt()
                            if (frameRect.left != newLeft || frameRect.top != newTop ||
                                frameRect.right != newRight || frameRect.bottom != newBottom
                            ) {
                                frameRect.set(newLeft, newTop, newRight, newBottom)
                                XposedHelpers.callMethod(inputWindowHandleWrapper, "forceChange")
                            }
                        }
                    }
                }
            }
        )
    }

    private fun defangSmoothInputSink(inputWindowHandleWrapper: Any, windowState: Any): Boolean {
        val title = runCatching {
            val attrs = XposedHelpers.getObjectField(windowState, "mAttrs")
            XposedHelpers.callMethod(attrs, "getTitle") as? CharSequence
        }.getOrNull()?.toString() ?: return false
        if (!title.startsWith("ActivityRecordInputSink")) return false
        val component = title.substringAfter(' ', "").trim()
        if (component.isEmpty()) return false
        val isSmooth = WindowSessions.taskActivities.values.any {
            runCatching { it.flattenToShortString() }.getOrNull() == component
        }
        if (!isSmooth) return false
        runCatching {
            XposedHelpers.callMethod(inputWindowHandleWrapper, "setReplaceTouchableRegionWithCrop", false)
            XposedHelpers.callMethod(
                inputWindowHandleWrapper,
                "setTouchableRegionCrop",
                arrayOf<Class<*>>(SurfaceControl::class.java),
                null
            )
            XposedHelpers.callMethod(inputWindowHandleWrapper, "setTouchableRegion", Region())
        }.onFailure {
            log(TAG, "defang input sink failed for $component", it)
        }
        if (defangedSinks.add(component)) {
            log(TAG) { "defanged input sink for $component" }
        }
        return true
    }

    private fun applyChromeTouchRegion(inputWindowHandleWrapper: Any, windowState: Any) {
        if (WindowSessions.chromeTouchHoles.isEmpty()) return
        val title = runCatching {
            val attrs = XposedHelpers.getObjectField(windowState, "mAttrs")
            XposedHelpers.callMethod(attrs, "getTitle") as? CharSequence
        }.getOrNull()?.toString() ?: return
        if (title != Protocol.CHROME_WINDOW_TITLE) return
        
        val frame = runCatching {
            XposedHelpers.callMethod(windowState, "getFrame") as Rect
        }.getOrNull() ?: return
        
        val region = surfaceTouchableRegion(windowState)
            ?: Region(0, 0, frame.width(), frame.height())
        val holeRegion = Region()
        var changed = false
        WindowSessions.chromeTouchHoles.values.forEach { hole ->
            if (Rect.intersects(frame, hole)) {
                holeRegion.set(
                    hole.left - frame.left,
                    hole.top - frame.top,
                    hole.right - frame.left,
                    hole.bottom - frame.top
                )
                region.op(holeRegion, Region.Op.DIFFERENCE)
                changed = true
            }
        }
        if (!changed) return
        runCatching {
            XposedHelpers.callMethod(inputWindowHandleWrapper, "setTouchableRegion", region)
        }
    }

    private fun surfaceTouchableRegion(windowState: Any): Region? = runCatching {
        val region = Region()
        val attrs = XposedHelpers.getObjectField(windowState, "mAttrs")
        XposedHelpers.callMethod(windowState, "getSurfaceTouchableRegion", region, attrs)
        region.takeIf { !it.isEmpty }
    }.getOrNull()

    private fun frameBasedSurfaceRegion(windowState: Any): Region? = runCatching {
        val frame = XposedHelpers.callMethod(windowState, "getFrame") as Rect
        Region(frame).also { it.translate(-frame.left, -frame.top) }
    }.getOrNull()
}
