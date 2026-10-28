package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.TaskInfo
import android.content.Context
import java.lang.reflect.Method

/** Both reviewed A17 builds expose the cache constructor. Pixel 17/907 has an empty visible-task
 * callback, so that callback alone is insufficient. Resolve and validate the cache path as well.
 */
internal class QuickstepTaskFilterAccess(loader: ClassLoader) {
    private companion object { const val WINDOWING_MODE_FULLSCREEN = 1 }

    private val tracker = loader.loadClass("com.android.quickstep.TopTaskTracker")
    private val group = loader.loadClass("com.android.wm.shell.shared.GroupedTaskInfo")
    private val groups = java.lang.reflect.Array.newInstance(group, 0).javaClass
    val visible = tracker.getDeclaredMethod("onVisibleTasksChanged", groups)
    val cached = tracker.getDeclaredMethod("getCachedTopTask", Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType)
    private val info = cached.returnType
    private val all = info.getDeclaredField("mAllCachedTasks").apply { isAccessible = true }
    private val top = info.getMethod("getLegacyBaseTask")
    private val context = info.getDeclaredField("mContext").apply { isAccessible = true }
    private val displayId = info.getDeclaredField("mDisplayId").apply { isAccessible = true }
    private val deskId = info.getDeclaredField("mActiveDeskId").apply { isAccessible = true }
    private val create = info.getDeclaredConstructor(List::class.java, Context::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val tasks = group.getMethod("getTaskInfoList")
    /** Pixel's empty onVisibleTasksChanged leaves this legacy cache populated after a task
     * leaves managed ownership. Clearing it forces the next getCachedTopTask(false) to read
     * current running tasks instead of the pre-fullscreen home entry. */
    private val ordered = tracker.getDeclaredField("mOrderedTaskList").apply { isAccessible = true }
    /** TaskInfo.getWindowingMode() is public at runtime (the Launcher's own TopTaskTracker calls
     * it) but hidden from the SDK stubs this module compiles against. Resolved once. */
    private val windowingMode: Method? = runCatching {
        TaskInfo::class.java.getMethod("getWindowingMode")
    }.getOrNull()
    val windowingModeAvailable: Boolean get() = windowingMode != null

    init {
        check(visible.returnType == Void.TYPE && List::class.java.isAssignableFrom(all.type) &&
            List::class.java.isAssignableFrom(tasks.returnType) && TaskInfo::class.java.isAssignableFrom(top.returnType))
        check(MutableCollection::class.java.isAssignableFrom(ordered.type))
    }

    fun invalidateTopTaskCache(trackerInstance: Any) {
        (ordered.get(trackerInstance) as? MutableCollection<*>)?.clear()
    }

    /** Debug evidence: task id plus the windowing mode the cache actually reported. */
    fun cachedFacts(value: Any): String =
        ((all.get(value) as? List<*>) ?: emptyList<Any>()).joinToString(",") { entry ->
            if (entry is TaskInfo) "${entry.taskId}:${windowingModeOf(entry) ?: "?"}" else "?"
        }

    /** Null when the hidden fact is unavailable; callers must then keep the previous behaviour. */
    private fun windowingModeOf(task: TaskInfo): Int? =
        runCatching { windowingMode?.invoke(task) as? Int }.getOrNull()

    /** Same hosted-task rule for the shell path: a group that already left floating ownership
     * (its task is fullscreen again) must stay eligible as a gesture target. */
    fun groupHosted(value: Any): List<Pair<Int, Boolean>> =
        (tasks.invoke(value) as List<*>).map { task ->
            val info = task as TaskInfo
            info.taskId to hosted(info)
        }

    fun withoutManagedTasks(value: Any, managed: (Int) -> Boolean): Any? {
        // LoS's shell-backed cache deliberately has no legacy list. Its grouped-task input was
        // filtered separately; preserve that representation instead of rebuilding it as legacy.
        val current = all.get(value) as? List<*> ?: return null
        val kept = current.filter { it !is TaskInfo || !managed(it.taskId) || !hosted(it) }
        // GestureState reads mAllCachedTasks directly, including two-task candidates. Filtering
        // only when the first task is hosted leaves a hosted second task eligible for animation.
        if (kept.size == current.size) return null
        // Never produce an empty candidate set: with no task to target the Launcher starts no
        // animation at all, so the swipe-up becomes a no-op. Keep the native list in that case.
        if (kept.isEmpty()) return null
        return create.newInstance(kept, context.get(value), displayId.getInt(value), deskId.getInt(value))
    }

    /**
     * The managed-id broadcast is asynchronous, so it can still list a task that has already been
     * released to fullscreen. The task's own windowing mode is the authoritative fact: a hosted
     * floating task is freeform/multi-window, while a released one is fullscreen. Without this,
     * the first swipe-up after a fullscreen handoff filters out the real foreground task, and
     * the Launcher animates the stale home entry instead (the gesture appears dead).
     */
    private fun hosted(task: TaskInfo): Boolean =
        windowingModeOf(task)?.let { it != WINDOWING_MODE_FULLSCREEN } ?: true
}
