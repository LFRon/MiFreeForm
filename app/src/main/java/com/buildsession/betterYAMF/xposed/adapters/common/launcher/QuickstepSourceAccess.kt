package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.graphics.RectF
import android.view.SurfaceControl
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Fixed source bindings shared by the reviewed A17 Quickstep builds. Resolving this class does
 * not install hooks or read a live task. Resource identities are read from this gesture each time.
 */
internal class QuickstepSourceAccess(loader: ClassLoader) {
    private val handles = loader.loadClass("com.android.quickstep.SwipeUpAnimationLogic")
        .getDeclaredField("mRemoteTargetHandles").apply { isAccessible = true }
    private val handleType = checkNotNull(handles.type.componentType)
    private val simulator = handleType.getMethod("getTaskViewSimulator")
    private val params = handleType.getMethod("getTransformParams")
    val producer = RecentsSourceFrame.NativeApi(simulator.returnType)
    private val targetSet = params.returnType.getMethod("getTargetSet")
    private val apps = targetSet.returnType.getField("apps")
    private val target = checkNotNull(apps.type.componentType)
    private val taskId = target.getField("taskId")
    private val leash = target.getField("leash")

    init {
        check(params.returnType == producer.params && taskId.type == Int::class.javaPrimitiveType &&
            leash.type == SurfaceControl::class.java)
    }

    private fun matchingHandle(swipe: Any, id: Int): Any? {
        val current = handles.get(swipe) as? Array<*> ?: return null
        return current.firstOrNull { handle ->
            val set = targetSet.invoke(params.invoke(handle)) ?: return@firstOrNull false
            (apps.get(set) as Array<*>).any { taskId.getInt(it) == id }
        }
    }

    fun peekPose(swipe: Any, id: Int): Pair<RectF, Float>? = runCatching {
        val handle = matchingHandle(swipe, id) ?: return null
        val current = simulator.invoke(handle)
        val bounds = RectF(producer.bounds.invoke(current) as RectF)
        if (bounds.isEmpty || !listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all(Float::isFinite))
            return null
        bounds to (producer.radius.invoke(current) as Float)
    }.getOrNull()

    /** Retain only native resource identities before UP; sample pose/pixels after final UP commits. */
    fun source(swipe: Any, id: Int): RecentsSourceFrame? = runCatching {
        val handle = matchingHandle(swipe, id) ?: return null
        val currentParams = checkNotNull(params.invoke(handle))
        val set = checkNotNull(targetSet.invoke(currentParams))
        val app = (apps.get(set) as Array<*>).first { taskId.getInt(it) == id }
        RecentsSourceFrame.probe(producer, checkNotNull(simulator.invoke(handle)), currentParams,
            leash.get(app) as SurfaceControl)
    }.onFailure {
        logWarning("reYAMF_EntryV2", "source synchronization unavailable task=$id; preserve native gesture", it)
    }.getOrNull()
}
