package com.buildsession.betterYAMF.xposed.hook.freeform

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.view.SurfaceControl
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.xposed.services.YAMFManager
import com.buildsession.betterYAMF.xposed.utils.log
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

object FreeformBackend {
    private const val TAG = "reYAMF_FreeformBackend"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    private val diagnostics = BuildConfig.DEBUG
    private val lastLogByTask = ConcurrentHashMap<Int, String>()
    private val snapshotLogged =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    private fun logOnce(taskId: Int, message: String) {
        if (!diagnostics) return
        if (lastLogByTask.put(taskId, message) != message) {
            log(TAG, message)
        }
    }

    private fun logOnceTask(task: Any, message: String) {
        if (!diagnostics) return
        val taskId = runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrDefault(-1)
        logOnce(taskId, message)
    }

    private fun isTaskAnimating(task: Any): Boolean =
        runCatching { XposedHelpers.callMethod(task, "isAnimating") as Boolean }.getOrDefault(false)

    private fun reassertTaskTransform(task: Any, clearCrop: Boolean, source: String) {
        if (isTaskAnimating(task)) {
            logOnceTask(task, "transform skip source=$source reason=animating")
            return
        }
        applyTaskTransform(task, clearCrop)
    }

    fun init(classLoader: ClassLoader) {
        val policy = FreeformPolicies.forSdk(Build.VERSION.SDK_INT)
        if (diagnostics) {
            log(TAG, "init with policy: ${policy.displayName}")
        }
        runCatching { hookRelaunch(classLoader) }.onFailure { log(TAG, "hookRelaunch failed", it) }
        runCatching { hookAdditionalRelaunchPoints(classLoader) }.onFailure { log(TAG, "hookAdditionalRelaunchPoints failed", it) }
        runCatching { hookConfigSpoofing(classLoader, policy) }.onFailure { log(TAG, "hookConfigSpoofing failed", it) }
        runCatching { hookSurfaceScaling(classLoader, policy) }.onFailure { log(TAG, "hookSurfaceScaling failed", it) }
        if (policy.reassertTaskTransforms) {
            runCatching { hookSurfaceReassertions(classLoader, policy) }.onFailure { log(TAG, "hookSurfaceReassertions failed", it) }
        }
        runCatching { hookInputScaling(classLoader) }.onFailure { log(TAG, "hookInputScaling failed", it) }
    }

