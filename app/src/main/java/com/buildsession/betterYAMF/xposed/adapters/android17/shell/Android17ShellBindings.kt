package com.buildsession.betterYAMF.xposed.adapters.android17.shell

import android.app.ActivityManager.RunningTaskInfo
import android.app.TaskInfo
import android.os.IBinder
import android.view.SurfaceControl
import java.lang.reflect.Method

/** PixelOS seventeen ebc25c0cf9c8. Resolve once before installing any Shell hooks. Public lifecycle
 * overrides survive interface dispatch; do not depend on R8 retaining private predicate helpers.
 */
internal class Android17ShellBindings(private val loader: ClassLoader) {
    private fun type(name: String) = loader.loadClass(name)
    private fun optional(name: String): Class<*>? = try { type(name) } catch (_: ClassNotFoundException) { null }
    private fun Class<*>.method(name: String, vararg args: Class<*>) =
        getDeclaredMethod(name, *args).apply { isAccessible = true }
    private val info = type("android.window.TransitionInfo")
    private val change = type("android.window.TransitionInfo\$Change")
    private val root = type("android.window.TransitionInfo\$Root")
    private val tx = SurfaceControl.Transaction::class.java
    private val readyArgs = arrayOf(IBinder::class.java, info, tx, tx)
    val applicationCreate = type("com.android.systemui.application.impl.SystemUIApplicationImpl").method("onCreate")
    val freeform = type("com.android.wm.shell.freeform.FreeformTaskTransitionObserver")
    val observers: List<Method> = buildList {
        add(freeform.method("onTransitionReady", *readyArgs))
        // Freeform passes this same projected info into DesktopInOrder and Fallback. Neither
        // helper needs a separate hook/signature: R8 is free to inline those single-call methods.
        optional("com.android.wm.shell.desktopmode.DesktopTasksTransitionObserver")?.let {
            add(it.method("onTransitionReady", *readyArgs))
        }
    }
    val animations: List<Method> = buildList {
        val callback = type("com.android.wm.shell.transition.Transitions\$TransitionFinishCallback")
        for (name in listOf("freeform.FreeformTaskTransitionHandler", "desktopmode.DesktopMixedTransitionHandler"))
            optional("com.android.wm.shell.$name")?.let {
                add(it.method("startAnimation", IBinder::class.java, info, tx, tx, callback))
            }
    }
    private val requestType = type("android.window.TransitionRequestInfo")
    // Hook the actual registered TransitionHandler override, not its single-call Controller
    // helper, which SystemUI's optimized build can inline.
    val desktopRequest = optional("com.android.wm.shell.desktopmode.desktoptaskshandlers.DesktopTasksTransitionHandler")
        ?.method("handleRequest", IBinder::class.java, requestType)
    private val triggerTask = requestType.getMethod("getTriggerTask")
    private val userChange = requestType.getMethod("getUserChange")
    private val cookies = TaskInfo::class.java.getField("launchCookies")
    private val constructor = info.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val typeOf = info.getMethod("getType")
    private val flags = info.getMethod("getFlags")
    private val changes = info.getMethod("getChanges")
    private val taskOf = change.getMethod("getTaskInfo")
    private val container = change.getMethod("getContainer")
    private val parent = change.getMethod("getParent")
    private val rootCount = info.getMethod("getRootCount")
    private val rootAt = info.getMethod("getRoot", Int::class.javaPrimitiveType)
    private val addRoot = info.getMethod("addRoot", root)
    private val getTrack = info.getMethod("getTrack")
    private val setTrack = info.getMethod("setTrack", Int::class.javaPrimitiveType)
    private val getDebugId = info.getMethod("getDebugId")
    private val setDebugId = info.getMethod("setDebugId", Int::class.javaPrimitiveType)

    data class Decoration(val type: Class<*>, val updates: List<Method>, val destroy: Method)
    val decorations = listOfNotNull(
        optional("com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel"),
        optional("com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel")
    ).map { model ->
        Decoration(model, listOf(
            model.method("onTaskOpening", RunningTaskInfo::class.java, SurfaceControl::class.java, tx, tx),
            model.method("onTaskChanging", RunningTaskInfo::class.java, SurfaceControl::class.java, tx, tx),
            model.method("onTaskClosing", RunningTaskInfo::class.java, tx, tx),
            model.method("onTaskInfoChanged", RunningTaskInfo::class.java)
        ), model.method("destroyWindowDecoration", RunningTaskInfo::class.java))
    }.also { check(it.isNotEmpty()) { "No reviewed WindowDecorViewModel implementation" } }
    private val freeformDecor = freeform.getDeclaredField("mWindowDecorViewModel").apply { isAccessible = true }
    private val destroyDecor = type("com.android.wm.shell.windowdecor.WindowDecorViewModel")
        .getMethod("destroyWindowDecoration", RunningTaskInfo::class.java)

    fun owns(task: Any?, cookie: IBinder?): Boolean = cookie != null && task is TaskInfo &&
        (cookies.get(task) as List<*>).contains(cookie)
    fun requestOwned(request: Any, cookie: IBinder?): Boolean =
        userChange.invoke(request) == null && owns(triggerTask.invoke(request), cookie)

    /** Borrowed view for selected observers/handlers only. It allocates no native handles, never
     * releases anything, and never mutates the original list, changes, roots or transactions.
     * These reviewed consumers read Change/Root metadata; their local list removals stay local.
     */
    fun project(original: Any, cookie: IBinder?, onOwned: (RunningTaskInfo) -> Unit = {}): Any {
        if (cookie == null) return original
        @Suppress("UNCHECKED_CAST") val values = changes.invoke(original) as List<Any>
        val owned = values.filter { owns(taskOf.invoke(it), cookie) }.toSet()
        if (owned.isEmpty()) return original
        // Split/PiP can append synthetic changes with a null container. Give each its own local
        // identity; never collapse them together or reject an otherwise valid mixed transition.
        val tokens = values.associateWith { container.invoke(it) ?: it }
        val excluded = ShellTransitionFilter.excluded(values.map {
            ShellTransitionFilter.Node(tokens.getValue(it), parent.invoke(it), it in owned)
        })
        if (excluded.isEmpty()) return original
        values.filter { tokens.getValue(it) in excluded }.forEach {
            (taskOf.invoke(it) as? RunningTaskInfo)?.let(onOwned)
        }
        val view = constructor.newInstance(typeOf.invoke(original), flags.invoke(original))
        @Suppress("UNCHECKED_CAST") val filtered = changes.invoke(view) as MutableList<Any>
        values.filterTo(filtered) { tokens.getValue(it) !in excluded }
        repeat(rootCount.invoke(original) as Int) { addRoot.invoke(view, rootAt.invoke(original, it)) }
        setTrack.invoke(view, getTrack.invoke(original)); setDebugId.invoke(view, getDebugId.invoke(original))
        return view
    }

    fun cleanup(observer: Any, task: RunningTaskInfo) {
        if (freeform.isInstance(observer)) destroyDecor.invoke(freeformDecor.get(observer), task)
    }
}
