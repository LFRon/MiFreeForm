package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/**
 * 回收区投放登场的纯策略（零平台依赖；宿主只传值并执行）。
 *
 * 形态（对齐 ColorOS FlexibleFloatHandleAnimationSpec 的 minimize 家族）：
 * 从**松手点**弹出一张"种子卡片"（目标卡片等比放大 [START_SCALE]），随后由弹簧
 * （[MiniAnimPolicy] DROP_ENTRY 族）收缩入 mini 槽位——一条短程弹簧飞行，
 * 起手即近终态大小，不再出现"整屏大卡片收缩"的抽象轨迹（本轮视频逐帧实锤问题）。
 *
 * 旧实现（START_SCALE=3.0、500ms 强减速曲线、160ms 淡入）已废弃：起点近全屏，
 * 视觉上等同"全屏应用塌缩进角落"，与本模块"松手点弹出"的语义不符。
 */
object DropEntryPolicy {

    /** 种子卡片 = 目标卡片的等比放大倍数。 */
    const val START_SCALE = 1.25f

    /**
     * 起点矩形（回收区投放登场）：以松手点 [dropX]/[dropY] 为中心、目标等比放大
     * [START_SCALE]，四边夹取到安全区（顶 = 状态栏安全带、底 = 导航栏安全带、左右 = [marginPx]）。
     * 松手点未知（< 0，如调试命令未携带）时退回目标中心原地弹出。
     */
    fun startRect(
        target: WindowRect,
        dropX: Int,
        dropY: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int,
        topInsetPx: Int,
        bottomInsetPx: Int
    ): WindowRect {
        val w = (target.width * START_SCALE).toInt().coerceAtLeast(1)
        val h = (target.height * START_SCALE).toInt().coerceAtLeast(1)
        val cx = if (dropX >= 0) dropX else (target.left + target.right) / 2
        val cy = if (dropY >= 0) dropY else (target.top + target.bottom) / 2
        val minLeft = marginPx
        val maxLeft = (screenWidth - w - marginPx).coerceAtLeast(minLeft)
        val minTop = topInsetPx.coerceAtLeast(marginPx)
        val maxTop = (screenHeight - bottomInsetPx - h - marginPx).coerceAtLeast(minTop)
        val left = (cx - w / 2).coerceIn(minLeft, maxLeft)
        val top = (cy - h / 2).coerceIn(minTop, maxTop)
        return WindowRect(left, top, left + w, top + h)
    }
}
