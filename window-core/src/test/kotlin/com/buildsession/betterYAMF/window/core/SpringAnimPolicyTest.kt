package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringAnimPolicyTest {

    @Test
    fun `unitless tracks use their own precision instead of pixel settling thresholds`() {
        val track = SpringTrack(0f, 1f, MiniAnimPolicy.spec(MiniAnimKind.MINIMIZE, MiniAnimProp.W), 0.001f, 0.01f)
        track.step(32f)
        assertTrue(track.value > 0f && track.value < 1f)
        assertFalse(track.atRest())
        repeat(100) { track.step(16f) }
        assertTrue(track.atRest())
        assertEquals(1f, track.value, 0f)
    }

    @Test
    fun `stiffness follows (2pi over response)^2 and damping is 1 minus bounce`() {
        val s = SpringSpec.of(0.30f, -0.05f)
        assertEquals(438.65f, s.stiffness, 1.0f)
        assertEquals(1.05f, s.dampingRatio, 1e-4f)
        val s2 = SpringSpec.of(0.20f, 0f)
        assertEquals(986.96f, s2.stiffness, 1.0f)
        assertEquals(1.0f, s2.dampingRatio, 1e-4f)
    }

    @Test
    fun `policy matrix matches ColorOS defaults`() {
        // 反编译实证默认值抽查（FlexibleFloatHandleAnimationSpec static 块）
        assertEquals(SpringSpec.of(0.30f, -0.05f), MiniAnimPolicy.spec(MiniAnimKind.OPEN, MiniAnimProp.TX))
        assertEquals(SpringSpec.of(0.45f, -0.05f), MiniAnimPolicy.spec(MiniAnimKind.OPEN, MiniAnimProp.TY))
        assertEquals(SpringSpec.of(0.28f, -0.05f), MiniAnimPolicy.spec(MiniAnimKind.MINIMIZE, MiniAnimProp.W))
        assertEquals(SpringSpec.of(0.20f, -0.20f), MiniAnimPolicy.spec(MiniAnimKind.MINIMIZE, MiniAnimProp.ALPHA))
        // 投放登场取 MINIMIZE 族
        assertEquals(
            MiniAnimPolicy.spec(MiniAnimKind.MINIMIZE, MiniAnimProp.TX),
            MiniAnimPolicy.spec(MiniAnimKind.DROP_ENTRY, MiniAnimProp.TX)
        )
    }

    @Test
    fun `track converges to target and reports at rest`() {
        val track = SpringTrack(0f, 100f, MiniAnimPolicy.spec(MiniAnimKind.OPEN, MiniAnimProp.TX))
        var t = 0f
        while (t < 1500f && !track.atRest()) {
            track.step(16f)
            t += 16f
        }
        assertTrue("spring should settle within 1.5s (t=$t)", track.atRest())
        assertEquals(100f, track.value, 1e-3f)
    }

    @Test
    fun `overdamped spring never overshoots`() {
        val track = SpringTrack(0f, 100f, MiniAnimPolicy.spec(MiniAnimKind.OPEN, MiniAnimProp.TY))
        var maxValue = 0f
        repeat(120) {
            track.step(16f)
            maxValue = maxOf(maxValue, track.value)
        }
        assertTrue("overdamped (bounce -0.05) must not exceed target: $maxValue", maxValue <= 100.5f)
    }

    @Test
    fun `undamped bounce can overshoot`() {
        // bounce 0.25 ⇒ ζ=0.75，允许过冲（对照组：证明积分器不是单调插值）
        val track = SpringTrack(0f, 100f, SpringSpec.of(0.30f, 0.25f))
        var maxValue = 0f
        repeat(120) {
            track.step(16f)
            maxValue = maxOf(maxValue, track.value)
        }
        assertTrue("bounce 0.25 should overshoot: $maxValue", maxValue > 101f)
    }

    @Test
    fun `interrupt reseeds without position jump and keeps velocity`() {
        val spec = MiniAnimPolicy.spec(MiniAnimKind.MINIMIZE, MiniAnimProp.TX)
        val track = SpringTrack(0f, 400f, spec)
        repeat(6) { track.step(16f) }
        val midValue = track.value
        val midVelocity = track.velocity
        assertFalse(track.atRest())
        track.seed(midValue, midVelocity)
        track.retarget(120f)
        track.step(16f)
        // 续接步进：位置连续（变化量应在一帧速度量级，而不是回跳原点）
        assertTrue("continuity violated: ${track.value} vs mid=$midValue", abs(track.value - midValue) < 80f)
    }

    @Test
    fun `retarget moves spring toward new endpoint`() {
        val track = SpringTrack(0f, 400f, MiniAnimPolicy.spec(MiniAnimKind.MINIMIZE, MiniAnimProp.TY))
        repeat(10) { track.step(16f) }
        track.retarget(0f)
        var t = 0f
        while (t < 1500f && !track.atRest()) {
            track.step(16f)
            t += 16f
        }
        assertEquals(0f, track.value, 1e-3f)
    }
}
