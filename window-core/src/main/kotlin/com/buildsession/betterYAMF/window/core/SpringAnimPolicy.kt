package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 弹簧参数（纯数值，零平台依赖）：
 * stiffness = (2π / response)²，dampingRatio = 1 - bounce —— ColorOS 反编译实证
 * （FlexibleAnimationParamsUtils#getStiffnessFromResponse / #getDampingRatioFromBounce）。
 * response 单位≈秒（越小越快），bounce 为过冲量（负值 = 过阻尼、无过冲）。
 */
data class SpringSpec(val stiffness: Float, val dampingRatio: Float) {
    companion object {
        fun of(response: Float, bounce: Float): SpringSpec {
            val r = if (response <= 0f) 1f else response
            val stiffness = (2.0 * Math.PI / r).let { (it * it).toFloat() }
            val damping = if (1f - bounce <= 0f) dampingRatioFloor else 1f - bounce
            return SpringSpec(stiffness, damping)
        }
    }
}

private const val dampingRatioFloor = 0.15f

/** mini 系列几何动画的形态（对应 ColorOS FlexibleFloatHandleAnimationSpec 的动画类型族）。 */
enum class MiniAnimKind {
    /** 展开态 → mini（点击/拖拽/抛掷最小化，ColorOS 类型 1/2/4）。 */
    MINIMIZE,

    /** mini → 展开态（"调出来"，ColorOS 类型 8 OPEN）。 */
    OPEN,

    /** 回收区投放登场：从松手点弹出并弹簧入槽位（入 mini 语义，取 MINIMIZE 族参数）。 */
    DROP_ENTRY
}

/** 参与过渡的几何属性（与 ColorOS 十属性中 leash 侧同源：tx/ty/w/h/alpha）。 */
enum class MiniAnimProp { TX, TY, W, H, ALPHA }

/**
 * mini 系列弹簧参数矩阵（观感参数在逻辑层，可单测）。
 *
 * 数值 = ColorOS 16 `persist.wm.debug.flexible.*` 默认值（反编译实证，点击行为列）：
 * | 属性  | MINIMIZE response/bounce | OPEN response/bounce |
 * | tx    | 0.48 / -0.05             | 0.30 / -0.05         |
 * | ty    | 0.30 / -0.05             | 0.45 / -0.05         |
 * | w     | 0.28 / -0.05             | 0.40 / -0.05         |
 * | h     | 0.30 / -0.05             | 0.40 / -0.05         |
 * | alpha | 0.20 / -0.20             | 0.45 / -0.05         |
 *
 * 解读：X 快于 Y、尺寸居中、无过冲（-0.05 ⇒ 过阻尼 1.05）——横向先到位、纵向略晚、尺寸收尾，
 * 视觉上呈现"甩入槽位"的有机感；alpha 在最小化时快速淡出（-0.20 ⇒ 更硬的过阻尼）。
 */
object MiniAnimPolicy {

    fun responseBounce(kind: MiniAnimKind, prop: MiniAnimProp): Pair<Float, Float> = when (kind) {
        MiniAnimKind.MINIMIZE, MiniAnimKind.DROP_ENTRY -> when (prop) {
            MiniAnimProp.TX -> 0.48f to -0.05f
            MiniAnimProp.TY -> 0.30f to -0.05f
            MiniAnimProp.W -> 0.28f to -0.05f
            MiniAnimProp.H -> 0.30f to -0.05f
            MiniAnimProp.ALPHA -> 0.20f to -0.20f
        }
        MiniAnimKind.OPEN -> when (prop) {
            MiniAnimProp.TX -> 0.30f to -0.05f
            MiniAnimProp.TY -> 0.45f to -0.05f
            MiniAnimProp.W -> 0.40f to -0.05f
            MiniAnimProp.H -> 0.40f to -0.05f
            MiniAnimProp.ALPHA -> 0.45f to -0.05f
        }
    }

    fun spec(kind: MiniAnimKind, prop: MiniAnimProp): SpringSpec =
        responseBounce(kind, prop).let { (response, bounce) -> SpringSpec.of(response, bounce) }

    /**
     * 带时间缩放的弹簧参数（开发者选项"动画时长缩放"）：等效 response × timeScale
     * （0 值由宿主改为"立即完成"，不在此处理）。
     */
    fun spec(kind: MiniAnimKind, prop: MiniAnimProp, timeScale: Float): SpringSpec {
        val (response, bounce) = responseBounce(kind, prop)
        return SpringSpec.of(response * timeScale.coerceIn(0.05f, 10f), bounce)
    }

    /** 全部轨道进入静止的兜底时限（毫秒；弹簧理论上无限趋近，宿主超过此值直接落终态）。 */
    const val SETTLE_TIMEOUT_MS = 1200L
}

/**
 * 单条属性的弹簧轨迹（纯数值积分；可打断 —— 保留位置与速度后重新瞄准，对应 ColorOS
 * SUPPORT_MULTI_INTERRUPT_ANIMATION：反向/重定向不跳变、速度连续）。
 *
 * 积分：半隐式欧拉，质量 1，阻尼系数 c = 2·√k·ζ；子步 1ms 保证稳定性。
 * 静止判据：|x−target| < [POS_EPS] 且 |v| < [VEL_EPS]（像素/秒）。
 */
class SpringTrack(
    start: Float,
    target: Float,
    private val spec: SpringSpec,
    private val positionThreshold: Float = 0.5f,
    private val velocityThreshold: Float = 30f
) {
    var value: Float = start
        private set
    var velocity: Float = 0f
        private set
    var target: Float = target
        private set
    private var resting = false

    fun retarget(newTarget: Float) {
        target = newTarget
        resting = false
    }

    /** 打断续接：以当前位置/速度为新起点（速度由宿主按前帧差分提供）。 */
    fun seed(currentValue: Float, currentVelocity: Float) {
        value = currentValue
        velocity = currentVelocity
        resting = false
    }

    /** 步进 [dtMs] 毫秒，返回最新值；已静止时直接返回 target。 */
    fun step(dtMs: Float): Float {
        if (resting) return target
        var remaining = dtMs.coerceIn(0f, 64f)
        val k = spec.stiffness
        val c = 2f * sqrt(k) * spec.dampingRatio
        while (remaining > 0f) {
            val h = (if (remaining > 1f) 1f else remaining) / 1000f
            val a = -k * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            remaining -= h * 1000f
        }
        if (abs(value - target) < positionThreshold && abs(velocity) < velocityThreshold) {
            value = target
            velocity = 0f
            resting = true
        }
        return value
    }

    fun atRest(): Boolean = resting

}
