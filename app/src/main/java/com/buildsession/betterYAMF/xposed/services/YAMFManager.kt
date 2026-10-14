package com.buildsession.betterYAMF.xposed.services

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.model.Config
import com.buildsession.betterYAMF.common.model.StartCmd
import com.buildsession.betterYAMF.common.model.resolveWindowSizePx
import com.buildsession.betterYAMF.common.runMain
import com.buildsession.betterYAMF.xposed.IAppIconCallback
import com.buildsession.betterYAMF.xposed.IAppListCallback
import com.buildsession.betterYAMF.xposed.IOpenCountListener
import com.buildsession.betterYAMF.xposed.IYAMFManager
import com.buildsession.betterYAMF.xposed.hook.HookLauncher
import com.buildsession.betterYAMF.window.backend.WindowBackend
import com.buildsession.betterYAMF.window.backend.WindowBackendCallbacks
import com.buildsession.betterYAMF.window.backend.WindowBackends
import com.buildsession.betterYAMF.window.host.AppWindow
import com.buildsession.betterYAMF.xposed.hook.organizer.SmoothWindowBackend
import com.buildsession.betterYAMF.xposed.utils.Instances
import com.buildsession.betterYAMF.xposed.utils.Instances.systemContext
import com.buildsession.betterYAMF.xposed.utils.Instances.systemUiContext
import com.buildsession.betterYAMF.xposed.utils.componentName
import com.buildsession.betterYAMF.xposed.utils.createContext
import com.buildsession.betterYAMF.xposed.utils.dpToPx
import com.buildsession.betterYAMF.xposed.utils.getActivityInfoCompat
import com.buildsession.betterYAMF.xposed.utils.getTopRootTask
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import com.qauxv.ui.CommonContextWrapper
import de.robv.android.xposed.XposedHelpers
import rikka.hidden.compat.ActivityManagerApis
import java.io.ByteArrayOutputStream
import java.io.File


