package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.abs

/**
 * 窗口缩放（resize）策略：**等比纯缩放** —— 对齐 ColorOS 16 灵活窗口的缩放逻辑。
 *
 * ColorOS 事实依据（对 ColorOS 16 系统实现的反编译实证）：
 * - 任务虚拟尺寸恒定（`OplusFlexibleRatioData` 比例族；`OplusFlexibleTaskLayoutPolicy`
 *   `updateLaunchBoundsAndCalScale` 只在启动/比例切换/显示变化时改虚拟尺寸），窗口大小
 *   变化**只改显示 scale**；
 * - 拖拽逐帧只动表面：`FlexibleFloatHandleAnimationSpec`
 *   `transaction.setPosition(...).setWindowCrop(...).setScale(..., curScale, curScale)
 *   .setCornerRadius(radius / curScale)`；落定把 scale 烘焙回任务表面
 *   （`setScale(mTaskSurface, getScale(), getScale())`）—— 应用全程零重排（无损）。
 *
 * 本策略只回答一件事：**拖拽向量 → 等比缩放系数**（纯几何、可单测）：
 * - 宽高比严格保持（窗口只缩放、绝不变形——与 ColorOS 的比例族一致）；
 * - 手感：以位移占比更大的一轴为准（拖水平=宽度跟手，拖垂直=高度跟手），
 *   两轴交界处连续（|dx/w| == |dy/h| 时两式恒等）；
 * - 只设安全护栏；视觉边界夹取仍走既有 KeepInScreen/Clamp 链路（纯平移、不改尺寸）。
 */
object ResizePolicy {

    /** 缩放系数安全下限（护栏，非视觉下限）。 */
    const val MIN_FACTOR = 0.2f

    /** 缩放系数安全上限（护栏；超屏由 KeepInScreen 平移夹取）。 */
    const val MAX_FACTOR = 5f

    /**
     * 拖拽增量 → 等比缩放系数。
     * 参数与增量同坐标系（窗口内容盒 px）。尺寸非法时返回 1（不动）。
     */
    fun dragScaleFactor(beginWidth: Int, beginHeight: Int, dx: Float, dy: Float): Float {
        if (beginWidth <= 0 || beginHeight <= 0) return 1f
        val fx = dx / beginWidth
        val fy = dy / beginHeight
        val delta = if (abs(fx) >= abs(fy)) fx else fy
        return (1f + delta).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** 等比缩放后的内容盒尺寸（宽高比严格保持；整型取整仅引入 ≤1px 偏差）。 */
    fun scaledSize(beginWidth: Int, beginHeight: Int, factor: Float): Pair<Int, Int> {
        val w = (beginWidth * factor).toInt().coerceAtLeast(1)
        val h = (beginHeight * factor).toInt().coerceAtLeast(1)
        return w to h
    }
}
