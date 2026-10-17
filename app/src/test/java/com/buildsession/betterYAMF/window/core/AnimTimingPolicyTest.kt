package com.buildsession.betterYAMF.window.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimTimingPolicyTest {

    @Test
    fun `缩放为零时立即完成`() {
        assertEquals(0L, AnimTimingPolicy.resolveDuration(300f, 0f))
        assertEquals(0L, AnimTimingPolicy.resolveDuration(300f, -1f))
    }

    @Test
    fun `缩放为一取配置时长`() {
        assertEquals(300L, AnimTimingPolicy.resolveDuration(300f, 1f))
    }

    @Test
    fun `配置达到上限视为自动用基准`() {
        assertEquals(AnimTimingPolicy.BASE_DURATION_MS, AnimTimingPolicy.resolveDuration(5100f, 1f))
        assertEquals(AnimTimingPolicy.BASE_DURATION_MS, AnimTimingPolicy.resolveDuration(9000f, 1f))
    }

    @Test
    fun `基准可覆盖_投放登场用 500ms`() {
        assertEquals(500L, AnimTimingPolicy.resolveDuration(5100f, 1f, baseMs = DropEntryPolicy.DURATION_BASE_MS))
    }

    @Test
    fun `缩放比例折减_含 Float 截断语义`() {
        assertEquals(150L, AnimTimingPolicy.resolveDuration(300f, 0.5f))
        // 333.7.toLong() = 333；333 * 1.5 = 499.5 → 截断 499（与原实现一致）
        assertEquals(499L, AnimTimingPolicy.resolveDuration(333.7f, 1.5f))
    }
}
