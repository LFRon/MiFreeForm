package com.buildsession.betterYAMF.xposed.adapters.android17.system

import android.annotation.SuppressLint
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.window.api.TaskFocusFacts
import com.buildsession.betterYAMF.window.api.TaskFocusOrigin
import com.buildsession.betterYAMF.window.runtime.WindowRuntimes
import com.buildsession.betterYAMF.xposed.adapters.common.organizer.SmoothTaskOrganizer
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks
import com.buildsession.betterYAMF.xposed.hook.bridge.NativeHooks.hookBefore
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logError
import java.util.function.Predicate

/** LOS 24.0: RecentsController.setInputConsumerEnabled -> ATMS.focusTopTask ->
 * setFocusedTask(int, ActivityRecord). The first method selects the highest focusable leaf,
 * including our always-on-top root's children. Focusing that child creates TO_FRONT, which
 * RecentsController.merge refuses and cancels the other app's recents gesture to Home.
 *
 * Keep the public native wrapper (permissions, Binder identity, WM global lock and finally).
 * Only replace its automatic candidate immediately before native focus; a tap, explicit focus,
 * task raise, fullscreen handback and unrelated lifecycle selection keep their original path.
 * No task focusability mutation, delayed restoration, Binder wait or gesture-time broadcast.
 */
// Runs only inside the SDK-37 system_server adapter, after exact-signature preflight. These
// are its reviewed native bindings; no application hidden-API exemption or SDK fallback.
@SuppressLint("PrivateApi")
internal class AutomaticTaskFocusGuard(loader: ClassLoader) {
    private val service = loader.loadClass("com.android.server.wm.ActivityTaskManagerService")
    private val task = loader.loadClass("com.android.server.wm.Task")
    private val focusTop = service.getDeclaredMethod("focusTopTask", Int::class.javaPrimitiveType)
    private val focusSelected = service.getDeclaredMethod("setFocusedTask", Int::class.javaPrimitiveType,
        loader.loadClass("com.android.server.wm.ActivityRecord"))
    private val root = service.getDeclaredField("mRootWindowContainer").apply { isAccessible = true }
    private val lock = service.getDeclaredField("mGlobalLock").apply { isAccessible = true }
    private val display = loader.loadClass("com.android.server.wm.RootWindowContainer")
        .getDeclaredMethod("getDisplayContent", Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val findTask = loader.loadClass("com.android.server.wm.WindowContainer")
        .getDeclaredMethod("getTask", Predicate::class.java, Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
    private val leaf = task.getDeclaredMethod("isLeafTask").apply { isAccessible = true }
    private val focusable = loader.loadClass("com.android.server.wm.TaskFragment")
        .getDeclaredMethod("isTopActivityFocusable").apply { isAccessible = true }
    private val taskId = task.getDeclaredField("mTaskId").apply { isAccessible = true }
    private val scope = AutomaticTaskFocusScope()

    fun install(): List<NativeHooks.Registration> {
        val hooks = mutableListOf<NativeHooks.Registration>()
        try {
            hooks += NativeHooks.hookMethod(focusTop, object : NativeHooks.Callback() {
                override fun beforeHookedMethod(param: NativeHooks.Call) {
                    param.setObjectExtra(SCOPE_KEY, scope.enter(param.thisObject, param.args[0] as Int))
                }
                override fun afterHookedMethod(param: NativeHooks.Call) {
                    // Xposed invokes after even when native permissions/original execution throw.
                    (param.getObjectExtra(SCOPE_KEY) as? AutomaticTaskFocusScope.Request)?.let(scope::leave)
                }
            })
            hooks += focusSelected.hookBefore { call ->
                if (call.argument(1) != null) return@hookBefore // Actual touched ActivityRecord.
                val request = scope.consume(call.thisObject) ?: return@hookBefore
                runCatching {
                    check(Thread.holdsLock(lock.get(call.thisObject))) { "Automatic focus outside WM lock" }
                    val dc = display.invoke(root.get(call.thisObject), request.displayId)
                    // Same leaf/focusability/traversal predicate as focusTopTask. CORE alone
                    // decides whether current window ownership permits automatic selection.
                    val target = dc?.let { container -> findTask.invoke(container, Predicate<Any> { candidate ->
                        leaf.invoke(candidate) == true && focusable.invoke(candidate) == true &&
                            WindowRuntimes.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC,
                                SmoothTaskOrganizer.ownsNativeTask(candidate)))
                    }, true) }
                    val selected = target?.let(taskId::getInt)
                    val original = call.args[0] as Int
                    if (selected == null) call.result = null
                    else call.args[0] = selected
                    if (selected != original) log(TAG) {
                        "automatic focus display=${request.displayId} native=$original selected=$selected"
                    }
                }.onFailure { error ->
                    // Do not create the known conflicting TO_FRONT if selection fails. Leave
                    // the current focus in place and report failure; explicit focus is untouched.
                    call.result = null
                    logError(TAG, "Automatic focus selection failed display=${request.displayId}", error)
                }
            }
            log(TAG) { "automatic top-task focus guard installed build=${BuildConfig.BUILD_TIME}" }
            return hooks
        } catch (error: Throwable) {
            hooks.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    private companion object {
        const val TAG = "reYAMF_TaskFocus"
        const val SCOPE_KEY = "BetterYAMF.automaticTaskFocus"
    }
}
