package com.buildsession.betterYAMF.xposed.hook.launcher

import com.buildsession.betterYAMF.window.core.RecentsGesturePolicy
import com.buildsession.betterYAMF.window.core.WindowRect

import org.junit.Assert.*
import org.junit.Test

class LauncherCoordinateMapperTest {
    private fun WindowRect.contains(point: LauncherCoordinateMapper.Point) =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom

    @Test fun bothLandscapeRotationsRecognizeThePhysicalUpperRight() {
        val zone = RecentsGesturePolicy.dropZone(2780, 1264)
        val right = LauncherCoordinateMapper.rotatePoint(1100f, 2480f, 0, 1, 2780, 1264)
        assertEquals(LauncherCoordinateMapper.Point(2480f, 164f), right)
        assertTrue(zone.contains(right))
        val reverse = LauncherCoordinateMapper.rotatePoint(100f, 300f, 0, 3, 2780, 1264)
        assertEquals(LauncherCoordinateMapper.Point(2480f, 100f), reverse)
        assertTrue(zone.contains(reverse))
    }

    @Test fun rotationDuringTheGestureKeepsProgressCoordinatesContinuous() {
        val before = LauncherCoordinateMapper.rotatePoint(300f, 164f, 1, 1, 2780, 1264)
        val after = LauncherCoordinateMapper.rotatePoint(1100f, 300f, 0, 1, 2780, 1264)
        assertEquals(before, after)
    }

    @Test fun allRotationPairsRoundTripWithoutDependingOnFingerLocation() {
        for (from in 0..3) for (to in 0..3) {
            val sourceW = if (from % 2 == 0) 1264 else 2780
            val sourceH = if (from % 2 == 0) 2780 else 1264
            val targetW = if (to % 2 == 0) 1264 else 2780
            val targetH = if (to % 2 == 0) 2780 else 1264
            for (x in listOf(0f, 9f, sourceW * 0.6f, sourceW.toFloat())) {
                for (y in listOf(0f, 12f, sourceH * 0.9f, sourceH.toFloat())) {
                    val mapped = LauncherCoordinateMapper.rotatePoint(x, y, from, to, targetW, targetH)
                    val restored = LauncherCoordinateMapper.rotatePoint(mapped.x, mapped.y, to, from, sourceW, sourceH)
                    assertEquals(x, restored.x, 0.001f)
                    assertEquals(y, restored.y, 0.001f)
                }
            }
        }
    }

    @Test fun currentViewportUpperRightIsTouchableForEveryInputRotation() {
        for (viewportRotation in 0..3) {
            val width = if (viewportRotation % 2 == 0) 1264 else 2780
            val height = if (viewportRotation % 2 == 0) 2780 else 1264
            val zone = RecentsGesturePolicy.dropZone(width, height)
            assertEquals(width, zone.right)
            assertEquals(0, zone.top)
            for (inputRotation in 0..3) {
                val inputWidth = if (inputRotation % 2 == 0) 1264 else 2780
                val inputHeight = if (inputRotation % 2 == 0) 2780 else 1264
                val input = LauncherCoordinateMapper.rotatePoint(width * 0.9f, height * 0.1f,
                    viewportRotation, inputRotation, inputWidth, inputHeight)
                val normalized = LauncherCoordinateMapper.rotatePoint(input.x, input.y,
                    inputRotation, viewportRotation, width, height)
                assertTrue(zone.contains(normalized))
            }
        }
    }

    @Test fun changingViewportReanchorsTheTargetInsteadOfRotatingItsOldCorner() {
        val oldPortraitCorner = LauncherCoordinateMapper.Point(1263f, 1f)
        val mappedOldCorner = LauncherCoordinateMapper.rotatePoint(oldPortraitCorner.x, oldPortraitCorner.y,
            0, 1, 2780, 1264)
        val current = RecentsGesturePolicy.dropZone(2780, 1264)
        assertFalse(current.contains(mappedOldCorner))
        assertTrue(current.contains(LauncherCoordinateMapper.Point(2779f, 1f)))
    }
}