object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "BetterYAMFManager"

    const val ACTION_GET_LAUNCHER_CONFIG = "com.buildsession.betterYAMF.ACTION_GET_LAUNCHER_CONFIG"
    const val ACTION_OPEN_APP = "com.buildsession.betterYAMF.action.OPEN_APP"
    private const val ACTION_CURRENT_TO_WINDOW = "com.buildsession.betterYAMF.action.CURRENT_TO_WINDOW"
    private const val ACTION_OPEN_APP_LIST = "com.buildsession.betterYAMF.action.OPEN_APP_LIST"
    const val ACTION_OPEN_IN_YAMF = "com.buildsession.betterYAMF.ACTION_OPEN_IN_YAMF"
    const val ACTION_RECENTS_GESTURE = "com.buildsession.betterYAMF.action.RECENTS_GESTURE"

    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_USER_ID = "userId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_SUPPRESS_MS = "suppressMs"
    const val EXTRA_FROM_DROP_ZONE = "fromDropZone"

    private const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_RECENT = 1
    const val SOURCE_TASKBAR = 2
    const val SOURCE_POPUP = 3

    const val CHROME_WINDOW_TITLE = "reYAMF_chrome"

    private const val LEGACY_DEFAULT_WINDOW_HEIGHT = 380

    val windowList = mutableListOf<Int>()
    private val activeWindows = mutableMapOf<Int, AppWindow>()
    val smoothFreeformTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val smoothFreeformBounds = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Rect>()
    val smoothCornerRadiusPx = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    val smoothTaskActivities = java.util.concurrent.ConcurrentHashMap<Int, ComponentName>()
    val chromeTouchHoles = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Rect>()
    val pendingSmoothFreeformApps = mutableMapOf<ComponentName, AppWindow>()
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
                        val windowingMode = taskWindowingMode(taskInfo)
                        if (windowingMode == WINDOWING_MODE_FREEFORM) {
                            val topActivity = taskInfo.topActivity
                            if (topActivity != null) {
                                val pendingWindow = pendingSmoothFreeformApps[topActivity]
                                if (pendingWindow != null) {
                                    log("reYAMF_debug", "claim task=${taskInfo.taskId} activity=$topActivity")
                                    smoothFreeformTasks.add(taskInfo.taskId)
                                    pendingSmoothFreeformApps.remove(topActivity)
                                    // Trigger a config change to apply spoofing/scaling immediately
                                    switchToSmoothFreeform(taskInfo.taskId)
                                    pendingWindow.currentTaskId = taskInfo.taskId
                                    pendingWindow.onTaskMovedToFront(taskInfo)
                                }
                            }
                        } else if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                            smoothFreeformTasks.remove(taskInfo.taskId)
                            smoothFreeformBounds.remove(taskInfo.taskId)
                            smoothCornerRadiusPx.remove(taskInfo.taskId)
                            smoothTaskActivities.remove(taskInfo.taskId)
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
                        smoothFreeformTasks.remove(taskId)
                        smoothFreeformBounds.remove(taskId)
                        smoothCornerRadiusPx.remove(taskId)
                        smoothTaskActivities.remove(taskId)
                        // 过滤：只有当被移除的任务 ID 确实属于某个小窗时才触发销
                        activeWindows.values.toList().forEach { window ->
                            if (window.currentTaskId == taskId) {
                                window.onDestroy()
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

    @Volatile
    var recentsGestureSuppressUntilMs = 0L

    @Volatile
    var lastTouchDownAtMs = 0L

    @Volatile
    var lastTouchUpAtMs = 0L

    /** 是否正有手指按在屏幕上（用于把回置顶推迟到手势结束，避免中途改窗口栈把进行中的手势抢走） */
    fun isTouchActive(): Boolean {
        val last = lastTouchDownAtMs
        val up = lastTouchUpAtMs
        return last > up && android.os.SystemClock.uptimeMillis() - last < 1500
    }

    internal fun taskWindowingMode(taskInfo: Any): Int = runCatching {
        val windowConfig = XposedHelpers.getObjectField(
            XposedHelpers.getObjectField(taskInfo, "configuration"),
            "windowConfiguration"
        )
        XposedHelpers.callMethod(windowConfig, "getWindowingMode") as Int
    }.getOrDefault(0)

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
            recentsGestureSuppressUntilMs = SystemClock.uptimeMillis() + suppressMs
            if (BuildConfig.DEBUG) {
                log("reYAMF_debug", "recents gesture suppress ${suppressMs}ms")
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
                putExtra(HookLauncher.EXTRA_HOOK_POPUP, config.hookLauncher.hookPopup)
                putExtra(HookLauncher.EXTRA_HOOK_TRANSIENT_TASKBAR, config.hookLauncher.hookTransientTaskbar)
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

    fun addWindow(id: Int, window: AppWindow) {
        windowList.add(0, id)
        activeWindows[id] = window
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

    fun removeWindow(id: Int) {
        windowList.remove(id)
        activeWindows.remove(id)
        
        if (activeWindows.isEmpty() && isTaskStackListenerRegistered) {
            runCatching {
                Instances.activityTaskManager.unregisterTaskStackListener(taskStackListener)
                isTaskStackListenerRegistered = false
                // log(TAG, "Global TaskStackListener unregistered (no active windows)")
            }
        }
    }

    fun isTop(id: Int) = if (windowList.isNotEmpty()) windowList[0] == id else true

    fun moveToTop(id: Int) {
        windowList.remove(id)
        windowList.add(0, id)
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
            smoothFreeformTasks.add(taskId)
        }

        AppWindow(
            CommonContextWrapper.createAppCompatContext(systemUiContext.createContext())
        ) { window, windowId ->
            addWindow(windowId, window)

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
    }

    /** organizer 路径：任务已消失（含最近任务划掉），销毁对应外壳 */
    fun onOrganizerTaskVanished(taskId: Int) {
        activeWindows.values.toList().forEach { w ->
            if (w.currentTaskId == taskId) {
                runCatching { w.onDestroy() }
            }
        }
    }

    fun switchToSmoothFreeform(taskId: Int) {
        log("reYAMF_debug", "switchToSmoothFreeform task=$taskId")
        smoothFreeformTasks.add(taskId)

        val runningTasks = XposedHelpers.callMethod(Instances.activityTaskManager, "getTasks", Int.MAX_VALUE, false, false, -1) as List<*>
        val taskInfo = runningTasks.find {
            XposedHelpers.getIntField(it, "taskId") == taskId
        } ?: run {
            log("reYAMF_debug", "switchToSmoothFreeform task=$taskId NOT FOUND in tasks")
            return
        }
        runCatching {
            XposedHelpers.getObjectField(taskInfo, "topActivity") as? ComponentName
        }.getOrNull()?.let { smoothTaskActivities[taskId] = it }

        // 系统窗口后端：认领（organizer/leash）+ 置前 + 恢复可聚焦；
        // 只走后端路径，失败大声报错，不再悄悄回退旧行为
        if (!windowBackend.openTask(taskId)) {
            log("reYAMF_debug", "ERROR organizer adopt failed task=$taskId (no fallback)")
        }
    }

    fun switchTaskToFullscreen(taskId: Int): Boolean {
        smoothFreeformTasks.remove(taskId)
        smoothFreeformBounds.remove(taskId)
        smoothCornerRadiusPx.remove(taskId)
        smoothTaskActivities.remove(taskId)

        // 系统窗口后端：释放（organizer 优先，未接管任务走旧 WCT 回全屏兜底）
        return windowBackend.releaseTask(taskId)
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

    override fun getAppList(): List<AppInfo?>? {
        return listOf()
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        runMain {
            appInfo?.let {
                createWindow(StartCmd(it.activityInfo.componentName, it.userId))
            }
        }
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        runMain {
            var apps: List<ActivityInfo>
            val showApps: MutableList<AppInfo> = mutableListOf()
            val users = mutableMapOf<Int, String>()
            Instances.userManager.invokeMethodAs<List<UserInfo>>(
                "getUsers",
                args(true, true, true),
                argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            )!!
                .filter { it.isProfile || it.isPrimary }
                .forEach {
                    users[it.id] = it.name
                }

            users.forEach { usr ->
                apps = (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, 0, usr.key
                ).map {
                    (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        0, usr.key
                    )
                }

                apps.forEach { activityInfo ->
                    showApps.add(
                        AppInfo(
                            activityInfo, usr.key, usr.value
                        )
                    )
                }
            }

            showApps.chunked(5).forEach { chunk ->
                callback.onAppListReceived(chunk.toMutableList())
            }

            callback.onAppListFinished()
        }
    }

    //Might be useful in the future
    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        runMain {
            val drawable = appInfo.activityInfo.loadIcon(Instances.packageManager)

            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                is AdaptiveIconDrawable -> {
                    val size = 108
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            callback.onResult(byteArray)
        }
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
                    runCatching {
                        val down = KeyEvent(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_HOME,
                            0
                        ).apply {
                            this.source = InputDevice.SOURCE_KEYBOARD
                            this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                        }
                        val up = KeyEvent(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_HOME,
                            0
                        ).apply {
                            this.source = InputDevice.SOURCE_KEYBOARD
                            this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                        }
                        val r1 = Instances.inputManager.injectInputEvent(down, 0)
                        val r2 = Instances.inputManager.injectInputEvent(up, 0)
                        log("reYAMF_debug", "injectHome done down=$r1 up=$r2")
                    }.onFailure {
                        log("reYAMF_debug", "injectHome failed", it)
                    }
                }, HOME_INJECT_DELAY_MS)
            }
        }
}
