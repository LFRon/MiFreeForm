package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizePolicyTest {

    @Test
    fun `horizontal drag dominates - width follows finger at full speed`() {
        val f = ResizePolicy.dragScaleFactor(1000, 2000, 100f, 10f)
        assertEquals(1.1f, f, 1e-4f)
    }

    @Test
    fun `vertical drag dominates - height follows finger at full speed`() {
        val f = ResizePolicy.dragScaleFactor(1000, 2000, 10f, 400f)
        assertEquals(1.2f, f, 1e-4f)
    }

    @Test
    fun `shrink by dragging opposite direction`() {
        val f = ResizePolicy.dragScaleFactor(1000, 2000, -250f, 0f)
        assertEquals(0.75f, f, 1e-4f)
    }

    @Test
    fun `equal axis ratios - expression is continuous`() {
        // |dx/w| == |dy/h|（如对角拖拽）：两式恒等，系数 = 1 + dx/w
        val f = ResizePolicy.dragScaleFactor(500, 1000, 50f, 100f)
        assertEquals(1.1f, f, 1e-4f)
    }

    @Test
    fun `factor clamped by guard rails`() {
        assertEquals(
            ResizePolicy.MIN_FACTOR,
            ResizePolicy.dragScaleFactor(1000, 1000, -100000f, 0f),
            1e-6f
        )
        assertEquals(
            ResizePolicy.MAX_FACTOR,
            ResizePolicy.dragScaleFactor(1000, 1000, 100000f, 0f),
            1e-6f
        )
    }

    @Test
    fun `invalid begin size returns identity`() {
        assertEquals(1f, ResizePolicy.dragScaleFactor(0, 0, 100f, 100f), 0f)
        assertEquals(1f, ResizePolicy.dragScaleFactor(-5, 10, 100f, 100f), 0f)
        assertEquals(1f, ResizePolicy.dragScaleFactor(10, 0, 100f, 100f), 0f)
    }

    @Test
    fun `scaled size preserves aspect within one pixel`() {
        val (w, h) = ResizePolicy.scaledSize(936, 1512, 1.37f)
        assertEquals(1282, w)
        assertEquals(2071, h)
        assertTrue(abs(w.toFloat() / h - 936f / 1512f) < 0.002f)
    }

    @Test
    fun `scaled size never degenerates to zero`() {
        val (w, h) = ResizePolicy.scaledSize(100, 200, 0.001f)
        assertEquals(1, w)
        assertEquals(1, h)
    }
}
