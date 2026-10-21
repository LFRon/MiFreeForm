package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.buildsession.betterYAMF.xposed.utils.logWarning

/**
 * LoS Display:2135/2339 can return a Launcher's fixed-rotation configuration even while the
 * overlay's display is landscape. Bind resources AND WindowManager to our own overlay
 * DisplayArea (ContextImpl:3158), so bounds, rotation and native placement share one owner.
 */
internal class LauncherOverlayDisplay(service: Context) : AutoCloseable {
    private val display = checkNotNull(service.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY))
    val displayId: Int get() = display.displayId
    val context: Context = service.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val release = runCatching { context.javaClass.getMethod("release") }.getOrNull()
    private var closed = false

    data class Frame(val width: Int, val height: Int, val rotation: Int)

    fun frame(): Frame? {
        if (closed) return null
        // WindowMetricsController:81 and Display:getLocalRotation read this WindowContext's
        // DisplayArea configuration, rather than the service/activity's maxBounds.
        val bounds = windowManager.currentWindowMetrics.bounds
        val rotation = context.display?.rotation ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0 || rotation !in 0..3) return null
        return Frame(bounds.width(), bounds.height(), rotation)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { release?.invoke(context) }
            .onFailure { logWarning("reYAMF_EntryV2", "overlay context release failed", it) }
    }
}
