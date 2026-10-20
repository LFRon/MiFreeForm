package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.app.TaskInfo
import android.content.Context

/** Both reviewed A17 builds expose the cache constructor. Pixel 17/907 has an empty visible-task
 * callback, so that callback alone is insufficient. Resolve and validate the cache path as well.
 */
internal class QuickstepTaskFilterAccess(loader: ClassLoader) {
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

    init {
        check(visible.returnType == Void.TYPE && List::class.java.isAssignableFrom(all.type) &&
            List::class.java.isAssignableFrom(tasks.returnType) && TaskInfo::class.java.isAssignableFrom(top.returnType))
    }

    fun groupIds(value: Any): List<Int> =
        (tasks.invoke(value) as List<*>).map { (it as TaskInfo).taskId }

    fun withoutManagedTasks(value: Any, managed: (Int) -> Boolean): Any? {
        // LoS's shell-backed cache deliberately has no legacy list. Its grouped-task input was
        // filtered separately; preserve that representation instead of rebuilding it as legacy.
        val current = all.get(value) as? List<*> ?: return null
        val kept = current.filter { it !is TaskInfo || !managed(it.taskId) }
        // GestureState reads mAllCachedTasks directly, including two-task candidates. Filtering
        // only when the first task is hosted leaves a hosted second task eligible for animation.
        if (kept.size == current.size) return null
        return create.newInstance(kept, context.get(value), displayId.getInt(value), deskId.getInt(value))
    }
}
