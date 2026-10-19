package com.buildsession.betterYAMF.window.core

import org.junit.Assert.*
import org.junit.Test

class WindowViewportPolicyTest {
    @Test fun portraitUsesTheFinalHomeWidthAndPreservesContentAspect() {
        val r = WindowViewportPolicy.logicalBounds(1264, 898, 1139)
        assertEquals(1264, r.width)
        assertTrue(r.height > r.width)
        assertEquals(898f / 1139f, r.width.toFloat() / r.height, 0.001f)
    }

    @Test fun aLandscapeHomeUsesLandscapeFullscreenWidth() {
        val r = WindowViewportPolicy.logicalBounds(2780, 1973, 518)
        assertEquals(2780, r.width)
        assertEquals(730, r.height)
        assertEquals(1973f / 2780f, 518f / r.height, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyContentViewportNeverProducesANativeLayout() {
        WindowViewportPolicy.logicalBounds(1264, 0, 1139)
    }
}
