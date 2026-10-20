package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/**
 * 动画时长解析策略（零平台依赖；宿主只读平台值并传入）：
 * 实际时长 = 配置时长 × 开发者选项"动画时长缩放"；缩放为 0 → 0（立即完成）；
 * 模块动画速度配置 ≥ [CONFIGURED_MAX_MS] 视为"自动"（改用 [baseMs]）。
 * 行为与原宿主实现逐式一致（Float→Long 截断语义相同）。
 */
object AnimTimingPolicy {

    /** 几何动画基准时长（毫秒）。 */
    const val BASE_DURATION_MS = 260L

    /** 模块动画速度配置上限：≥ 此值视为自动（不采用配置值）。 */
    const val CONFIGURED_MAX_MS = 5100f

    fun resolveDuration(
        configuredMs: Float,
        animatorScale: Float,
        baseMs: Long = BASE_DURATION_MS
    ): Long {
        if (animatorScale <= 0f) return 0L
        val configured = if (configuredMs < CONFIGURED_MAX_MS) configuredMs.toLong() else baseMs
        return (configured * animatorScale).toLong()
    }
}
