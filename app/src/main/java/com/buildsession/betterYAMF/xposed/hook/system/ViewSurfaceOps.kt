package com.buildsession.betterYAMF.xposed.hook.system

import android.view.Surface
import android.view.View
import de.robv.android.xposed.XposedHelpers

/**
 * 视图表面控制适配器（反射细节收口点）：
 * 应用所在窗口 SurfaceControl 的帧率票（SF 层刷新率提升）。
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
        val viewRootImpl = XposedHelpers.callMethod(view, "getViewRootImpl")
        val surfaceControl = viewRootImpl?.let {
            XposedHelpers.callMethod(it, "getSurfaceControl") as? android.view.SurfaceControl
        }
        if (viewRootImpl == null || surfaceControl == null) return@runCatching false
        val tx = android.view.SurfaceControl.Transaction()
        if (enable) {
            tx.setFrameRate(surfaceControl, hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        } else {
            tx.clearFrameRate(surfaceControl)
        }
        tx.apply()
        tx.close()
        true
    }.getOrDefault(false)
}
