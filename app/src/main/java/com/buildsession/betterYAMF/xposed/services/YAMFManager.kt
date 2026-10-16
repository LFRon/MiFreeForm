package com.buildsession.betterYAMF.xposed.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.common.model.Config
import com.buildsession.betterYAMF.common.model.StartCmd
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.common.ui.componentName
import com.buildsession.betterYAMF.ipc.IAppIconCallback
import com.buildsession.betterYAMF.ipc.IAppListCallback
import com.buildsession.betterYAMF.ipc.IOpenCountListener
import com.buildsession.betterYAMF.ipc.IYAMFManager
import com.buildsession.betterYAMF.xposed.hook.HookLauncher
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.window.backend.WindowBackends
import com.buildsession.betterYAMF.window.host.AppWindow
import com.buildsession.betterYAMF.window.host.WindowHost
import com.buildsession.betterYAMF.window.session.WindowSessions
import com.buildsession.betterYAMF.xposed.hook.organizer.SmoothWindowBackend
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.Instances.systemContext
import com.buildsession.betterYAMF.xposed.utils.Instances.systemUiContext
import com.buildsession.betterYAMF.xposed.utils.createContext
import com.buildsession.betterYAMF.xposed.utils.getTopRootTask
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import com.qauxv.ui.CommonContextWrapper
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.delay
import rikka.hidden.compat.ActivityManagerApis
import java.io.File


