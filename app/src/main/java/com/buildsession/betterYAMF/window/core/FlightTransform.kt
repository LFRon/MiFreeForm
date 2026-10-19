package com.buildsession.betterYAMF.window.core

/**
 * 飞行组仿射（纯几何，零平台依赖，可单测）。
 *
 * 目的：把"外壳组"（卡片 + 顶/底栏）与内容盒在**任意一帧由同一个仿射**导出——
 * 外壳和内容从构造上不可能各走各的时间线（消除"边框落后内容/脱节"）。
 * 机制等价物（ColorOS `FlexibleFloatHandleAnimationSpec#applySpringTransform`）：
 * 那边是 leash + mask（外壳表面）在同一 SurfaceControl.Transaction、同一帧、同一弹簧值；
 * 我们视图侧的等价物 = 基座布局一次冻结 + 逐帧只做视图仿射（无 WM 重排、无窗口尺寸变化）。
 *
 * 坐标约定（所有矩形为屏幕坐标；基座矩形即一次性布局后的窗口/卡片矩形）：
 * - 组仿射：任意基座布局点 p（视图在窗口内的局部坐标，窗口原点 = 基座左上）映射为
 *   p' = p × s + d（d = 当前矩形左上 − 基座左上）；
 * - 视图实现：pivot_local = −p（见 [pivotFor]），scale = s，translation = d
 *   ⇒ Android 视图变换式 p + d + pivot×(1−s) 恰等于 p×s + d，全组一致；
 * - 描边/圆角要"视觉恒定"（不随组缩放变粗变细）⇒ 布局值 = 视觉值 ÷ s（见 [visualToLayout]）。
 */
object FlightTransform {

    data class Affine(val scaleX: Float, val scaleY: Float, val dx: Float, val dy: Float) {
        /** 组内统一缩放（描边/圆角补偿基准；两轴非等时取较小者）。 */
        val scale: Float get() = minOf(scaleX, scaleY)
    }

    /** 基座矩形 → 当前矩形的组仿射。 */
    fun of(base: WindowRect, current: WindowRect): Affine {
        val sx = if (base.width > 0) current.width.toFloat() / base.width else 1f
        val sy = if (base.height > 0) current.height.toFloat() / base.height else 1f
        return Affine(
            scaleX = sx,
            scaleY = sy,
            dx = (current.left - base.left).toFloat(),
            dy = (current.top - base.top).toFloat()
        )
    }

    /** 视图旋转缩放中心（局部坐标）= 布局位置的负值 ⇒ 绕"基座原点"缩放，全组同仿射。 */
    fun pivotFor(layoutPos: Int): Float = -layoutPos.toFloat()

    /** 视觉值 → 布局值（÷s）：描边/圆角在组缩放 s 下维持视觉恒定。 */
    fun visualToLayout(visualPx: Float, scale: Float): Float =
        if (scale > 0.01f) visualPx / scale else visualPx
}
