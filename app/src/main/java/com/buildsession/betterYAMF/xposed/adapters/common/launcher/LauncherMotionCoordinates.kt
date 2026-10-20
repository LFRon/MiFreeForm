package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.view.MotionEvent
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** Read the event's actual transform before Quickstep applies its own touch rotation. */
internal object LauncherMotionCoordinates {
    private var surfaceRotation = runCatching {
        MotionEvent::class.java.getDeclaredMethod("getSurfaceRotation").apply { isAccessible = true }
    }.onFailure { logWarning("reYAMF_EntryV2", "event rotation capability unavailable", it) }.getOrNull()

    fun point(event: MotionEvent, gestureRotation: Int,
        width: Int, height: Int): LauncherCoordinateMapper.Point? {
        val rotation = runCatching { surfaceRotation?.invoke(event) as? Int }.onFailure {
            surfaceRotation = null
            logWarning("reYAMF_EntryV2", "event rotation unavailable; preserve native gesture", it)
        }.getOrNull()?.takeIf { it in 0..3 } ?: return null
        // Input-monitor events have display coordinates; x/y follow the transform inspected
        // above. Never infer a coordinate space from the position of one finger.
        return LauncherCoordinateMapper.rotatePoint(event.x, event.y, rotation, gestureRotation, width, height)
    }
}