    private fun shouldForcePreventRelaunch(activityRecord: Any): Boolean {
        return runCatching {
            val task = XposedHelpers.callMethod(activityRecord, "getTask") ?: return false
            val taskId = XposedHelpers.getIntField(task, "mTaskId")

            val isSmoothTask = YAMFManager.smoothFreeformTasks.contains(taskId)
            val isForcePrevent = runCatching { YAMFManager.config.forcePreventRelaunch }.getOrDefault(false)
            
            if (isSmoothTask) return true
            
            if (isForcePrevent) {
                val windowingMode = XposedHelpers.callMethod(task, "getWindowingMode") as Int
                val displayId = XposedHelpers.callMethod(task, "getDisplayId") as Int
                
                // 1. 丝滑模式 (Freeform)
                // 2. Legacy 模式 (处于虚拟显示器上)
                if (windowingMode == WINDOWING_MODE_FREEFORM || YAMFManager.windowList.contains(displayId)) {
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
                                log(TAG, "Suppressing relaunchActivityLocked for ${param.thisObject}")
                                param.result = null // Skip original method
                            }
                        }
                    })
                }
            }
        }
    }

    private fun hookConfigSpoofing(classLoader: ClassLoader, policy: FreeformPolicy) {
        val taskFragmentClass = classLoader.loadClass("com.android.server.wm.TaskFragment")
        val hintClassName = FreeformResolver.firstLoadable(classLoader, policy.configOverrideHintCandidates)
        if (hintClassName == null) {
            log(TAG, "ConfigOverrideHint not found (${policy.configOverrideHintCandidates}), config spoofing disabled")
            return
        }
        if (diagnostics) {
            log(TAG, "ConfigOverrideHint resolved to $hintClassName")
        }
        
        XposedHelpers.findAndHookMethod(taskFragmentClass, "computeConfigResourceOverrides",
            Configuration::class.java,
            Configuration::class.java,
            hintClassName,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val taskFragment = param.thisObject
                    if (!taskFragment.javaClass.name.endsWith(".Task")) return
                    
                    val taskId = XposedHelpers.getIntField(taskFragment, "mTaskId")
                    if (YAMFManager.smoothFreeformTasks.contains(taskId)) {
                        val inOutConfig = param.args[0] as Configuration
                        val parentConfig = param.args[1] as Configuration
                        
                        val inOutWindowConfig = XposedHelpers.getObjectField(inOutConfig, "windowConfiguration")
                        val parentWindowConfig = XposedHelpers.getObjectField(parentConfig, "windowConfiguration")
                        
                        // Save physical bounds
                        val physicalBounds = Rect(XposedHelpers.callMethod(inOutWindowConfig, "getBounds") as Rect)
                        YAMFManager.smoothFreeformBounds[taskId] = physicalBounds
                        
                        // Spoof configuration
                        val parentBounds = XposedHelpers.callMethod(parentWindowConfig, "getBounds") as Rect
                        XposedHelpers.callMethod(inOutWindowConfig, "setBounds", parentBounds)
                        
                        val parentAppBounds = XposedHelpers.callMethod(parentWindowConfig, "getAppBounds") as? Rect
                        if (parentAppBounds != null) {
                            XposedHelpers.callMethod(inOutWindowConfig, "setAppBounds", parentAppBounds)
                        }
                        
                        inOutConfig.screenWidthDp = parentConfig.screenWidthDp
                        inOutConfig.screenHeightDp = parentConfig.screenHeightDp
                        inOutConfig.smallestScreenWidthDp = parentConfig.smallestScreenWidthDp
                        inOutConfig.densityDpi = parentConfig.densityDpi
                        inOutConfig.orientation = parentConfig.orientation
                        XposedHelpers.callMethod(inOutWindowConfig, "setWindowingMode", WINDOWING_MODE_FULLSCREEN)
                        logOnce(taskId, "spoof task=$taskId hint=${param.args.getOrNull(2)?.javaClass?.name} physical=$physicalBounds parent=$parentBounds")
                    }
                }
            }
        )
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
                logOnceTask(param.thisObject, "transform skip source=updateSurfaceSize reason=animating")
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

        if (diagnostics) {
            log(TAG, "reassert anchors installed (clearCrop=${policy.clearTaskCrop}, file=Task)")
        }
    }

    private fun applyTaskTransform(task: Any, clearCrop: Boolean) {
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
        if (!YAMFManager.smoothFreeformTasks.contains(taskId)) {
            logOnce(taskId, "transform skip task=$taskId reason=untracked")
            return
        }
        val physicalBounds = YAMFManager.smoothFreeformBounds[taskId]
        if (physicalBounds == null) {
            logOnce(taskId, "transform skip task=$taskId reason=no-physical-bounds")
            return
        }

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
        )
        if (transform == null) {
            logOnce(taskId, "transform skip task=$taskId reason=empty-spoofed-bounds")
            return
        }

        val surfaceControl = XposedHelpers.getObjectField(task, "mSurfaceControl") as? SurfaceControl
        if (surfaceControl == null) {
            logOnce(taskId, "transform skip task=$taskId reason=no-surface")
            return
        }

        if (diagnostics && snapshotLogged.add(taskId)) {
            val requested = runCatching {
                XposedHelpers.callMethod(task, "getRequestedOverrideBounds") as Rect
            }.getOrNull()
            log(TAG, "transform context task=$taskId configBounds=$spoofedBounds requested=$requested physical=$physicalBounds")
        }
        logOnce(taskId, "transform task=$taskId scale=(${transform.scaleX},${transform.scaleY}) clearCrop=$clearCrop")

        XposedHelpers.callMethod(transaction, "setMatrix", surfaceControl, transform.scaleX, 0f, 0f, transform.scaleY)
        XposedHelpers.callMethod(transaction, "setPosition", surfaceControl, transform.positionX, transform.positionY)

        if (clearCrop) {
            XposedHelpers.callMethod(
                transaction,
                "setWindowCrop",
                arrayOf<Class<*>>(SurfaceControl::class.java, Rect::class.java),
                surfaceControl,
                null
            )
        }
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
                    val task = XposedHelpers.callMethod(windowState, "getTask") ?: return
                    val taskId = XposedHelpers.getIntField(task, "mTaskId")
                    
                    if (YAMFManager.smoothFreeformTasks.contains(taskId)) {
                        val physicalBounds = YAMFManager.smoothFreeformBounds[taskId] ?: return
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
                        XposedHelpers.callMethod(
                            inputWindowHandleWrapper,
                            "setScaleFactor",
                            FreeformSurfaceMath.inputScale(transform.scaleX)
                        )
                    }
                }
            }
        )
    }
}
