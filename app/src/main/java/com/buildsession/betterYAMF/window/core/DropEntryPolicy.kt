package com.buildsession.betterYAMF.window.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * 回收区投放登场的纯策略（零平台依赖；宿主只传值并执行）：
 * 起点大卡片的几何与飞行观感参数（时长 / 曲线 / 景深 / 淡入）——"观感参数在逻辑层"。
 *
 * 轨迹（对齐"米窗示范"视频逐帧量化）：一条从大到小的连续强减速飞行，
 * 右缘锚定槽位右上角、圆角/描边随飞行渐成，落点即 mini，全程无"可点击中间态"。
 */
object DropEntryPolicy {

    /** 起点大卡片 = 目标卡片的放大倍数。 */
    const val START_SCALE = 3.0f

    /** 飞行时长基准（毫秒；经开发者动画缩放与模块动画速度配置折减，见 [AnimTimingPolicy]）。 */
    const val DURATION_BASE_MS = 500L

    /** 起飞淡入时长（毫秒；避免起点帧闪现）。 */
    const val START_FADE_MS = 160L

    /** 飞行插值曲线控制点（起步即高速、长尾收束；宿主以此构造 PathInterpolator）。 */
    const val CURVE_X1 = 0.05f
    const val CURVE_Y1 = 0f
    const val CURVE_X2 = 0.12f
    const val CURVE_Y2 = 1f

    /**
     * 起点矩形：右缘锚定 [target] 右缘（轨迹的右缘直线），顶边夹在
     * [topInsetPx, 屏高 - bottomInsetPx - 起点高] 安全带内；左界不小于 [marginPx]。
     */
    fun startRect(
        target: WindowRect,
        screenHeight: Int,
        marginPx: Int,
        topInsetPx: Int,
        bottomInsetPx: Int
    ): WindowRect {
        val startW = (target.width * START_SCALE).toInt()
        val startH = (target.height * START_SCALE).toInt()
        val startLeft = (target.right - startW).coerceAtLeast(marginPx)
        val startTop = target.top.coerceIn(
            topInsetPx,
            (screenHeight - bottomInsetPx - startH).coerceAtLeast(topInsetPx)
        )
        return WindowRect(startLeft, startTop, startLeft + startW, startTop + startH)
    }

    /** 内容景深回缩系数（视口缩放乘数）：sin 峰 3%，首末帧归 1。 */
    fun dipAt(f: Float): Float = 1f - 0.03f * sin((PI * f).toFloat())
}
