package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DropEntryPolicyTest {

    /** mini 目标卡片（真机量级：约 360×460）——起点应为右缘锚定的放大卡片。 */
    private val target = WindowRect(left = 1000, top = 200, right = 1200, bottom = 420)

    private val screenH = 2780
    private val margin = 24
    private val topInset = 140
    private val bottomInset = 120

    @Test
    fun `起点右缘锚定目标右缘_尺寸为放大倍数`() {
        val start = DropEntryPolicy.startRect(target, screenH, margin, topInset, bottomInset)
        assertEquals(target.right, start.right)
        assertEquals((target.width * DropEntryPolicy.START_SCALE).toInt(), start.width)
        assertEquals((target.height * DropEntryPolicy.START_SCALE).toInt(), start.height)
        // 顶边在安全带内不变
        assertEquals(target.top, start.top)
    }

    @Test
    fun `顶边低于安全带下限时被抬到下限`() {
        val low = target.copy(top = 80, bottom = 300)
        val start = DropEntryPolicy.startRect(low, screenH, margin, topInset, bottomInset)
        assertEquals(topInset, start.top)
    }

    @Test
    fun `左界不小于边距`() {
        // 小目标靠左：放大后左界本应为负 → 夹到 margin
        val small = WindowRect(left = 10, top = 200, right = 110, bottom = 300)
        val start = DropEntryPolicy.startRect(small, screenH, margin, topInset, bottomInset)
        assertEquals(margin, start.left)
    }

    @Test
    fun `屏高不足时顶边取下限与安全上界重合值`() {
        // startH = 220*3 = 660；上界 = 900-120-660 = 120 < 下限 140 → coerceAtLeast 140 后被夹到 140
        val start = DropEntryPolicy.startRect(target, screenHeight = 900, margin, topInset, bottomInset)
        assertEquals(topInset, start.top)
    }

    @Test
    fun `景深曲线首末归一无负值`() {
        assertEquals(1f, DropEntryPolicy.dipAt(0f), 1e-6f)
        assertEquals(1f, DropEntryPolicy.dipAt(1f), 1e-4f)
        assertEquals(0.97f, DropEntryPolicy.dipAt(0.5f), 1e-4f)
        for (i in 0..20) {
            val d = DropEntryPolicy.dipAt(i / 20f)
            assertEquals(true, d in 0.969f..1.0001f)
        }
    }
}
