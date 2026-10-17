package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClampPolicyTest {

    private val winW = 400
    private val winH = 600
    private val screenW = 1264
    private val screenH = 2780

    @Test
    fun `屏内位置不变`() {
        val o = ClampPolicy.clampOffset(100, 200, winW, winH, screenW, screenH, 0, 0)
        assertEquals(100, o.x)
        assertEquals(200, o.y)
    }

    @Test
    fun `越负数边被夹到零`() {
        val o = ClampPolicy.clampOffset(-50, -80, winW, winH, screenW, screenH, 0, 0)
        assertEquals(0, o.x)
        assertEquals(0, o.y)
    }

    @Test
    fun `越右下边被夹到屏宽减窗宽`() {
        val o = ClampPolicy.clampOffset(9999, 9999, winW, winH, screenW, screenH, 0, 0)
        assertEquals(screenW - winW, o.x)
        assertEquals(screenH - winH, o.y)
    }

    @Test
    fun `帧偏移计入安全边界`() {
        val o = ClampPolicy.clampOffset(9999, 9999, winW, winH, screenW, screenH, 20, 36)
        assertEquals(screenW - winW - 20, o.x)
        assertEquals(screenH - winH - 36, o.y)
    }
}
