package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropEntryPolicyTest {

    /** mini 目标卡片（真机量级：约 360×460）。 */
    private val target = WindowRect(left = 1000, top = 200, right = 1200, bottom = 420)

    private val screenW = 1264
    private val screenH = 2780
    private val margin = 24
    private val topInset = 140
    private val bottomInset = 120

    private fun start(dropX: Int, dropY: Int) =
        DropEntryPolicy.startRect(target, dropX, dropY, screenW, screenH, margin, topInset, bottomInset)

    @Test
    fun `种子卡片尺寸为目标等比放大`() {
        val s = start(target.right, target.top + 100)
        assertEquals((target.width * DropEntryPolicy.START_SCALE).toInt(), s.width)
        assertEquals((target.height * DropEntryPolicy.START_SCALE).toInt(), s.height)
    }

    @Test
    fun `种子卡片以松手点为中心`() {
        // 松手点取屏中部，避免夹取
        val s = start(600, 1400)
        assertEquals(600, (s.left + s.right) / 2)
        assertEquals(1400, (s.top + s.bottom) / 2)
    }

    @Test
    fun `靠角落松手点被夹到安全区`() {
        // 右上角松手点（投放典型位置）：中心应被夹到安全带内，四边可见
        val s = start(screenW - 4, 10)
        assertTrue("left >= margin", s.left >= margin)
        assertTrue("top >= topInset", s.top >= topInset)
        assertTrue("right <= screenW - margin", s.right <= screenW - margin)
        assertTrue("bottom <= screenH - bottomInset - margin", s.bottom <= screenH - bottomInset - margin)
    }

    @Test
    fun `左界不小于边距`() {
        val s = start(0, 1400)
        assertEquals(margin, s.left)
    }

    @Test
    fun `松手点未知时退回目标中心原地弹出`() {
        val s = start(-1, -1)
        assertEquals((target.left + target.right) / 2, (s.left + s.right) / 2)
        assertEquals((target.top + target.bottom) / 2, (s.top + s.bottom) / 2)
    }
}
