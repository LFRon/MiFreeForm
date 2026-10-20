package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.graphics.ColorSpace
import android.graphics.ParcelableColorSpace
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceControl
import com.buildsession.betterYAMF.common.model.EntrySnapshot
import com.buildsession.betterYAMF.xposed.adapters.common.system.MorphSnapshotOps
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** LoS TaskViewSimulator:338-367 supplies buffer crop and display-space geometry separately. */
internal object RecentsEntryCapture {
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
