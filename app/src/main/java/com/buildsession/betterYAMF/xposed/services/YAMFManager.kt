package com.buildsession.betterYAMF.xposed.services

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.model.Config
import com.buildsession.betterYAMF.common.model.StartCmd
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.common.ui.componentName
import com.buildsession.betterYAMF.xposed.IAppIconCallback
import com.buildsession.betterYAMF.xposed.IAppListCallback
import com.buildsession.betterYAMF.xposed.IOpenCountListener
import com.buildsession.betterYAMF.xposed.IYAMFManager
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
import rikka.hidden.compat.ActivityManagerApis
import java.io.File


object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "BetterYAMFManager"

    const val ACTION_GET_LAUNCHER_CONFIG = "com.buildsession.betterYAMF.ACTION_GET_LAUNCHER_CONFIG"
    const val ACTION_OPEN_APP = "com.buildsession.betterYAMF.action.OPEN_APP"
    private const val ACTION_CURRENT_TO_WINDOW = "com.buildsession.betterYAMF.action.CURRENT_TO_WINDOW"
    private const val ACTION_OPEN_APP_LIST = "com.buildsession.betterYAMF.action.OPEN_APP_LIST"
    const val ACTION_OPEN_IN_YAMF = "com.buildsession.betterYAMF.ACTION_OPEN_IN_YAMF"
    const val ACTION_RECENTS_GESTURE = "com.buildsession.betterYAMF.action.RECENTS_GESTURE"
    const val ACTION_MANAGED_TASKS = "com.buildsession.betterYAMF.action.MANAGED_TASKS"
    const val ACTION_QUERY_MANAGED_TASKS = "com.buildsession.betterYAMF.action.QUERY_MANAGED_TASKS"
    const val ACTION_DUMP_GEOMETRY = "com.buildsession.betterYAMF.action.DUMP_GEOMETRY"

    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_USER_ID = "userId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_SUPPRESS_MS = "suppressMs"
    const val EXTRA_FROM_DROP_ZONE = "fromDropZone"
    const val EXTRA_MANAGED_TASK_IDS = "managedTaskIds"

    private const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_RECENT = 1
    const val SOURCE_TASKBAR = 2
    const val SOURCE_POPUP = 3

    const val CHROME_WINDOW_TITLE = "reYAMF_chrome"

    private const val LEGACY_DEFAULT_WINDOW_HEIGHT = 380

    private val activeWindows = mutableMapOf<Int, WindowHost>()
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
                                w.onTaskVisibilityChanged(taskInfo.isVisible)
                            }
                        }

                        // 若首帧时任务已是 FREEFORM 的任务尚未登记（例如从菜单直启），补登记并立即套用缩放链路；
                        // 切回 FULLSCREEN 的任务则移出登记。
                        val windowingMode = windowBackend.windowingModeOf(taskInfo)
                        if (windowingMode == WINDOWING_MODE_FREEFORM) {
                            val topActivity = taskInfo.topActivity
                            if (topActivity != null) {
                                val pendingWindow = pendingSmoothFreeformApps[topActivity]
                                if (pendingWindow != null) {
                                    log("reYAMF_debug", "claim task=${taskInfo.taskId} activity=$topActivity")
                                    WindowSessions.hostedTasks.add(taskInfo.taskId)
                                    pendingSmoothFreeformApps.remove(topActivity)
                                    // Trigger a config change to apply spoofing/scaling immediately
                                    switchToSmoothFreeform(taskInfo.taskId)
                                    pendingWindow.currentTaskId = taskInfo.taskId
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
                    "onTaskInfoChanged" -> {
                        val taskInfo = args[0] as android.app.ActivityManager.RunningTaskInfo
                        activeWindows.values.forEach { w ->
                            if (w.currentTaskId == taskInfo.taskId) {
                                w.onTaskVisibilityChanged(taskInfo.isVisible)
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
                val visible = info?.let { XposedHelpers.getBooleanField(it, "isVisible") } ?: true
                if (BuildConfig.DEBUG) {
                    log("reYAMF_debug", "visibility task=${w.currentTaskId} found=${info != null} visible=$visible")
                }
                w.onTaskVisibilityChanged(visible)
            }
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
        systemContext.registerReceiver(ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)
        systemContext.registerReceiver(ACTION_RECENTS_GESTURE) { _, intent ->
            val suppressMs = intent.getLongExtra(EXTRA_SUPPRESS_MS, 0L)
            windowBackend.suppressRecentsGestureFor(suppressMs)
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug", "recents gesture suppress ${suppressMs}ms")
            }
        }
        systemContext.registerReceiver(ACTION_QUERY_MANAGED_TASKS) { _, _ ->
            broadcastManagedTaskIds()
        }
        systemContext.registerReceiver(ACTION_DUMP_GEOMETRY) { _, _ ->
            activeWindows.values.toList().forEach { w ->
                runCatching { w.dumpGeometry() }
            }
        }
        systemContext.registerReceiver(ACTION_CURRENT_TO_WINDOW) { _, _ ->
            currentToWindow()
        }
        systemContext.registerReceiver(ACTION_OPEN_APP_LIST) { _, _ ->
            val componentName = ComponentName("com.buildsession.betterYAMF", "com.buildsession.betterYAMF.manager.applist.AppListWindow")
            val intent = Intent().setComponent(componentName)
            AndroidAppHelper.currentApplication().startService(intent)
        }
        systemContext.registerReceiver(ACTION_OPEN_APP) { _, intent ->
            val componentName = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT_NAME)
                ?: return@registerReceiver
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            createWindow(StartCmd(componentName = componentName, userId = userId))
        }
        systemContext.registerReceiver(ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
            ActivityManagerApis.broadcastIntent(Intent(HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                // log(TAG, "send config: ${config.hookLauncher}")
                putExtra(HookLauncher.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                putExtra(HookLauncher.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
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
                switchToSmoothFreeform(taskId)
                window.currentTaskId = taskId
            } else if (startCmd?.componentName != null) {
                pendingSmoothFreeformApps[startCmd.componentName] = window
                // Start new activity in freeform
                val options = android.app.ActivityOptions.makeBasic()
                XposedHelpers.callMethod(options, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
                
                val intent = Intent().apply {
                    component = startCmd.componentName
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                
                val userHandle = XposedHelpers.callStaticMethod(
                    android.os.UserHandle::class.java,
                    "of",
                    startCmd.userId ?: 0
                )
                
                XposedHelpers.callMethod(
                    Instances.systemContext,
                    "startActivityAsUser",
                    intent,
                    options.toBundle(),
                    userHandle
                )
            }
        }
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
            val intent = Intent(ACTION_MANAGED_TASKS)
                .setPackage("com.android.launcher3")
                .putExtra(EXTRA_MANAGED_TASK_IDS, ids)
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

        // 系统窗口后端：认领（organizer/leash）+ 置前 + 恢复可聚焦；
        // 只走后端路径，失败大声报错，不再悄悄回退旧行为
        if (!windowBackend.openTask(taskId)) {
            log("reYAMF_debug", "ERROR organizer adopt failed task=$taskId (no fallback)")
        }
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
            systemContext.sendBroadcast(Intent(AppWindow.ACTION_RESET_ALL_WINDOW))
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
            val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
            val componentName =
                intent.getParcelableExtra(EXTRA_COMPONENT_NAME, ComponentName::class.java)
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            val source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_UNSPECIFIED)
            val fromDropZone = intent.getBooleanExtra(EXTRA_FROM_DROP_ZONE, false)
            log("reYAMF_debug", "openInYAMF task=$taskId source=$source fromZone=$fromDropZone component=$componentName")
            createWindow(StartCmd(componentName, userId, taskId, openAsMini = fromDropZone))

            // TODO: better way to close recents
            if (source == SOURCE_RECENT) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { windowBackend.injectHomeKey() }
                }, HOME_INJECT_DELAY_MS)
            }
        }
}
