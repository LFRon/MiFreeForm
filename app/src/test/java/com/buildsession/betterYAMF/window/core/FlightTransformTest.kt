package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FlightTransformTest {

    @Test
    fun `affine maps base rect to current rect`() {
        val a = FlightTransform.of(
            WindowRect(0, 0, 1000, 1000),
            WindowRect(200, 300, 700, 900)
        )
        assertEquals(0.5f, a.scaleX, 1e-4f)
        assertEquals(0.6f, a.scaleY, 1e-4f)
        assertEquals(0.5f, a.scale, 1e-4f)
        assertEquals(200f, a.dx, 1e-4f)
        assertEquals(300f, a.dy, 1e-4f)
    }

    @Test
    fun `pivot formula reproduces group affine for any group member`() {
        // 视图的视觉位置 = p×s + d（p = 基座布局位置）。
        // 用视图变换式验证：visual = p + translation + pivot×(1−s)，pivot = −p。
        val a = FlightTransform.of(WindowRect(0, 0, 1000, 1200), WindowRect(100, 50, 600, 650))
        val points = listOf(0 to 0, 480 to 1100, 1000 to 1200)
        for ((px, py) in points) {
            val pivotX = FlightTransform.pivotFor(px)
            val pivotY = FlightTransform.pivotFor(py)
            val visualX = px + a.dx + pivotX * (1f - a.scaleX)
            val visualY = py + a.dy + pivotY * (1f - a.scaleY)
            assertEquals(px * a.scaleX + a.dx, visualX, 1e-3f)
            assertEquals(py * a.scaleY + a.dy, visualY, 1e-3f)
        }
    }

    @Test
    fun `stroke radius compensation keeps visual constant`() {
        // s = 0.3 时布局值应放大（19px 视觉 → 63px 布局），绘出后仍是 19px 视觉。
        val layout = FlightTransform.visualToLayout(19f, 0.3f)
        assertEquals(63.33f, layout, 0.1f)
        assertEquals(19f, layout * 0.3f, 0.1f)
    }

    @Test
    fun `degenerate scale falls back without division blowup`() {
        assertEquals(5f, FlightTransform.visualToLayout(5f, 0f), 1e-4f)
        val a = FlightTransform.of(WindowRect(0, 0, 0, 0), WindowRect(10, 10, 10, 10))
        assertEquals(1f, a.scaleX, 1e-4f)
        assertEquals(1f, a.scaleY, 1e-4f)
    }
}