object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "BetterYAMFManager"

    private const val LEGACY_DEFAULT_WINDOW_HEIGHT = 380

    private val activeWindows = mutableMapOf<Int, WindowHost>()
    val pendingSmoothFreeformApps = mutableMapOf<ComponentName, WindowHost>()

    /** "重启进入会话"防重入：组件 → 上次重启时间（uptimeMillis）；5s 节流防启动失败循环。 */
    private val sessionRestartAtMs = java.util.concurrent.ConcurrentHashMap<ComponentName, Long>()
    lateinit var config: Config
    val configFile = File("/data/system/BetterYAMF.json")

    /** 系统窗口后端端口（systemReady 安装；宿主/流程不得绕过它直接操作系统任务语义） */
    val windowBackend: WindowBackend get() = WindowBackends.current()

    private var openWindowCount = 0
    private val iOpenCountListenerSet = mutableSetOf<IOpenCountListener>()
    lateinit var activityManagerService: Any

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val HOME_INJECT_DELAY_MS = 700L

    private val taskStackListener by lazy {
        android.app.ITaskStackListenerProxy.newInstance(Instances.systemContext.classLoader) { args, method ->
            runMain {
                if (BuildConfig.DEBUG) {
                    log("reYAMF_debug", "stackEvent ${method.name}")
                }
                runCatching {
                    when (method.name) {
                    "onTaskStackChanged" -> {
                        onStackChangedForHome()
                    }
                    "onTaskMovedToFront" -> {
                        val taskInfo = args[0] as android.app.ActivityManager.RunningTaskInfo
                        activeWindows.values.forEach { it.onTaskMovedToFront(taskInfo) }
                        activeWindows.values.forEach { w ->
                            if (w.currentTaskId == taskInfo.taskId) {
                                notifyTaskVisible(w, taskInfo.isVisible)
                            }
                        }

                        // 若首帧时任务已是 FREEFORM 的任务尚未登记（例如从菜单直启），补登记并立即套用缩放链路；
                        // 切回 FULLSCREEN 的任务则移出登记。会话显示器承载的任务（普通全屏）
                        // 与容器承载共用同一"补登记"语义（isTaskInSessionDisplay 对无会话实现恒 false，
                        // 不影响容器路径）。
                        val windowingMode = windowBackend.windowingModeOf(taskInfo)
                        if (windowingMode == WINDOWING_MODE_FREEFORM || windowBackend.isTaskInSessionDisplay(taskInfo)) {
                            val topActivity = taskInfo.topActivity
                            if (topActivity != null) {
                                // 认领匹配：组件精确匹配优先；未命中按包名兜底——无杀迁移后任务顶层不变，
                                // 与待认领登记的应用入口组件可能不同（应用已导航至内部页面等）。
                                val pendingEntry = pendingSmoothFreeformApps.entries.firstOrNull {
                                    it.key == topActivity
                                } ?: pendingSmoothFreeformApps.entries.firstOrNull {
                                    it.key.packageName == topActivity.packageName
                                }
                                val pendingWindow = pendingEntry?.value
                                if (pendingWindow != null) {
                                    val inSessionRoot = windowBackend.isTaskInSessionRoot(taskInfo)
                                    val restartThrottleOk =
                                        SystemClock.uptimeMillis() - (sessionRestartAtMs[topActivity] ?: 0L) > 5000L
                                    if (!inSessionRoot && restartThrottleOk) {
                                        // 陈旧待认领命中了外部（桌面等）启动的任务：就地迁移进会话屏
                                        // （**无杀进程、不改 DPI**）：任务身份保留、进程存活；
                                        // 失败才退回冷启动。5s 节流防启动失败循环。
                                        log("reYAMF_debug", "claim move-into-session task=${taskInfo.taskId} activity=$topActivity (not in session root)")
                                        sessionRestartAtMs[topActivity] = SystemClock.uptimeMillis()
                                        val moved = windowBackend.moveTaskToSession(taskInfo.taskId, pendingWindow.windowId)
                                        if (!moved) {
                                            val userId = XposedHelpers.getIntField(taskInfo, "userId")
                                            windowBackend.launchAppForSession(topActivity, userId, pendingWindow.windowId)
                                        }
                                    } else {
                                        if (!inSessionRoot) {
                                            log("reYAMF_debug", "claim fallback-adopt task=${taskInfo.taskId} (restart throttled)")
                                        }
                                        log("reYAMF_debug", "claim task=${taskInfo.taskId} activity=$topActivity")
                                        WindowSessions.hostedTasks.add(taskInfo.taskId)
                                        pendingSmoothFreeformApps.remove(pendingEntry.key)
                                        sessionRestartAtMs.remove(topActivity)
                                        // Trigger a config change to apply spoofing/scaling immediately
                                        switchToSmoothFreeform(taskInfo.taskId)
                                        pendingWindow.currentTaskId = taskInfo.taskId
                                        pendingWindow.onTaskMovedToFront(taskInfo)
                                    }
                                }
                            }
                        } else if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                            // M7 起任务不再被切成 freeform：FULLSCREEN 任务也可能正被我们的
                            // 窗口承载——只有"未被任何窗口承载"时才清登记（否则会话直接被拆散）。
                            val hostedByWindow = activeWindows.values.any {
                                it.currentTaskId == taskInfo.taskId
                            }
                            if (!hostedByWindow) {
                                WindowSessions.hostedTasks.remove(taskInfo.taskId)
                                WindowSessions.taskActivities.remove(taskInfo.taskId)
                                windowBackend.forgetTask(taskInfo.taskId)
                            }
                        }
                    }
                    "onTaskInfoChanged" -> {
                        val taskInfo = args[0] as android.app.ActivityManager.RunningTaskInfo
                        activeWindows.values.forEach { w ->
                            if (w.currentTaskId == taskInfo.taskId) {
                                notifyTaskVisible(w, taskInfo.isVisible)
                            }
                        }
                    }
                    "onTaskDescriptionChanged" -> {
                        val taskInfo = args[0] as android.app.ActivityManager.RunningTaskInfo
                        activeWindows.values.forEach { it.onTaskDescriptionChanged(taskInfo) }
                    }
                    "onTaskRemovalStarted" -> {
                        val arg = args[0]
                        val taskId = if (arg is Int) arg else XposedHelpers.getIntField(arg, "taskId")
                        WindowSessions.hostedTasks.remove(taskId)
                        WindowSessions.taskActivities.remove(taskId)
                        windowBackend.forgetTask(taskId)
                        // 过滤：只有当被移除的任务 ID 确实属于某个小窗时才触发销
                        activeWindows.values.toList().forEach { window ->
                            if (window.currentTaskId == taskId) {
                                window.onDestroy(releaseTask = true)
                            }
                        }
                    }
                }
                }.onFailure {
                    log("reYAMF_debug", "taskStackListener handling failed: ${method.name}", it)
                }
            }
        }
    }
    private var isTaskStackListenerRegistered = false

    private var lastStackRecheckAt = 0L

    private fun onStackChangedForHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastStackRecheckAt < 700) return
        lastStackRecheckAt = now
        val tasks = runCatching {
            XposedHelpers.callMethod(Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1) as List<*>
        }.getOrNull() ?: return
        val topTask = tasks.firstOrNull() ?: return
        val topTaskId = XposedHelpers.getIntField(topTask, "taskId")
        val topPkg = runCatching {
            XposedHelpers.getObjectField(topTask, "topActivity")?.let {
                XposedHelpers.callMethod(it, "getPackageName") as String
            }
        }.getOrNull()
        val topIsHome = runCatching {
            val windowConfig = XposedHelpers.getObjectField(
                XposedHelpers.getObjectField(topTask, "configuration"),
                "windowConfiguration"
            )
            (XposedHelpers.callMethod(windowConfig, "getActivityType") as Int) == 2
        }.getOrDefault(false)
        if (BuildConfig.DEBUG) {
            log("reYAMF_debug", "stackChanged topId=$topTaskId pkg=$topPkg isHome=$topIsHome windows=${activeWindows.size}")
        }
        val windows = activeWindows.values.toList()
        if (windows.isEmpty()) return
        if (topIsHome) {
            windows.forEach { it.onHomeTop() }
        } else {
            windows.forEach { w ->
                val info = tasks.find {
                    XposedHelpers.getIntField(it, "taskId") == w.currentTaskId
                }
                if (info == null) {
                    // 承载任务已不存在：任务被系统内部移除（如 top-resumed 超时强停）不会回调
                    // onTaskRemovalStarted（真机实证：任务 9 消失后无任何 stack 事件）——
                    // 旧行为 not-found 默认 visible=true，会让死任务窗口常驻且显示空白会话屏
                    // （真机实证：窗口 2373055 在任务 9 消失 20 分钟后仍可见）。改：二次确认后拆除。
                    if (w.currentTaskId > 0) {
                        val miss = (missingTaskChecks[w.windowId] ?: 0) + 1
                        missingTaskChecks[w.windowId] = miss
                        if (BuildConfig.DEBUG) {
                            log("reYAMF_debug", "visibility task=${w.currentTaskId} found=false miss=$miss")
                        }
                        if (miss >= 2) {
                            missingTaskChecks.remove(w.windowId)
                            log("reYAMF_debug", "host task gone; destroy window=${w.windowId} task=${w.currentTaskId}")
                            runCatching { w.onDestroy(releaseTask = false) }
                        }
                    }
                    return@forEach
                }
                missingTaskChecks.remove(w.windowId)
                // 会话窗口的宿主任务被外部拉回普通显示器（桌面图标重启 = 平台复用任务重挂回默认屏）：
                // 窗口已失去承载对象（会话屏不再包含该任务），旧行为会留下一个冻结在最后一帧的
                // 幽灵小窗（真机实证：任务 9 被拉回桌面后窗口 2373055 的会话视口定格在 QQ 最后一帧）。
                // 待认领（pendingSmoothFreeformApps 仍挂在本窗口）= 打开流程进行中（迁移可能被
                // 过渡闸门推迟），不得误杀。二次确认后拆除。
                if (w.isSessionHosted) {
                    val onSession = runCatching {
                        windowBackend.isTaskInSessionDisplay(info as android.app.ActivityManager.RunningTaskInfo)
                    }.getOrDefault(true)
                    val awaitingClaim = pendingSmoothFreeformApps.values.any { it === w }
                    if (!onSession && !awaitingClaim) {
                        val miss = (staleHostChecks[w.windowId] ?: 0) + 1
                        staleHostChecks[w.windowId] = miss
                        if (BuildConfig.DEBUG) {
                            log("reYAMF_debug", "session host stale window=${w.windowId} task=${w.currentTaskId} miss=$miss")
                        }
                        if (miss >= 2) {
                            staleHostChecks.remove(w.windowId)
                            log("reYAMF_debug", "session task left session display; destroy window=${w.windowId} task=${w.currentTaskId}")
                            runCatching { w.onDestroy(releaseTask = false) }
                        }
                        return@forEach
                    }
                    staleHostChecks.remove(w.windowId)
                }
                val visible = XposedHelpers.getBooleanField(info, "isVisible")
                if (BuildConfig.DEBUG) {
                    log("reYAMF_debug", "visibility task=${w.currentTaskId} found=true visible=$visible")
                }
                notifyTaskVisible(w, visible)
            }
        }
    }

    /** 任务消失确认计数（防任务列表瞬时抖动误杀窗口）。 */
    private val missingTaskChecks = mutableMapOf<Int, Int>()

    /** 宿主任务离开会话屏确认计数（防迁移/过渡期间误杀）。 */
    private val staleHostChecks = mutableMapOf<Int, Int>()

    /** 进行中的可见性复核任务集（防重复调度）。 */
    private val visibilityRechecks = mutableSetOf<Int>()

    /**
     * 可见性通知统一入口：false 时附带一次延迟复核。
     *
     * 背景（真机实证）：任务刚迁移进新建会话屏的瞬间 `isVisible` 会瞬时为 false
     * （显示器点亮/恢复尚未完成），旧实现据此把窗口置 INVISIBLE 后没有任何事件回灌
     * （onTaskInfoChanged 不再触发，onTaskStackChanged 被 700ms 节流且 home 顶置分支不再评估）
     * ——窗口滞留隐藏（真机实证：窗口 1f9ab5d 打开后一直不可见）。
     * 复核按真实状态纠正，幂等且最多两轮。
     */
    private fun notifyTaskVisible(w: WindowHost, visible: Boolean) {
        w.onTaskVisibilityChanged(visible)
        if (!visible) scheduleVisibilityRecheck(w.windowId, 0)
    }

    private fun scheduleVisibilityRecheck(windowId: Int, attempt: Int) {
        val key = windowId * 10 + attempt
        if (!visibilityRechecks.add(key)) return
        runMain {
            delay(if (attempt == 0) 450L else 900L)
            visibilityRechecks.remove(key)
            val w = activeWindows[windowId] ?: return@runMain
            if (w.currentTaskId <= 0) return@runMain
            val tasks = runCatching {
                XposedHelpers.callMethod(
                    Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
                ) as List<*>
            }.getOrNull() ?: return@runMain
            val info = tasks.find {
                XposedHelpers.getIntField(it, "taskId") == w.currentTaskId
            } ?: return@runMain
            val visible = XposedHelpers.getBooleanField(info, "isVisible")
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug", "visibility recheck window=$windowId task=${w.currentTaskId} visible=$visible")
            }
            w.onTaskVisibilityChanged(visible)
            if (!visible && attempt == 0) scheduleVisibilityRecheck(windowId, 1)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun systemReady() {
        Instances.init(activityManagerService)
        // 系统窗口后端（WindowBackend 端口）：安装 organizer/leash 实现并绑定回灌回调
        runCatching {
            WindowBackends.install(SmoothWindowBackend)
            WindowBackends.current().initialize(object : WindowBackendCallbacks {
                override fun onTaskAdopted(taskId: Int) {
                    onOrganizerTaskAppeared(taskId)
                }

                override fun onTaskVanished(taskId: Int) {
                    onOrganizerTaskVanished(taskId)
                }
            })
        }.onFailure { log("reYAMF_debug", "window backend init failed", it) }
        systemContext.registerReceiver(Protocol.ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)
        systemContext.registerReceiver(Protocol.ACTION_RECENTS_GESTURE) { _, intent ->
            val suppressMs = intent.getLongExtra(Protocol.EXTRA_SUPPRESS_MS, 0L)
            windowBackend.suppressRecentsGestureFor(suppressMs)
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug", "recents gesture suppress ${suppressMs}ms")
            }
        }
        systemContext.registerReceiver(Protocol.ACTION_QUERY_MANAGED_TASKS) { _, _ ->
            broadcastManagedTaskIds()
        }
        systemContext.registerReceiver(Protocol.ACTION_DUMP_GEOMETRY) { _, _ ->
            activeWindows.values.toList().forEach { w ->
                runCatching { w.dumpGeometry() }
            }
        }
        systemContext.registerReceiver(Protocol.ACTION_CURRENT_TO_WINDOW) { _, _ ->
            currentToWindow()
        }
        systemContext.registerReceiver(Protocol.ACTION_OPEN_APP) { _, intent ->
            val componentName = intent.getParcelableExtra<ComponentName>(Protocol.EXTRA_COMPONENT_NAME)
                ?: return@registerReceiver
            val userId = intent.getIntExtra(Protocol.EXTRA_USER_ID, 0)
            createWindow(StartCmd(componentName = componentName, userId = userId))
        }
        systemContext.registerReceiver(Protocol.ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
            ActivityManagerApis.broadcastIntent(Intent(Protocol.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                // log(TAG, "send config: ${config.hookLauncher}")
                putExtra(Protocol.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                putExtra(Protocol.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
                `package` = intent.getStringExtra("sender")
            }, 0)
        }

        configFile.createNewFile()
        config = runCatching {
            gson.fromJson(configFile.readText(), Config::class.java)
        }.getOrNull() ?: Config()
        if (config.defaultWindowHeight == LEGACY_DEFAULT_WINDOW_HEIGHT) {
            config.defaultWindowHeight = 0
        }
        // log(TAG, "config: $config")
    }

    fun addWindow(window: WindowHost) {
        activeWindows[window.windowId] = window
        openWindowCount++

        if (!isTaskStackListenerRegistered) {
            runCatching {
                Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
                isTaskStackListenerRegistered = true
                log("reYAMF_debug", "taskStackListener registered")
            }.onFailure {
                log("reYAMF_debug", "registerTaskStackListener failed", it)
            }
        }

        notifyOpenCount()
    }

    /**
     * 宿主注销（窗口销毁时由宿主统一调用）：撤销窗口登记 / 待认领表 / 会话表 / 几何缓存，
     * 并递减打开计数同步 UI。几何释放（releaseTask）由调用方在销毁前完成，此处幂等兜底。
     */
    fun unregisterHost(host: WindowHost) {
        activeWindows.remove(host.windowId)
        pendingSmoothFreeformApps.entries.removeAll { it.value === host }
        WindowSessions.chromeTouchHoles.remove(host.windowId)
        val taskId = host.currentTaskId
        if (taskId > 0) {
            WindowSessions.hostedTasks.remove(taskId)
            WindowSessions.taskActivities.remove(taskId)
            windowBackend.forgetTask(taskId)
        }
        if (openWindowCount > 0) openWindowCount--
        notifyOpenCount()

        if (activeWindows.isEmpty() && isTaskStackListenerRegistered) {
            runCatching {
                Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
                isTaskStackListenerRegistered = false
                // log(TAG, "Global TaskStackListener unregistered (no active windows)")
            }
        }
    }

    private fun notifyOpenCount() {
        val toRemove = mutableSetOf<IOpenCountListener>()
        iOpenCountListenerSet.forEach {
            runCatching {
                it.onUpdate(openWindowCount)
            }.onFailure { _ ->
                toRemove.add(it)
            }
        }
        iOpenCountListenerSet.removeAll(toRemove)
    }

    fun createWindow(startCmd: StartCmd?) {
        Instances.iStatusBarService.collapsePanels()

        val taskId = startCmd?.taskId ?: -1
        if (taskId != -1) {
            // 同一任务已在小窗中：静默销毁旧壳（不释放任务），避免双壳互抢
            activeWindows.values.toList().forEach { w ->
                if (w.currentTaskId == taskId) {
                    log("reYAMF_debug", "dedupe old shell window=${w.windowId} task=$taskId")
                    runCatching { w.onDestroy(releaseTask = false) }
                }
            }
            WindowSessions.hostedTasks.add(taskId)
        }

        AppWindow(
            CommonContextWrapper.createAppCompatContext(systemUiContext.createContext())
        ) { window, _ ->
            addWindow(window)

            if (startCmd?.openAsMini == true) {
                window.markOpenAsMini()
            }

            if (taskId != -1) {
                // 用户从最近任务/回收区开小窗：既有任务**就地迁移进入会话屏**（无杀进程）——
                // 应用进程存活、任务身份保留（最近任务不消失、状态不重置）；小窗不改应用 DPI。
                // 迁移失败（任务不可迁移等）才退回冷启动。
                val info = taskInfoById(taskId)
                val component = info?.topActivity ?: info?.baseActivity
                if (info != null && component != null && !windowBackend.isTaskInSessionRoot(info)) {
                    log("reYAMF_debug", "reopen-as-session task=$taskId component=$component (in-place move)")
                    pendingSmoothFreeformApps[component] = window
                    val moved = windowBackend.moveTaskToSession(taskId, window.windowId)
                    if (!moved) {
                        log("reYAMF_debug", "reopen-as-session move failed task=$taskId; fallback cold launch")
                        windowBackend.launchAppForSession(component, XposedHelpers.getIntField(info, "userId"), window.windowId)
                    }
                } else {
                    WindowSessions.hostedTasks.add(taskId)
                    switchToSmoothFreeform(taskId)
                    window.currentTaskId = taskId
                }
            } else if (startCmd?.componentName != null) {
                pendingSmoothFreeformApps[startCmd.componentName] = window
                // 意图级调用：以"小窗会话"语义进入会话——迁移/启动等执行细节全部由实现层负责：
                // 同包既有任务就地迁移（无杀进程）；无既有任务时冷启动到会话屏。小窗不改应用 DPI。
                windowBackend.launchAppForSession(startCmd.componentName, startCmd.userId ?: 0, window.windowId)
            }
        }
    }

    /** 按 taskId 查询 RunningTaskInfo（流程书用的任务快照；失败返回 null）。 */
    private fun taskInfoById(taskId: Int): android.app.ActivityManager.RunningTaskInfo? = runCatching {
        val runningTasks = XposedHelpers.callMethod(
            Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1
        ) as? List<*> ?: return null
        runningTasks.filterIsInstance<android.app.ActivityManager.RunningTaskInfo>()
            .find { it.taskId == taskId }
    }.getOrNull()

    /** organizer 路径：任务的 leash 已就绪，让宿主按当前物理矩形摆放 */
    fun onOrganizerTaskAppeared(taskId: Int) {
        activeWindows.values.toList().forEach { w ->
            if (w.currentTaskId == taskId) {
                runCatching { w.onOrganizerLeash() }
            }
        }
        broadcastManagedTaskIds()
    }

    /** organizer 路径：任务已消失（含最近任务划掉），销毁对应外壳 */
    fun onOrganizerTaskVanished(taskId: Int) {
        activeWindows.values.toList().forEach { w ->
            if (w.currentTaskId == taskId) {
                runCatching { w.onDestroy(releaseTask = true) }
            }
        }
        broadcastManagedTaskIds()
    }

    /**
     * 把"受管任务（承载根 + 已认领子任务）"集合同步给 Launcher（M6 批次2）。
     *
     * 集合来源 = `WindowBackend.managedTaskIds()`（端口查询，不依赖具体后端实现）；
     * 触发点 = 流程层事件（认领回灌/消失回灌/释放完成）。
     * Launcher 侧据此把这些任务从"手势目标任务"判定（TopTaskTracker）中剔除——
     * 对齐系统对 pinned 任务的处理：上滑/快速切换等手势永远不会选中浮窗，
     * 浮窗只受自身触摸与模块命令驱动。
     */
    fun broadcastManagedTaskIds() {
        runCatching {
            val ids = windowBackend.managedTaskIds()
            val intent = Intent(Protocol.ACTION_MANAGED_TASKS)
                .setPackage("com.android.launcher3")
                .putExtra(Protocol.EXTRA_MANAGED_TASK_IDS, ids)
            systemContext.sendBroadcast(intent)
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug", "managed tasks -> launcher: [${ids.joinToString()}]")
            }
        }.onFailure {
            log("reYAMF_debug", "broadcast managed tasks failed", it)
        }
    }

    fun switchToSmoothFreeform(taskId: Int) {
        log("reYAMF_debug", "switchToSmoothFreeform task=$taskId")
        WindowSessions.hostedTasks.add(taskId)

        val runningTasks = XposedHelpers.callMethod(Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1) as List<*>
        val taskInfo = runningTasks.find {
            XposedHelpers.getIntField(it, "taskId") == taskId
        } ?: run {
            log("reYAMF_debug", "switchToSmoothFreeform task=$taskId NOT FOUND in tasks")
            return
        }
        runCatching {
            XposedHelpers.getObjectField(taskInfo, "topActivity") as? ComponentName
        }.getOrNull()?.let { WindowSessions.taskActivities[taskId] = it }

        // 系统窗口后端：认领（organizer/leash）+ 置前 + 恢复可聚焦；小窗不改应用 DPI，
        // 不施加任何密度覆盖。只走后端路径，失败大声报错，不再悄悄回退旧行为
        if (!windowBackend.openTask(taskId)) {
            log("reYAMF_debug", "ERROR organizer adopt failed task=$taskId (no fallback)")
        }
    }

    /**
     * 释放平滑任务回全屏。
     * [toFront]：true=置顶回前（下拉全屏路径）；false=沉到原父级栈底（关闭小窗：
     * 任务转后台、留在最近任务，不抢当前前台）。
     * [sessionWindowId]：会话窗口宿主的显式声明（透传至实现层）——会话回收不依赖
     * 可失败的任务 displayId 反射查询，且在宿主绑定的 taskId 失配（应用自建新任务）时
     * 按"该会话显示器上的顶层任务"兜底（真机实锤两轮：判定失败/任务失配都会导致
     * 回收被跳过 → 会话屏随窗口释放后应用消失）。
     */
    fun switchTaskToFullscreen(taskId: Int, toFront: Boolean = true, sessionWindowId: Int = -1): Boolean {
        WindowSessions.hostedTasks.remove(taskId)
        WindowSessions.taskActivities.remove(taskId)

        // 系统窗口后端：释放（organizer 优先，未接管任务走旧 WCT 回全屏兜底）
        val released = windowBackend.releaseTask(taskId, toFront, sessionWindowId)
        // 流程层负责把最新受管集合同步给 Launcher（手势目标豁免；M6 批次2）
        broadcastManagedTaskIds()
        return released
    }

    init {
        // log(TAG, "reYAMF service initialized")
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun createWindow() {
        runMain {
            createWindow(null)
        }
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun getConfigJson(): String {
        return gson.toJson(config)
    }

    override fun updateConfig(newConfig: String) {
        config = gson.fromJson(newConfig, Config::class.java)
        runMain {
            configFile.writeText(newConfig)
            // Log.d(TAG, "updateConfig: $config")
        }
    }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        iOpenCountListenerSet.add(iOpenCountListener)
        iOpenCountListener.onUpdate(openWindowCount)
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener?) {
        iOpenCountListenerSet.remove(iOpenCountListener)
    }

    override fun currentToWindow() {
        runMain {
            val task = getTopRootTask(0) ?: return@runMain
            if (task.baseActivity?.packageName != "com.android.launcher3") {
                createWindow(StartCmd(taskId = task.taskId))
            }
        }
    }

    override fun resetAllWindow() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
            systemContext.sendBroadcast(Intent(Protocol.ACTION_RESET_ALL_WINDOW))
        }
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        runMain {
            appInfo?.let {
                createWindow(StartCmd(it.activityInfo.componentName, it.userId))
            }
        }
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        AppCatalog.getAppListAsync(callback)
    }

    //Might be useful in the future
    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        AppCatalog.getAppIcon(callback, appInfo)
    }

    override fun collapseStatusBarPanel() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
        }
    }

    private val OpenInYAMFBroadcastReceiver: BroadcastReceiver.(Context, Intent) -> Unit =
        { _: Context, intent: Intent ->
            val taskId = intent.getIntExtra(Protocol.EXTRA_TASK_ID, 0)
            val componentName =
                intent.getParcelableExtra(Protocol.EXTRA_COMPONENT_NAME, ComponentName::class.java)
            val userId = intent.getIntExtra(Protocol.EXTRA_USER_ID, 0)
            val source = intent.getIntExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_UNSPECIFIED)
            val fromDropZone = intent.getBooleanExtra(Protocol.EXTRA_FROM_DROP_ZONE, false)
            log("reYAMF_debug", "openInYAMF task=$taskId source=$source fromZone=$fromDropZone component=$componentName")
            createWindow(StartCmd(componentName, userId, taskId, openAsMini = fromDropZone))

            // TODO: better way to close recents
            if (source == Protocol.SOURCE_RECENT) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { windowBackend.injectHomeKey() }
                }, HOME_INJECT_DELAY_MS)
            }
        }
}
