package com.buildsession.betterYAMF.xposed.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
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
import com.buildsession.betterYAMF.window.runtime.WindowRuntimes
import com.buildsession.betterYAMF.xposed.adapters.common.system.EntryProtocolIdentity
import com.buildsession.betterYAMF.xposed.adapters.common.system.EntrySnapshotTransport
import com.buildsession.betterYAMF.xposed.adapters.common.system.PendingEntryRequests
import com.buildsession.betterYAMF.xposed.adapters.common.system.UserScopedBroadcasts
import com.buildsession.betterYAMF.window.api.CommandStatus
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.window.backend.WindowBackends
import com.buildsession.betterYAMF.window.host.AppWindow
import com.buildsession.betterYAMF.window.host.WindowHost
import com.buildsession.betterYAMF.window.session.WindowSessions
import com.buildsession.betterYAMF.xposed.adapters.SystemAdapters
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.Instances.systemContext
import com.buildsession.betterYAMF.xposed.utils.Instances.systemUiContext
import com.buildsession.betterYAMF.xposed.utils.createContext
import com.buildsession.betterYAMF.xposed.utils.getTopRootTask
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.logError
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
    private val entryHandler by lazy { Handler(Looper.getMainLooper()) }
    private val entryRequests = PendingEntryRequests { logError("reYAMF_EntryV2", "pending entry cleanup failed", it) }
    val pendingSmoothFreeformApps = mutableMapOf<ComponentName, WindowHost>()

    lateinit var config: Config
    val configFile = File("/data/system/BetterYAMF.json")

    /** 系统窗口后端端口（systemReady 安装；宿主/流程不得绕过它直接操作系统任务语义） */
    val windowBackend: WindowBackend get() = WindowBackends.current()

    private var openWindowCount = 0
    private val iOpenCountListenerSet = mutableSetOf<IOpenCountListener>()
    lateinit var activityManagerService: Any

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    /** 投放起跑闸：轮询节拍 / 等待上限（超时放行并留证，主线程 Handler）。 */
    private const val DROP_GATE_POLL_MS = 32L
    private const val DROP_GATE_TIMEOUT_MS = 1200L

    private val taskStackListener by lazy {
        android.app.ITaskStackListenerProxy.newInstance(Instances.systemContext.classLoader) { args, method ->
            runMain {
                if (BuildConfig.DEBUG) {
                    log("reYAMF_debug") { "stackEvent ${method.name}" }
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
                                // Task-moved-to-front carries a snapshot taken before visibility
                                // settles (LoS TaskChangeNotificationController:418). Organized
                                // tasks receive visibility exclusively from their organizer.
                                if (!windowBackend.isTaskManaged(taskInfo.taskId)) notifyTaskVisible(w, taskInfo.isVisible)
                            }
                        }

                        // 若首帧时任务已是 FREEFORM 的任务尚未登记（例如从菜单直启），补登记并立即套用缩放链路；
                        // 切回 FULLSCREEN 的任务则移出登记。
                        val windowingMode = windowBackend.windowingModeOf(taskInfo)
                        if (windowingMode == WINDOWING_MODE_FREEFORM) {
                            val topActivity = taskInfo.topActivity
                            if (topActivity != null) {
                                // 认领匹配：组件精确匹配优先；未命中按包名兜底——任务顶层可能与
                                // 待认领登记的应用入口组件不同（应用已导航至内部页面等）。
                                val pendingEntry = pendingSmoothFreeformApps.entries.firstOrNull {
                                    it.key == topActivity
                                } ?: pendingSmoothFreeformApps.entries.firstOrNull {
                                    it.key.packageName == topActivity.packageName
                                }
                                val pendingWindow = pendingEntry?.value
                                if (pendingWindow != null) {
                                    // 就地认领入承载根（出生即入根的任务同样经此绑定；幂等）。
                                    log("reYAMF_debug") { "claim task=${taskInfo.taskId} activity=$topActivity" }
                                    WindowSessions.hostedTasks.add(taskInfo.taskId)
                                    pendingSmoothFreeformApps.remove(pendingEntry.key)
                                    // Trigger a config change to apply spoofing/scaling immediately
                                    // ⚠ 次序铁律（同 createWindow）：currentTaskId 先于同步认领赋值，
                                    // 否则 adoptTask 的同步回灌会被 onOrganizerTaskAppeared 过滤跳过。
                                    pendingWindow.bindTask(taskInfo.taskId)
                                    activateWindowTask(pendingWindow)
                                    pendingWindow.onTaskMovedToFront(taskInfo)
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
                    // 注：ITaskStackListener（LoS 24）没有 onTaskInfoChanged 回调（该名字仅存在于
                    // ITaskOrganizer）——可见性刷新由 onTaskMovedToFront + 栈复检 + 延迟复核覆盖。
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
            log("reYAMF_debug") { "stackChanged topId=$topTaskId pkg=$topPkg isHome=$topIsHome windows=${activeWindows.size}" }
        }
        val windows = activeWindows.values.toList()
        if (windows.isEmpty()) return
        if (topIsHome) {
            windows.forEach { it.onHomeTop() }
        } else {
            windows.forEach { w ->
                if (windowBackend.isTaskManaged(w.currentTaskId)) return@forEach
                val info = tasks.find {
                    XposedHelpers.getIntField(it, "taskId") == w.currentTaskId
                }
                if (info == null) {
                    // 承载任务已不存在：任务被系统内部移除（如 top-resumed 超时强停）不会回调
                    // onTaskRemovalStarted（真机实证：任务 9 消失后无任何 stack 事件）——
                    // 旧行为 not-found 默认 visible=true，会让死任务窗口常驻且显示空白小窗
                    // （真机实证：窗口 2373055 在任务 9 消失 20 分钟后仍可见）。改：二次确认后拆除。
                    if (w.currentTaskId > 0) {
                        val miss = (missingTaskChecks[w.windowId] ?: 0) + 1
                        missingTaskChecks[w.windowId] = miss
                        if (BuildConfig.DEBUG) {
                            log("reYAMF_debug") { "visibility task=${w.currentTaskId} found=false miss=$miss" }
                        }
                        if (miss >= 2) {
                            missingTaskChecks.remove(w.windowId)
                            log("reYAMF_debug") { "host task gone; destroy window=${w.windowId} task=${w.currentTaskId}" }
                            runCatching { w.onDestroy(releaseTask = false) }
                        }
                    }
                    return@forEach
                }
                missingTaskChecks.remove(w.windowId)
                val visible = XposedHelpers.getBooleanField(info, "isVisible")
                if (BuildConfig.DEBUG) {
                    log("reYAMF_debug") { "visibility task=${w.currentTaskId} found=true visible=$visible" }
                }
                notifyTaskVisible(w, visible)
            }
        }
    }

    /** 任务消失确认计数（防任务列表瞬时抖动误杀窗口）。 */
    private val missingTaskChecks = mutableMapOf<Int, Int>()

    /** 进行中的可见性复核任务集（防重复调度）。 */
    private val visibilityRechecks = mutableSetOf<Int>()

    /**
     * 可见性通知统一入口：false 时附带一次延迟复核。
     *
     * 背景（真机实证）：任务在承载根内重挂/过渡的瞬间 `isVisible` 会瞬时为 false，
     * 旧实现据此把窗口置 INVISIBLE 后没有任何事件回灌（onTaskStackChanged 被 700ms
     * 节流且 home 顶置分支不再评估）——窗口滞留隐藏（真机实证：窗口 1f9ab5d 打开后
     * 一直不可见）。复核按真实状态纠正，幂等且最多两轮。
     */
    private fun notifyTaskVisible(w: WindowHost, visible: Boolean) {
        w.onTaskVisibilityChanged(visible)
        if (!visible && !windowBackend.isTaskManaged(w.currentTaskId)) scheduleVisibilityRecheck(w.windowId, 0)
    }

    private fun scheduleVisibilityRecheck(windowId: Int, attempt: Int) {
        val key = windowId * 10 + attempt
        if (!visibilityRechecks.add(key)) return
        runMain {
            delay(if (attempt == 0) 450L else 900L)
            visibilityRechecks.remove(key)
            val w = activeWindows[windowId] ?: return@runMain
            if (w.currentTaskId <= 0) return@runMain
            if (windowBackend.isTaskManaged(w.currentTaskId)) return@runMain
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
                log("reYAMF_debug") { "visibility recheck window=$windowId task=${w.currentTaskId} visible=$visible" }
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
            WindowBackends.install(checkNotNull(SystemAdapters.current).windowBackend())
            WindowBackends.current().initialize(object : WindowBackendCallbacks {
                override fun onEntryCapabilitiesChanged() {
                    EntryProtocolIdentity.peers.recipients().forEach { peer ->
                        sendEntryCapabilities(peer, 0L)
                    }
                    broadcastManagedTaskIds()
                }
                override fun onPresentationInvalidated(taskId: Int) {
                    // Backend callbacks already leave WM's stack and validate their session on
                    // main. Another unowned dispatch here could target a replacement host.
                    activeWindows.values.firstOrNull { it.currentTaskId == taskId }?.onPresentationInvalidated()
                }

                override fun onTaskAdopted(taskId: Int) {
                    onOrganizerTaskAppeared(taskId)
                }

                override fun onTaskOwnershipLost(taskId: Int) {
                    onOrganizerTaskVanished(taskId)
                }

                override fun onTaskVisibleChanged(taskId: Int, visible: Boolean) {
                    // 可见性翻转路由到宿主窗口（与 onTaskMovedToFront 的可见性回灌同一条路）：
                    // 熄屏/解锁等无"置前"事件的场景，外壳据此恢复/收起，不再永久卡在隐藏态。
                    activeWindows.values.firstOrNull { it.currentTaskId == taskId }
                        ?.let { notifyTaskVisible(it, visible) }
                }
            })
        }.onFailure { log("reYAMF_debug", "window backend init failed", it) }
        systemContext.registerReceiver(Protocol.ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)
        systemContext.registerReceiver(Protocol.ACTION_SOURCE_RELEASED) { _, intent ->
            val user = UserScopedBroadcasts.deliveryUser(this)
            if (!EntryProtocolIdentity.acceptsDestination(intent, user)) return@registerReceiver
            val taskId = intent.getIntExtra(Protocol.EXTRA_TASK_ID, -1)
            val handoffId = intent.getLongExtra(Protocol.EXTRA_HANDOFF_ID, 0L)
            activeWindows.values.firstOrNull { it.currentTaskId == taskId }?.onSourceReleased(handoffId)
        }
        systemContext.registerReceiver(Protocol.ACTION_CANCEL_RECENTS_ENTRY) { _, intent ->
            val user = UserScopedBroadcasts.deliveryUser(this)
            if (!EntryProtocolIdentity.acceptsDestination(intent, user)) return@registerReceiver
            val taskId = intent.getIntExtra(Protocol.EXTRA_TASK_ID, -1)
            val handoffId = intent.getLongExtra(Protocol.EXTRA_HANDOFF_ID, 0L)
            intent.getStringExtra(Protocol.EXTRA_SOURCE_PACKAGE)?.let { source ->
                entryRequests.cancel(PendingEntryRequests.Key(source, user,
                    intent.getLongExtra(Protocol.EXTRA_SOURCE_EPOCH, 0L), taskId, handoffId))
            }
            activeWindows.values.firstOrNull { it.currentTaskId == taskId }?.onEntryCancelled(handoffId)
        }
        systemContext.registerReceiver(Protocol.ACTION_QUERY_ENTRY_CAPABILITIES) { _, intent ->
            val sender = launcherSender(this, intent) ?: return@registerReceiver
            val sourcePackage = sender.packageName
            val sourceEpoch = intent.getLongExtra(Protocol.EXTRA_SOURCE_EPOCH, 0L)
            val async = intent.getBooleanExtra(Protocol.EXTRA_ASYNC_SNAPSHOT, false)
            if (!EntryProtocolIdentity.peers.register(sourcePackage, sourceEpoch, sender.userId, sender.uid, async)) return@registerReceiver
            entryRequests.peerChanged(sourcePackage, sender.userId, sourceEpoch)
            EntryProtocolIdentity.peers.peer(sourcePackage, sender.userId)?.let { peer ->
                sendEntryCapabilities(peer, intent.getLongExtra(Protocol.EXTRA_CAPABILITY_QUERY_ID, 0L))
            }
            broadcastManagedTaskIds()
            if (!windowBackend.supportsRetainedEntry && !windowBackend.supportsLegacyEntry)
                windowBackend.reportEntryUnavailable()
            log("reYAMF_EntryV2") { "capabilities retained=${windowBackend.supportsRetainedEntry} legacy=${windowBackend.supportsLegacyEntry} loadedBuild=${com.buildsession.betterYAMF.BuildConfig.BUILD_TIME}" }
        }
        systemContext.registerReceiver(Protocol.ACTION_RECENTS_GESTURE) { _, intent ->
            val suppressMs = intent.getLongExtra(Protocol.EXTRA_SUPPRESS_MS, 0L)
            windowBackend.suppressRecentsGestureFor(suppressMs)
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug") { "recents gesture suppress ${suppressMs}ms" }
            }
        }
        systemContext.registerReceiver(Protocol.ACTION_QUERY_MANAGED_TASKS) { _, intent ->
            val sender = launcherSender(this, intent) ?: return@registerReceiver
            EntryProtocolIdentity.peers.register(sender.packageName,
                intent.getLongExtra(Protocol.EXTRA_SOURCE_EPOCH, 0L), sender.userId, sender.uid)
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
            val userId = intent.getIntExtra(Protocol.EXTRA_USER_ID, UserScopedBroadcasts.deliveryUser(this))
            createWindow(StartCmd(componentName = componentName, userId = userId))
        }
        systemContext.registerReceiver(Protocol.ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
            UserScopedBroadcasts.send(systemContext, Intent(Protocol.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                // log(TAG, "send config: ${config.hookLauncher}")
                putExtra(Protocol.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                putExtra(Protocol.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
                `package` = intent.getStringExtra("sender")
            }, UserScopedBroadcasts.deliveryUser(this))
        }

        configFile.createNewFile()
        config = runCatching {
            gson.fromJson(configFile.readText(), Config::class.java)
        }.getOrNull() ?: Config()
        if (config.defaultWindowHeight == LEGACY_DEFAULT_WINDOW_HEIGHT) {
            config.defaultWindowHeight = 0
        }
        runCatching {
            UserService.register(systemContext, activityManagerService)
        }.onFailure { logWarning(TAG, "App connection bridge unavailable", it) }
        // log(TAG, "config: $config")
    }

    fun addWindow(window: WindowHost) {
        activeWindows[window.windowId] = window
        openWindowCount++

        if (!isTaskStackListenerRegistered) {
            runCatching {
                Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
                isTaskStackListenerRegistered = true
                log("reYAMF_debug") { "taskStackListener registered" }
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
        if (activeWindows.remove(host.windowId) == null) return
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
        val taskId = startCmd?.taskId?.takeIf { it > 0 } ?: -1
        if (taskId == -1 && startCmd?.componentName == null) {
            logWarning(TAG, "reject window request without an existing task or component")
            rejectEntry(startCmd)
            return
        }
        Instances.iStatusBarService.collapsePanels()

        if (taskId != -1) {
            WindowRuntimes.prepareOpenTask(taskId)
            if (startCmd?.entrySnapshot == null) WindowSessions.hostedTasks.add(taskId)
        }

        var initialized = false
        AppWindow(
            CommonContextWrapper.createAppCompatContext(systemUiContext.createContext())
        ) { window, _ ->
            addWindow(window)
            if (taskId != -1) window.bindTask(taskId)
            initialized = true

            if (startCmd?.openAsMini == true) {
                window.markOpenAsMini(startCmd.dropX, startCmd.dropY, startCmd.sourceBounds,
                    startCmd.awaitSourceRelease, startCmd.sourcePackage, startCmd.handoffId, startCmd.entrySnapshot,
                    startCmd.launcherRotation, startCmd.sourceEpoch, startCmd.sourceUserId)
            }

            if (taskId != -1) {
                if (startCmd?.entrySnapshot != null) return@AppWindow
                // 用户从最近任务/回收区开小窗：既有任务**就地认领入承载根**（无杀进程）——
                // 应用进程存活、任务身份保留（最近任务不消失、状态不重置）；小窗不改应用 DPI。
                WindowSessions.hostedTasks.add(taskId)
                // ⚠ 次序铁律：currentTaskId 必须先赋值。认领链路是**同步**的——
                // switchToSmoothFreeform → openTask → adoptTask → notifyTaskAdopted →
                // onOrganizerTaskAppeared 以 `w.currentTaskId == taskId` 过滤宿主并点火
                // onOrganizerLeash（托管/回退/淡入的唯一触发点）。反序会让回灌被过滤跳过，
                // 壳既不托管也不回退（真机实证：壳完全消失、内容不落变换）。
                window.bindTask(taskId)
                activateWindowTask(window)
            } else if (startCmd?.componentName != null) {
                // A replaced or rejected launch must not leave an unclaimable transparent host.
                pendingSmoothFreeformApps.put(startCmd.componentName, window)
                    ?.onDestroy(releaseTask = false)
                // 意图级调用：以"小窗会话"语义启动——freeform 目标、启动即入根等执行细节
                // 全部由实现层负责；小窗不改应用 DPI。
                if (!windowBackend.launchAppForSession(startCmd.componentName, startCmd.userId ?: 0, window.windowId)) {
                    window.onDestroy(releaseTask = false)
                }
            }
        }
        if (!initialized) {
            WindowSessions.hostedTasks.remove(taskId)
            if (windowBackend.isTaskManaged(taskId)) windowBackend.releaseTask(taskId, true)
            rejectEntry(startCmd)
        }
    }

    private fun rejectEntry(startCmd: StartCmd?) {
        startCmd?.entrySnapshot?.close()
        reportEntry(startCmd, CommandStatus.FAILED)
    }

    /** Unbound, transparent host: CORE selects the launch viewport before any native launch.
     * Notification requests are correlated by a native launch cookie, never by package/component.
     */
    internal fun prepareNotificationHost(): WindowHost {
        var created: WindowHost? = null
        AppWindow(CommonContextWrapper.createAppCompatContext(systemUiContext.createContext())) { host, _ ->
            addWindow(host)
            host.markNotificationEntry()
            created = host
        }
        return checkNotNull(created) { "Notification host could not be created" }
    }

    /** Reuse exactly the selected native task. A temporary unbound host is discarded without
     * releasing any task when the PendingIntent chose an existing session.
     */
    internal fun claimNotificationTask(preparedHost: WindowHost, taskId: Int, requestId: Long,
        selectedHost: (WindowHost) -> Unit, result: (CommandStatus) -> Unit): WindowHost? {
        val existing = activeWindows.values.firstOrNull { it !== preparedHost && it.currentTaskId == taskId &&
            windowBackend.isTaskManaged(taskId) }
        val selected = existing ?: preparedHost
        selectedHost(selected)
        if (existing != null) preparedHost.onDestroy(false)
        else {
            if (activeWindows[preparedHost.windowId] !== preparedHost) { result(CommandStatus.FAILED); return null }
            preparedHost.bindTask(taskId)
            if (!activateWindowTask(preparedHost)) {
                preparedHost.onDestroy(true)
                result(CommandStatus.FAILED)
                return null
            }
        }
        selected.activate(requestId, result)
        return selected
    }

    private fun reportEntry(startCmd: StartCmd?, status: CommandStatus) {
        startCmd?.sourcePackage?.let { destination ->
            UserScopedBroadcasts.send(systemContext, Intent(Protocol.ACTION_WINDOW_PRESENTED).setPackage(destination)
                .putExtra(Protocol.EXTRA_TASK_ID, startCmd.taskId ?: -1)
                .putExtra(Protocol.EXTRA_HANDOFF_ID, startCmd.handoffId)
                .putExtra(Protocol.EXTRA_SOURCE_EPOCH, startCmd.sourceEpoch)
                .putExtra(Protocol.EXTRA_COMMAND_STATUS, status.name), startCmd.sourceUserId)
        }
    }

    /** Native source completion activates exactly the still-owned destination host. */
    fun activateWindowTask(window: WindowHost): Boolean {
        if (activeWindows[window.windowId] !== window || window.currentTaskId <= 0) return false
        WindowSessions.hostedTasks.add(window.currentTaskId)
        return window.openTask()
    }

    /** organizer 路径：任务的 leash 已就绪，让宿主按当前物理矩形摆放 */
    fun onOrganizerTaskAppeared(taskId: Int) {
        activeWindows.values.toList().forEach { w ->
            if (w.currentTaskId == taskId) {
                runCatching { w.onOrganizerLeash() }
            }
        }
        broadcastManagedTaskIds()
    }

    /** Native ownership ended; cleanup must not issue a second release on the next owner's task. */
    fun onOrganizerTaskVanished(taskId: Int) {
        activeWindows.values.toList().forEach { w ->
            if (w.currentTaskId == taskId) {
                runCatching { w.onTaskOwnershipLost() }
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
            EntryProtocolIdentity.peers.recipients().forEach { target ->
                UserScopedBroadcasts.send(systemContext, Intent(Protocol.ACTION_MANAGED_TASKS).setPackage(target.packageName)
                    .putExtra(Protocol.EXTRA_MANAGED_TASK_IDS, ids), target.userId)
            }
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug") { "managed tasks -> launcher: [${ids.joinToString()}]" }
            }
        }.onFailure {
            log("reYAMF_debug", "broadcast managed tasks failed", it)
        }
    }

    fun switchToSmoothFreeform(taskId: Int, initialBounds: android.graphics.Rect? = null, logicalBounds: android.graphics.Rect? = null): Boolean {
        log("reYAMF_debug") { "switchToSmoothFreeform task=$taskId" }
        WindowSessions.hostedTasks.add(taskId)

        val runningTasks = XposedHelpers.callMethod(Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1) as List<*>
        val taskInfo = runningTasks.find {
            XposedHelpers.getIntField(it, "taskId") == taskId
        } ?: run {
            logWarning("reYAMF_debug", "switchToSmoothFreeform task=$taskId NOT FOUND in tasks")
            return false
        }
        runCatching {
            XposedHelpers.getObjectField(taskInfo, "topActivity") as? ComponentName
        }.getOrNull()?.let { WindowSessions.taskActivities[taskId] = it }

        // 系统窗口后端：认领（organizer/leash）+ 置前 + 恢复可聚焦；小窗不改应用 DPI，
        // 不施加任何密度覆盖。只走后端路径，失败大声报错，不再悄悄回退旧行为
        val opened = windowBackend.openTask(taskId, initialBounds, logicalBounds)
        if (!opened) {
            logError("reYAMF_debug", "ERROR organizer adopt failed task=$taskId (no fallback)")
        }
        return opened
    }

    /**
     * 释放平滑任务回全屏。
     * [toFront]：true=置顶回前（下拉全屏路径）；false=沉到原父级栈底（关闭小窗：
     * 任务转后台、留在最近任务，不抢当前前台）。
     */
    fun switchTaskToFullscreen(taskId: Int, toFront: Boolean = true): Boolean {
        WindowSessions.hostedTasks.remove(taskId)
        WindowSessions.taskActivities.remove(taskId)

        // 系统窗口后端：释放（organizer 优先，未接管任务走旧 WCT 回全屏兜底）
        val released = windowBackend.releaseTask(taskId, toFront)
        // 流程层负责把最新受管集合同步给 Launcher（手势目标豁免；M6 批次2）
        broadcastManagedTaskIds()
        return released
    }

    /** The native task is released while its existing host still owns the visual bridge. */
    fun onHostTaskReleased(host: WindowHost) {
        if (activeWindows[host.windowId] !== host) return
        WindowSessions.hostedTasks.remove(host.currentTaskId)
        WindowSessions.taskActivities.remove(host.currentTaskId)
        broadcastManagedTaskIds()
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
        runMain {
            iOpenCountListenerSet.add(iOpenCountListener)
            runCatching { iOpenCountListener.onUpdate(openWindowCount) }
                .onFailure { iOpenCountListenerSet.remove(iOpenCountListener) }
        }
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener?) {
        runMain { iOpenCountListenerSet.remove(iOpenCountListener) }
    }

    override fun currentToWindow() {
        runMain {
            // The always-on-top presentation root is visible too; it is never an app candidate.
            val task = getTopRootTask(0, windowBackend.managedTaskIds().toSet()) ?: return@runMain
            if (!windowBackend.isHomeTask(task)) {
                createWindow(StartCmd(taskId = task.taskId))
            }
        }
    }

    override fun resetAllWindow() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
            UserScopedBroadcasts.send(systemContext, Intent(Protocol.ACTION_RESET_ALL_WINDOW).setPackage(systemContext.packageName), 0)
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

    private fun launcherSender(receiver: BroadcastReceiver, intent: Intent): UserScopedBroadcasts.Sender? =
        runCatching { UserScopedBroadcasts.sender(systemContext, receiver, intent.getStringExtra(Protocol.EXTRA_SOURCE_PACKAGE)) }
            .onFailure { logWarning("reYAMF_EntryV2", "cannot verify launcher transport address", it) }.getOrNull()

    private fun sendEntryCapabilities(peer: com.buildsession.betterYAMF.xposed.adapters.common.system.EntryPeerRegistry.Peer,
        queryId: Long) {
        UserScopedBroadcasts.send(systemContext, Intent(Protocol.ACTION_ENTRY_CAPABILITIES).setPackage(peer.packageName)
            .putExtra(Protocol.EXTRA_ENTRY_PROTOCOL,
                if (windowBackend.supportsRetainedEntry && peer.asyncSnapshot) Protocol.ENTRY_PROTOCOL else 0)
            .putExtra(Protocol.EXTRA_ASYNC_SNAPSHOT, true)
            .putExtra(Protocol.EXTRA_LEGACY_ENTRY, windowBackend.supportsLegacyEntry)
            .putExtra(Protocol.EXTRA_SOURCE_EPOCH, peer.epoch)
            .putExtra(Protocol.EXTRA_DESTINATION_EPOCH, EntryProtocolIdentity.epoch)
            .putExtra(Protocol.EXTRA_CAPABILITY_QUERY_ID, queryId), peer.userId)
    }

    private val OpenInYAMFBroadcastReceiver: BroadcastReceiver.(Context, Intent) -> Unit =
        entryReceiver@ { _: Context, intent: Intent ->
            val sourceUser = UserScopedBroadcasts.deliveryUser(this)
            val taskId = intent.getIntExtra(Protocol.EXTRA_TASK_ID, -1)
            val sourcePackage = intent.getStringExtra(Protocol.EXTRA_SOURCE_PACKAGE)
            val handoffId = intent.getLongExtra(Protocol.EXTRA_HANDOFF_ID, 0L)
            val base = StartCmd(
                componentName = intent.getParcelableExtra(Protocol.EXTRA_COMPONENT_NAME, ComponentName::class.java),
                userId = intent.getIntExtra(Protocol.EXTRA_USER_ID, sourceUser), taskId = taskId,
                openAsMini = intent.getBooleanExtra(Protocol.EXTRA_FROM_DROP_ZONE, false),
                dropX = intent.getIntExtra(Protocol.EXTRA_DROP_X, -1), dropY = intent.getIntExtra(Protocol.EXTRA_DROP_Y, -1),
                sourceBounds = intent.getParcelableExtra(Protocol.EXTRA_SOURCE_BOUNDS, android.graphics.Rect::class.java),
                sourcePackage = sourcePackage, handoffId = handoffId,
                launcherRotation = intent.getIntExtra(Protocol.EXTRA_LAUNCHER_ROTATION, -1).takeIf { it in 0..3 } ?: -1,
                sourceEpoch = intent.getLongExtra(Protocol.EXTRA_SOURCE_EPOCH, 0L), sourceUserId = sourceUser)
            if (!EntryProtocolIdentity.acceptsDestination(intent, sourceUser)) {
                logWarning("reYAMF_EntryV2", "reject stale destination/peer entry")
                reportEntry(base, CommandStatus.FAILED)
                return@entryReceiver
            }
            val source = intent.getIntExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_UNSPECIFIED)
            val handoffComplete = intent.getBooleanExtra(Protocol.EXTRA_RECENTS_HANDOFF_COMPLETE, false)
            fun proceed(command: StartCmd) {
                runCatching { createWindow(command) }.onFailure {
                    log("reYAMF_EntryV2", "window creation failed task=$taskId handoff=$handoffId", it)
                    rejectEntry(command)
                }
            }
            if (intent.hasExtra(Protocol.EXTRA_ENTRY_SNAPSHOT_TOKEN)) {
                // Validate metadata/capability/address BEFORE asking another process for pixels.
                val sender = launcherSender(this, intent)
                val peer = sourcePackage?.let { EntryProtocolIdentity.peers.peer(it, sourceUser) }
                if (sender == null || peer?.asyncSnapshot != true || !base.openAsMini || taskId <= 0 || handoffId <= 0L ||
                    base.sourceBounds?.isEmpty != false || !windowBackend.supportsRetainedEntry ||
                    !intent.getBooleanExtra(Protocol.EXTRA_ASYNC_SNAPSHOT, false) ||
                    intent.getIntExtra(Protocol.EXTRA_ENTRY_PROTOCOL, 0) != Protocol.ENTRY_PROTOCOL) {
                    logWarning("reYAMF_EntryV2", "reject entry transport task=$taskId handoff=$handoffId")
                    reportEntry(base, CommandStatus.FAILED)
                    return@entryReceiver
                }
                val key = PendingEntryRequests.Key(sender.packageName, sender.userId, base.sourceEpoch, taskId, handoffId)
                val ticket = entryRequests.begin(key) { reportEntry(base, CommandStatus.SUPERSEDED) }
                    ?: return@entryReceiver // Already cancelled, superseded or delivered.
                try {
                    val operation = EntrySnapshotTransport.request(intent, sender.uid, entryHandler) { value ->
                        if (!entryRequests.take(ticket)) {
                            if (value is EntrySnapshotTransport.Result.Ready) value.frame.close()
                            return@request
                        }
                        when (value) {
                            is EntrySnapshotTransport.Result.Ready -> {
                                val frame = value.frame
                                val age = SystemClock.elapsedRealtimeNanos() - frame.capturedAtNanos
                                if (!EntryProtocolIdentity.acceptsDestination(intent, sourceUser) ||
                                    frame.bounds != base.sourceBounds || age !in 0L..5_000_000_000L) {
                                    frame.close()
                                    reportEntry(base, CommandStatus.SUPERSEDED)
                                } else {
                                    log("reYAMF_EntryV2") { "snapshot received task=$taskId handoff=$handoffId user=$sourceUser ageMs=${age / 1_000_000L}" }
                                    proceed(base.copy(entrySnapshot = frame, awaitSourceRelease = true))
                                }
                            }
                            is EntrySnapshotTransport.Result.Failed -> {
                                logError("reYAMF_EntryV2", "snapshot transfer failed task=$taskId handoff=$handoffId", value.error)
                                reportEntry(base, CommandStatus.FAILED)
                            }
                            EntrySnapshotTransport.Result.Superseded -> reportEntry(base, CommandStatus.SUPERSEDED)
                        }
                    }
                    entryRequests.attach(ticket, operation)
                } catch (error: Throwable) {
                    if (entryRequests.take(ticket)) {
                        logError("reYAMF_EntryV2", "snapshot request failed task=$taskId handoff=$handoffId", error)
                        reportEntry(base, CommandStatus.FAILED)
                    }
                }
            } else {
                val command = base.copy(awaitSourceRelease = base.openAsMini && handoffComplete)
                if (source == Protocol.SOURCE_RECENT && !handoffComplete) {
                    // Negotiated legacy fallback completes native UP before requesting entry.
                    windowBackend.injectHomeKey()
                    awaitTaskSurfaceStable(taskId) { stable ->
                        if (stable) proceed(command)
                        else logWarning("reYAMF_debug", "cancel recents entry: task=$taskId still belongs to a system transition")
                    }
                } else proceed(command)
            }
        }

    /**
     * 等待任务表面静止（投放登场起跑闸）。
     * 轮询 32ms、上限 [DROP_GATE_TIMEOUT_MS]：超时保留原任务，不在转场未结束时强行认领。
     */
    private fun awaitTaskSurfaceStable(
        taskId: Int,
        timeoutMs: Long = DROP_GATE_TIMEOUT_MS,
        onReady: (Boolean) -> Unit
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startAt = android.os.SystemClock.uptimeMillis()
        fun poll() {
            val stable = runCatching { windowBackend.isTaskSurfaceStable(taskId) }.getOrDefault(false)
            val elapsed = android.os.SystemClock.uptimeMillis() - startAt
            if (stable || elapsed >= timeoutMs) {
                log("reYAMF_debug") { "drop gate: task=$taskId stable=$stable waited=${elapsed}ms" }
                onReady(stable)
            } else {
                handler.postDelayed({ poll() }, DROP_GATE_POLL_MS)
            }
        }
        handler.post { poll() }
    }
}
