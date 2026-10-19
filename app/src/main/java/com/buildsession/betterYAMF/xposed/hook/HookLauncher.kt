package com.buildsession.betterYAMF.xposed.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findAllMethods
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findField
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.getObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.paramCount
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.xposed.hook.launcher.LauncherInputAnchors
import com.buildsession.betterYAMF.xposed.hook.launcher.LauncherCoordinateMapper
import com.buildsession.betterYAMF.xposed.hook.launcher.LauncherMotionCoordinates
import com.buildsession.betterYAMF.xposed.hook.launcher.LauncherOverlayDisplay
import com.buildsession.betterYAMF.xposed.hook.launcher.ManagedTaskFilter
import com.buildsession.betterYAMF.xposed.hook.launcher.RecentsDropHandoff
import com.buildsession.betterYAMF.xposed.hook.launcher.RecentsDropZoneView
import com.buildsession.betterYAMF.xposed.hook.launcher.RecentsEntryCapture
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.window.core.RecentsGesturePolicy
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy


class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "BetterYAMF_HookLauncher"
    }

    private var isRegistered = false

    private var mDropZoneView: RecentsDropZoneView? = null
    private var mOverlayDisplay: LauncherOverlayDisplay? = null
    private val mDropZoneRect = Rect()
    private val mMainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var mScreenHeight = 0
    private var mScreenWidth = 0
    private var mStartY = 0f
    private var mIsShowingZone = false
    private var mCurrentTaskId = -1

    private var mCurrentRotation = 0
    private var mIsPotentialSwipeUp = false
    private var mSuppressArmed = false

    private fun cancelDropTarget() {
        mDropZoneView?.visibility = View.GONE
        mDropZoneView?.sourcePose = null
        mIsShowingZone = false
        mIsPotentialSwipeUp = false
        mSuppressArmed = false
        mCurrentTaskId = -1
    }

    private fun updateDropZoneLayout(frame: LauncherOverlayDisplay.Frame) {
        val zone = RecentsGesturePolicy.dropZone(frame.width, frame.height)
        val changed = mDropZoneRect.left != zone.left || mDropZoneRect.top != zone.top ||
            mDropZoneRect.right != zone.right || mDropZoneRect.bottom != zone.bottom
        mDropZoneRect.set(zone.left, zone.top, zone.right, zone.bottom)
        if (changed) log(TAG) { "drop target viewport=${frame.width}x${frame.height} rotation=${frame.rotation} bounds=$mDropZoneRect" }
        val view = mDropZoneView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (lp.width == zone.width && lp.height == zone.height) return
        lp.width = zone.width
        lp.height = zone.height
        // Native gravity anchors the edge; never rotate an old rectangle into a different corner.
        mOverlayDisplay?.windowManager?.updateViewLayout(view, lp)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXHelperInit.initHandleLoadPackage(lpparam)
        
        // Pixel Launcher: com.google.android.apps.nexuslauncher
        // Launcher3: com.android.launcher3
        val isLauncher = lpparam.packageName == "com.android.launcher3" || 
                        lpparam.packageName == "com.google.android.apps.nexuslauncher" ||
                        lpparam.packageName.contains("launcher")
        
        if (!isLauncher) return
        // log(TAG, "Handling package: ${lpparam.packageName} (Process: ${lpparam.processName})")

        // 尝试加载 TouchInteractionService，Pixel Launcher 可能会继承或直接使用该类
        val tisClass = loadClassOrNull("com.android.quickstep.TouchInteractionService")
        if (tisClass != null) {
            // log(TAG, "Found TouchInteractionService in ${lpparam.packageName}")
            findMethod(tisClass) { name == "onCreate" }.hookAfter { param ->
                // log(TAG, "TouchInteractionService#onCreate hooked in ${lpparam.packageName}")
                val context = param.thisObject as android.app.Service
                RecentsDropHandoff.install(context)

                // M6 批次2：委托独立组件（接收器注册 + 主动查询当前受管任务集合）
                ManagedTaskFilter.ensureReceiverRegistered(context)
                
                // 确保在主线程执行 UI 操作
                mMainHandler.post {
                    try {
                        val overlay = mOverlayDisplay ?: LauncherOverlayDisplay(context).also { mOverlayDisplay = it }
                        val frame = checkNotNull(overlay.frame()) { "Overlay display is not configured" }
                        updateDropZoneLayout(frame)
                        val zoneWidth = mDropZoneRect.width()
                        val zoneHeight = mDropZoneRect.height()

                        if (mDropZoneView == null) {
                            mDropZoneView = RecentsDropZoneView(overlay.context).apply {
                                visibility = View.GONE
                            }
                            updateDropZoneColor(false)

                            val lp = WindowManager.LayoutParams().apply {
                                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                format = PixelFormat.TRANSLUCENT
                                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                width = zoneWidth
                                height = zoneHeight
                                gravity = Gravity.TOP or Gravity.RIGHT
                                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            }

                            overlay.windowManager.addView(mDropZoneView, lp)
                            // log(TAG, "Successfully added mDropZoneView to WindowManager")
                        }
                    } catch (e: Exception) {
                        mDropZoneView = null
                        mOverlayDisplay?.close()
                        mOverlayDisplay = null
                        logWarning(TAG, "Drop overlay unavailable; preserve native gesture", e)
                    }
                }
            }

            // M6 批次2：手势目标任务过滤——委托独立组件（把受管浮窗任务从 TopTaskTracker
            // 的判定中剔除，使上滑/快速切换等手势永远以"下方应用"为目标）。
            ManagedTaskFilter.installTopTaskFilters(lpparam.classLoader)

            val inputAnchor = LauncherInputAnchors.resolve(lpparam.classLoader)
            if (inputAnchor != null) {
            log(TAG) { "Launcher input anchor: ${inputAnchor.displayName}" }
            val inputClass = lpparam.classLoader.loadClass(inputAnchor.className)
            XposedBridge.hookAllMethods(inputClass, "onInputEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args[0] as? MotionEvent ?: return
                    val action = event.actionMasked
                    if (action != MotionEvent.ACTION_DOWN && 
                        action != MotionEvent.ACTION_MOVE && 
                        action != MotionEvent.ACTION_UP &&
                        action != MotionEvent.ACTION_CANCEL) return

                    if (action == MotionEvent.ACTION_DOWN) {
                        // Retire the old source before native DOWN can reuse Launcher state,
                        // including when this device cannot normalize the new event's rotation.
                        RecentsDropHandoff.onGestureStarted(XposedHelpers.callMethod(event, "getDisplayId") as Int)
                    }
                    val frame = mOverlayDisplay?.frame()?.takeIf { mDropZoneView != null }
                        ?: run { cancelDropTarget(); return }
                    if (action == MotionEvent.ACTION_DOWN) {
                        mScreenWidth = frame.width
                        mScreenHeight = frame.height
                        mCurrentRotation = frame.rotation
                    }
                    if (mScreenWidth <= 0 || mScreenHeight <= 0) return
                    val point = LauncherMotionCoordinates.point(event, frame.rotation, frame.width, frame.height)
                        ?: run {
                            cancelDropTarget()
                            return
                        }
                    // Only swipe progress stays in DOWN coordinates. Hover and release use the
                    // CURRENT overlay frame, exactly like the visible top-right target.
                    val gesturePoint = LauncherCoordinateMapper.rotatePoint(point.x, point.y,
                        frame.rotation, mCurrentRotation, mScreenWidth, mScreenHeight)
                    val correctedX = point.x
                    val correctedY = point.y
                    updateDropZoneLayout(frame)

                    val isInZone = correctedX >= mDropZoneRect.left && correctedX < mDropZoneRect.right &&
                        correctedY >= mDropZoneRect.top && correctedY < mDropZoneRect.bottom

                    when (action) {
                        MotionEvent.ACTION_DOWN -> {
                            mStartY = gesturePoint.y
                            mIsShowingZone = false
                            mCurrentTaskId = -1
                            // 只有从底部 5% 区域开始的滑动才被认为是潜在的上划手势
                            mIsPotentialSwipeUp = gesturePoint.y > mScreenHeight * 0.95
                            mSuppressArmed = mIsPotentialSwipeUp
                            if (mSuppressArmed) {
                                suppressRecentsGesture(900L)
                            }
                            // log(TAG, "ACTION_DOWN at ($correctedX, $correctedY), potential=$mIsPotentialSwipeUp")
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (!mIsPotentialSwipeUp) return
                            
                            val diffY = mStartY - gesturePoint.y
                            val threshold = mScreenHeight * 0.2
                            
                            if (diffY > threshold) {
                                if (!mIsShowingZone) {
                                    // 仅在达到阈值时才进行反射调用，大幅减少性能损耗
                                     var capturedTaskId = -1
                                     runCatching {
                                         val gestureState = inputAnchor.gestureStateOf(param.thisObject, event)
                                         if (gestureState != null) {
                                            val taskId = XposedHelpers.callMethod(gestureState, "getTopRunningTaskId") as Int
                                            val runningTask = XposedHelpers.callMethod(gestureState, "getRunningTask")
                                            val isHomeTask = if (runningTask != null) {
                                                XposedHelpers.callMethod(runningTask, "isHomeTask") as Boolean
                                            } else false
                                            
                                            if (taskId != -1 && !isHomeTask) {
                                                // 增强校验：不仅要 Launcher 获得焦点，还要确保 Launcher 的 Activity 处于 Resumed 状态
                                                // 这能有效排除沉浸模式下仅划出导航栏的情况
                                                val isLauncherActive = runCatching {
                                                    val activityThread = XposedHelpers.callStaticMethod(
                                                        Class.forName("android.app.ActivityThread"),
                                                        "currentActivityThread"
                                                    )
                                                    val mActivities = XposedHelpers.getObjectField(activityThread, "mActivities") as Map<*, *>
                                                    
                                                    // 检查当前进程中是否有任何 Activity 是处于 Resumed 状态的
                                                    mActivities.values.any { activityRecord ->
                                                        val paused = XposedHelpers.getBooleanField(activityRecord, "paused")
                                                        val activity = XposedHelpers.getObjectField(activityRecord, "activity") as? Activity
                                                        activity != null && !activity.isFinishing && !paused
                                                    }
                                                }.getOrDefault(false)

                                                if (isLauncherActive || RecentsDropHandoff.isSourceVisible(gestureState)) {
                                                    capturedTaskId = taskId
                                                }
                                            }
                                        }
                                    }

                                    if (capturedTaskId != -1) {
                                        mCurrentTaskId = capturedTaskId
                                        mIsShowingZone = true
                                        val swipe = runCatching {
                                            val consumer = inputAnchor.inputConsumerOf(param.thisObject, event)
                                            val active = XposedHelpers.callMethod(consumer, "getActiveConsumerInHierarchy") ?: consumer
                                            XposedHelpers.getObjectField(active, "mInteractionHandler")
                                        }.getOrNull()
                                        val targetId = capturedTaskId
                                        mDropZoneView?.sourcePose = swipe?.let { handler ->
                                            { RecentsEntryCapture.peekPose(handler, targetId) }
                                        }
                                        mDropZoneView?.visibility = View.VISIBLE
                                        log(TAG) { "Swipe up threshold reached with taskId $mCurrentTaskId" }
                                    } else {
                                        // 如果不是有效的任务（例如在桌面），则不再处理后续 MOVE
                                        mIsPotentialSwipeUp = false
                                    }
                                }
                            } else {
                                if (mIsShowingZone) {
                                    mIsShowingZone = false
                                    mMainHandler.post {
                                        mDropZoneView?.visibility = View.GONE
                                    }
                                }
                            }
                            
                            if (mIsShowingZone) {
                                updateDropZoneColor(isInZone)
                            }
                        }

                        MotionEvent.ACTION_UP -> {
                                if (mSuppressArmed) {
                                    mSuppressArmed = false
                                    suppressRecentsGesture(450L)
                                }
                                log(TAG) { "ACTION_UP at ($correctedX, $correctedY), isInZone=$isInZone, isShowing=$mIsShowingZone, taskId=$mCurrentTaskId" }
                                if (mIsShowingZone && isInZone && mCurrentTaskId != -1) {
                                    val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).apply {
                                        setPackage("android")
                                        putExtra(Protocol.EXTRA_TASK_ID, mCurrentTaskId)
                                        putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_RECENT)
                                        putExtra(Protocol.EXTRA_FROM_DROP_ZONE, true)
                                        // 松手点（屏坐标）：小窗登场动画从该点区域收缩飞入磁吸槽位
                                        putExtra(Protocol.EXTRA_DROP_X, correctedX.toInt())
                                        putExtra(Protocol.EXTRA_DROP_Y, correctedY.toInt())
                                    }
                                    val handoff = RecentsDropHandoff.prepare(inputAnchor, param.thisObject, event, mCurrentTaskId)
                                    if (handoff?.begin(AndroidAppHelper.currentApplication(), intent) != true) {
                                        // Preserve normal UP cleanup before the legacy HOME fallback.
                                        mMainHandler.post { AndroidAppHelper.currentApplication().sendBroadcast(intent) }
                                    }
                                }
                                mDropZoneView?.visibility = View.GONE
                                mDropZoneView?.sourcePose = null
                                mIsShowingZone = false
                                mCurrentTaskId = -1
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                if (mSuppressArmed) {
                                    mSuppressArmed = false
                                    suppressRecentsGesture(450L)
                                }
                                // log(TAG, "ACTION_CANCEL")
                                mDropZoneView?.visibility = View.GONE
                                mDropZoneView?.sourcePose = null
                                mIsShowingZone = false
                            }
                        }
                }

            })
            } else {
                log(TAG) { "No launcher input anchor available" }
            }

            findMethod(tisClass) { name == "onDestroy" }.hookBefore {
                RecentsDropHandoff.dispose()
                // log(TAG, "TouchInteractionService#onDestroy hooked")
                mMainHandler.post {
                    mDropZoneView?.let { view ->
                        try {
                            mOverlayDisplay?.windowManager?.removeViewImmediate(view)
                            // log(TAG, "Removed mDropZoneView from WindowManager")
                        } catch (e: Exception) {
                            log(TAG, "Error removing drop zone view", e)
                        }
                        mDropZoneView = null
                    }
                    mOverlayDisplay?.close()
                    mOverlayDisplay = null
                    cancelDropTarget()
                }
            }
        }

        findMethod("com.android.launcher3.Launcher") {
            name == "onCreate"
        }.hookAfter {
            if (!isRegistered) {
                val activity = it.thisObject as Activity
                val application = activity.application
                application.registerReceiver(Protocol.ACTION_RECEIVE_LAUNCHER_CONFIG) { _, intent ->
                    val hookRecent = intent.getBooleanExtra(Protocol.EXTRA_HOOK_RECENT, false)
                    val hookTaskbar = intent.getBooleanExtra(Protocol.EXTRA_HOOK_TASKBAR, false)
                    if (hookRecent) runCatching { hookRecent(lpparam) }.onFailure { e ->
                        log(TAG, "hook recent failed", e) }
                    if (hookTaskbar) runCatching { hookTaskbar(lpparam) }.onFailure { e ->
                        log(TAG, "hook taskbar failed", e) }
                    application.unregisterReceiver(this)
                }
                application.sendBroadcast(Intent(Protocol.ACTION_GET_LAUNCHER_CONFIG).apply {
                    `package` = "android"
                    putExtra("sender", application.packageName)
                })

                isRegistered = true
            }
        }
    }

    private fun hookRecent(lpparam: XC_LoadPackage.LoadPackageParam) {
        // log(TAG, "hooking recent ${lpparam.packageName}")
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "com.android.quickstep.TaskOverlayFactory",
                lpparam.classLoader
            ), "getEnabledShortcuts", object : XC_MethodHook() {
                @SuppressLint("UseCompatLoadingForDrawables")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val taskView = param.args[0] as View
                    val shortcuts = param.result as MutableList<Any>
                    val itemInfo = XposedHelpers.getObjectField(shortcuts[0], "mItemInfo")

                    var task: Any = Unit

                    runCatching {
                        task = XposedHelpers.callMethod(taskView, "getTask")
                    }.onFailure {
                        val taskContainers = XposedHelpers.getObjectField(taskView, "taskContainers") as List<*>
                        val firstContainer = taskContainers[0]
                        task = XposedHelpers.getObjectField(firstContainer, "task")
                    }

                    val activity = taskView.context
                    val key = XposedHelpers.getObjectField(task, "key")
                    val taskId = XposedHelpers.getIntField(key, "id")

                    val userId = XposedHelpers.getIntField(key, "userId")

                    val classRemoteActionShortcut = XposedHelpers.findClass(
                        "com.android.launcher3.popup.RemoteActionShortcut",
                        lpparam.classLoader
                    )

                    val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                    }

                    runCatching {
                        val itemInfoTmp =
                            itemInfo.javaClass.newInstance(args(itemInfo), argTypes(itemInfo.javaClass))
                        val topComponent = XposedHelpers.callMethod(itemInfoTmp, "getTargetComponent") as ComponentName
                        intent.putExtra(Protocol.EXTRA_COMPONENT_NAME, topComponent)
                    }.onFailure {
                        val topComponent = extractComponentInfo(itemInfo.toString()).toString()
                        intent.putExtra(Protocol.EXTRA_COMPONENT_NAME, topComponent)
                    }
                    intent.putExtra(Protocol.EXTRA_TASK_ID, taskId)
                    intent.putExtra(Protocol.EXTRA_USER_ID, userId)
                    intent.putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_RECENT)

                    val action = RemoteAction(
                        Icon.createWithBitmap(
                            moduleRes.getDrawable(R.drawable.ic_picture_in_picture_alt_24, null)
                                .toBitmap()
                        ),
                        moduleRes.getString(R.string.open_with_yamf), // + if (BuildConfig.DEBUG) " ($taskId)" else "",
                        "",
                        PendingIntent.getBroadcast(
                            AndroidAppHelper.currentApplication(),
                            1345,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    val c = classRemoteActionShortcut.constructors[0]
                    val shortcut = when (c.parameterCount) {
                        4 -> c.newInstance(action, activity, itemInfo, null)
                        3 -> c.newInstance(action, activity, itemInfo)
                        else -> {
                            log(
                                TAG,
                                "unknown RemoteActionShortcut constructor: ${c.toGenericString()}"
                            )
                            null
                        }
                    }

                    if (shortcut != null) {
                        shortcuts.add(shortcut)
                    }
                }
            })
    }

    private fun hookTaskbar(lpparam: XC_LoadPackage.LoadPackageParam) {
        // log(TAG, "hooking taskbar ${lpparam.packageName}")
        loadClass("com.android.launcher3.taskbar.TaskbarActivityContext").apply {
            findMethodOrNull { name == "startItemInfoActivity" }
                ?.hookReplace {
                    val infoIntent = it.args[0].invokeMethodAutoAs<Intent>("getIntent")!!
                    val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).apply {
                        setPackage("android")
                        putExtra(Protocol.EXTRA_COMPONENT_NAME, infoIntent.component)
                        putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_TASKBAR)
                    }
                    AndroidAppHelper.currentApplication().sendBroadcast(intent)
                }
            val classWorkspaceiteminfo =
                loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            findMethod { name == "onTaskbarIconClicked" }
                .hookBefore {
                    val tag = it.args[0].invokeMethodAuto("getTag")!!
                    if (classWorkspaceiteminfo.isInstance(tag)) {
                        val infoIntent = tag.invokeMethodAutoAs<Intent>("getIntent")!!
                        val intent = Intent(Protocol.ACTION_OPEN_IN_YAMF).apply {
                            setPackage("android")
                            putExtra(Protocol.EXTRA_COMPONENT_NAME, infoIntent.component)
                            putExtra(Protocol.EXTRA_SOURCE, Protocol.SOURCE_TASKBAR)
                        }
                        AndroidAppHelper.currentApplication().sendBroadcast(intent)
                        it.result = Unit
                    }
                }
        }

    }

    private fun suppressRecentsGesture(durationMs: Long) {
        runCatching {
            val intent = Intent(Protocol.ACTION_RECENTS_GESTURE).apply {
                setPackage("android")
                putExtra(Protocol.EXTRA_SUPPRESS_MS, durationMs)
            }
            AndroidAppHelper.currentApplication().sendBroadcast(intent)
        }.onFailure {
            log(TAG, "suppressRecentsGesture failed", it)
        }
    }

    private fun updateDropZoneColor(isInZone: Boolean) {
        mDropZoneView?.setActive(isInZone)
    }

    private fun extractComponentInfo(input: String): ComponentName? {
        val regex = Regex("""ComponentInfo\{([^/]+)/([^}]+)\}""")
        val matchResult = regex.find(input)
        return if (matchResult != null) {
            val (packageName, className) = matchResult.destructured
            ComponentName(packageName, className)
        } else {
            null
        }
    }
}
