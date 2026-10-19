package com.buildsession.betterYAMF.window.core

import org.junit.Assert.*
import org.junit.Test

class RecentsGesturePolicyTest {
    private fun WindowRect.contains(x: Float, y: Float) =
        x >= left && x < right && y >= top && y < bottom

    @Test fun portraitKeepsTheExistingUpperRightTarget() {
        assertEquals(WindowRect(506, 0, 1264, 834), RecentsGesturePolicy.dropZone(1264, 2780))
    }

    @Test fun landscapeHasALargerUpperRightTargetAndLeavesTheCentralSwipeClear() {
        val zone = RecentsGesturePolicy.dropZone(2780, 1264)
        assertEquals(WindowRect(1529, 0, 2780, 758), zone)
        assertTrue(zone.contains(1800f, 700f))
        assertFalse(zone.contains(1390f, 500f))
        assertFalse(zone.contains(1100f, 700f))
        assertTrue(zone.contains(2600f, 100f))
        assertTrue(zone.width * zone.height > (2780 * 0.5f * 1264 * 0.4f))
    }

}
