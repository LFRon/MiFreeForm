package com.buildsession.betterYAMF.xposed.hook

import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AndroidAppHelper
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.init.InitFields.moduleRes
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookAfter
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookReplace
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.xposed.hook.launcher.LauncherInputAnchors
import com.buildsession.betterYAMF.xposed.hook.launcher.ManagedTaskFilter
import com.buildsession.betterYAMF.xposed.hook.launcher.RecentsDropHandoff
import com.buildsession.betterYAMF.common.ipc.Protocol
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import com.buildsession.betterYAMF.xposed.utils.registerReceiver
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


class HookLauncher : IXposedHookLoadPackage, IXposedHookZygoteInit {
    companion object {
        const val TAG = "BetterYAMF_HookLauncher"
    }

    private var isRegistered = false

    // The Hook entry can be constructed in Zygote, before the application main Looper exists.
    private val gestureAdapter by lazy { com.buildsession.betterYAMF.xposed.hook.launcher.LauncherGestureAdapter() }

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
            findMethod(tisClass) { name == "onCreate" }.hookAfter {
                gestureAdapter.create(it.thisObject as android.app.Service)
            }
            ManagedTaskFilter.installTopTaskFilters(lpparam.classLoader)
            val inputAnchor = LauncherInputAnchors.resolve(lpparam.classLoader)
            if (inputAnchor != null) {
                log(TAG) { "Launcher input anchor: ${inputAnchor.displayName}" }
                val inputClass = lpparam.classLoader.loadClass(inputAnchor.className)
                NativeHooks.hookAllMethods(inputClass, "onInputEvent", object : NativeHooks.Callback() {
                    override fun beforeHookedMethod(param: NativeHooks.Call) {
                        val event = param.args[0] as? MotionEvent ?: return
                        gestureAdapter.input(inputAnchor, param.thisObject, event)?.let {
                            param.setObjectExtra("miwindow.sourceHandoff", it)
                        }
                    }
                    override fun afterHookedMethod(param: NativeHooks.Call) {
                        (param.getObjectExtra("miwindow.sourceHandoff") as? RecentsDropHandoff.Handoff)
                            ?.inputFinished(param.throwable)
                    }
                })
            } else logWarning(TAG, "No supported launcher input anchor; preserve native input")
            findMethod(tisClass) { name == "onDestroy" }.hookBefore { gestureAdapter.destroy() }
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
        NativeHooks.hookAllMethods(
            XposedHelpers.findClass(
                "com.android.quickstep.TaskOverlayFactory",
                lpparam.classLoader
            ), "getEnabledShortcuts", object : NativeHooks.Callback() {
                @SuppressLint("UseCompatLoadingForDrawables")
                override fun afterHookedMethod(param: NativeHooks.Call) {
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
                            log(TAG) { "unknown RemoteActionShortcut constructor: ${c.toGenericString()}" }
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
