package com.buildsession.betterYAMF.xposed.hook.launcher

import android.graphics.ColorSpace
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.VelocityTracker
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import com.buildsession.betterYAMF.xposed.hook.system.MorphSnapshotOps
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning
import de.robv.android.xposed.XposedHelpers

/** LoS TaskViewSimulator:338-367 supplies buffer crop and display-space geometry separately. */
internal object RecentsEntryCapture {
    fun peekPose(swipeHandler: Any, taskId: Int): Pair<RectF, Float>? = runCatching {
        val handles = XposedHelpers.getObjectField(swipeHandler, "mRemoteTargetHandles") as Array<*>
        for (handle in handles) {
            val params = XposedHelpers.callMethod(handle, "getTransformParams")
            val targets = XposedHelpers.getObjectField(XposedHelpers.callMethod(params, "getTargetSet"), "apps") as Array<*>
            if (targets.none { XposedHelpers.getIntField(it, "taskId") == taskId }) continue
            val simulator = XposedHelpers.callMethod(handle, "getTaskViewSimulator")
            val bounds = RectF(XposedHelpers.callMethod(simulator, "getCurrentRect") as RectF)
            if (!bounds.isEmpty && listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }) {
                return bounds to (XposedHelpers.callMethod(simulator, "getScaledCornerRadius") as Number).toFloat()
            }
        }
        null
    }.getOrNull()

    fun capture(swipeHandler: Any, taskId: Int, activeConsumer: Any): EntrySnapshot? = runCatching {
        val handles = XposedHelpers.getObjectField(swipeHandler, "mRemoteTargetHandles") as Array<*>
        for (handle in handles) {
            val params = XposedHelpers.callMethod(handle, "getTransformParams")
            val targets = XposedHelpers.getObjectField(XposedHelpers.callMethod(params, "getTargetSet"), "apps") as Array<*>
            val target = targets.firstOrNull { XposedHelpers.getIntField(it, "taskId") == taskId } ?: continue
            val simulator = XposedHelpers.callMethod(handle, "getTaskViewSimulator")
            val bounds = RectF(XposedHelpers.callMethod(simulator, "getCurrentRect") as RectF)
            check(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() } && !bounds.isEmpty)
            val crop = Rect().also { (XposedHelpers.callMethod(simulator, "getCurrentCropRect") as RectF).roundOut(it) }
            val screen = Rect().also(bounds::roundOut)
            val radius = (XposedHelpers.callMethod(simulator, "getScaledCornerRadius") as Number).toFloat()
            val surface = XposedHelpers.getObjectField(target, "leash") as SurfaceControl
            val captured = MorphSnapshotOps.captureLayer(surface, crop) ?: return null
            try {
                val color = captured.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                check(ParcelableColorSpace.isParcelable(color))
                val tracker = runCatching { XposedHelpers.getObjectField(activeConsumer, "mVelocityTracker") as? VelocityTracker }.getOrNull()
                val pointer = runCatching { XposedHelpers.getIntField(activeConsumer, "mActivePointerId") }.getOrDefault(0)
                tracker?.computeCurrentVelocity(1000)
                log("reYAMF_EntryV2") { "capture task=$taskId source=$screen crop=$crop buffer=${captured.width}x${captured.height} radius=$radius" }
                return EntrySnapshot(captured.buffer, ParcelableColorSpace(color), screen, radius,
                    tracker?.getXVelocity(pointer) ?: 0f, tracker?.getYVelocity(pointer) ?: 0f,
                    SystemClock.elapsedRealtimeNanos())
            } catch (error: Throwable) {
                captured.buffer.close()
                throw error
            }
        }
        null
    }.onFailure { logWarning("reYAMF_EntryV2", "capture unavailable task=$taskId; preserve native gesture", it) }.getOrNull()
}
