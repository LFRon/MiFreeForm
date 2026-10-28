package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.view.Surface
import android.view.View
import com.buildsession.betterYAMF.xposed.utils.Reflect

/**
 * 视图表面控制适配器（反射细节收口点）：
 * 应用所在窗口 SurfaceControl 的帧率票（SF 层刷新率提升）与窗口表面句柄获取。
 *
 * 分层边界：本类属适配器层（唯一允许反射 ViewRootImpl/SurfaceControl 的地方）；
 * 宿主（AppWindow）只经本入口调用，不直接持有反射细节。
 *
 * 关键源码事实（LineageOS 24.0）：
 * - `ViewRootImpl.java:14329` `setPreferredFrameRate`：平台自身即以本 Transaction 通道
 *   下发帧率（`SurfaceControl.java:4683 setFrameRate` / `:4796 clearFrameRate`）；
 * - FIXED_SOURCE = 内容严格依赖该帧率；结束用 clearFrameRate 撤销。
 */
object ViewSurfaceOps {

    /** 为 [view] 所在窗口表面设置/撤销帧率票；表面未就绪等返回 false（静默降级）。 */
    fun setViewSurfaceFrameRate(view: View, hz: Float, enable: Boolean): Boolean = runCatching {
        val surfaceControl = windowSurface(view) ?: return@runCatching false
        val tx = android.view.SurfaceControl.Transaction()
        try {
            if (enable) {
                tx.setFrameRate(surfaceControl, hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } else {
                // LoS SurfaceControl:4796 defines clearFrameRate as this exact call. The
                // equivalent API is also available on our API 33 minimum platform.
                tx.setFrameRate(surfaceControl, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS)
            }
            tx.apply()
        } finally {
            tx.close()
        }
        true
    }.getOrDefault(false)

    /**
     * 取视图所在窗口的窗口表面句柄（动画快照/帧率等适配用）。
     * **只读语义**：调用方不得 release（生命周期归 ViewRootImpl）。
     */
    fun windowSurface(view: View): android.view.SurfaceControl? = runCatching {
        val viewRootImpl = Reflect.callMethod(view, "getViewRootImpl")
        (Reflect.callMethod(viewRootImpl, "getSurfaceControl") as? android.view.SurfaceControl)
            ?.takeIf { it.isValid }
    }.getOrNull()
}
