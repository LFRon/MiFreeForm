package com.buildsession.betterYAMF.xposed.hook.launcher

import android.graphics.ColorSpace
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.SurfaceControl
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

    /** Probe resource identities before UP; no pixels or pose are captured until native UP ends. */
    fun source(swipeHandler: Any, taskId: Int): RecentsSourceFrame? = runCatching {
        val handles = XposedHelpers.getObjectField(swipeHandler, "mRemoteTargetHandles") as Array<*>
        for (handle in handles) {
            val params = XposedHelpers.callMethod(handle, "getTransformParams")
            val targets = XposedHelpers.getObjectField(XposedHelpers.callMethod(params, "getTargetSet"), "apps") as Array<*>
            val target = targets.firstOrNull { XposedHelpers.getIntField(it, "taskId") == taskId } ?: continue
            val simulator = XposedHelpers.callMethod(handle, "getTaskViewSimulator")
            val surface = XposedHelpers.getObjectField(target, "leash") as SurfaceControl
            return RecentsSourceFrame.probe(simulator, params, surface)
        }
        null
    }.onFailure { logWarning("reYAMF_EntryV2", "source synchronization unavailable task=$taskId; preserve native gesture", it) }.getOrNull()

    /** Source geometry is immutable and its native transform has committed. Velocity comes from
     * onGestureEnded's px/ms argument, copied before cleanupAfterGesture recycles its tracker.
     */
    fun capture(frame: RecentsSourceFrame.Frame, taskId: Int, velocityX: Float, velocityY: Float): EntrySnapshot? = runCatching {
        val captured = MorphSnapshotOps.captureLayer(frame.surface, frame.crop) ?: return null
        try {
            val color = captured.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            check(ParcelableColorSpace.isParcelable(color))
            log("reYAMF_EntryV2") { "capture task=$taskId source=${frame.bounds} crop=${frame.crop} buffer=${captured.width}x${captured.height} radius=${frame.cornerRadius}" }
            EntrySnapshot(captured.buffer, ParcelableColorSpace(color), Rect(frame.bounds), frame.cornerRadius,
                velocityX, velocityY, SystemClock.elapsedRealtimeNanos())
        } catch (error: Throwable) {
            captured.buffer.close()
            throw error
        }
    }.onFailure { logWarning("reYAMF_EntryV2", "capture unavailable task=$taskId; preserve native gesture", it) }.getOrNull()
}
