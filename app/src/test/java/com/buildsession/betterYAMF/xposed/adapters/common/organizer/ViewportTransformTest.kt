package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import com.buildsession.betterYAMF.window.api.ContentAlignment
import com.buildsession.betterYAMF.window.api.SceneRect
import org.junit.Assert.*
import org.junit.Test

class ViewportTransformTest {
    @Test fun entrySourceAndTaskUseTheSameTopAnchoredCoverProjection() {
        for ((width, height) in listOf(400 to 400, 250 to 550, 550 to 250)) {
            for ((logicalWidth, logicalHeight) in listOf(1264 to 2780, 1264 to 1605, 2780 to 1264)) {
                val f = ViewportTransform.compute(logicalWidth, logicalHeight,
                    80, 120, width, height, 20f, ContentAlignment.TOP)!!
                val image = SceneRect(80f, 120f, width.toFloat(), height.toFloat()).coverTop(
                    logicalWidth.toFloat() / logicalHeight)
                assertEquals(0f, f.cropTop, 0f)
                assertEquals(120f, f.y, 0.001f)
                assertEquals(image.left, f.x, 0.001f)
                assertEquals(image.top, f.y, 0.001f)
                assertEquals(image.width, logicalWidth * f.scale, 0.001f)
                assertEquals(image.height, logicalHeight * f.scale, 0.001f)
                assertEquals(80f, f.x + f.cropLeft * f.scale, 0.001f)
                assertEquals(width.toFloat(), f.cropWidth * f.scale, 0.001f)
                assertEquals(height.toFloat(), f.cropHeight * f.scale, 0.001f)
            }
        }
    }

    @Test fun sameAspectUsesUniformScaleWithoutCropping() {
        val f = ViewportTransform.compute(1000, 1400, 80, 120, 250, 350, 12f)!!
        assertEquals(0.25f, f.scale, 0f)
        assertEquals(1000f, f.cropWidth, 0f); assertEquals(1400f, f.cropHeight, 0f)
        assertEquals(80f, f.x, 0f); assertEquals(120f, f.y, 0f)
        assertEquals(12f, f.cornerRadius * f.scale, 0.001f)
    }

    @Test fun intermediateAspectCropsInsteadOfLetterboxingOrStretching() {
        val f = ViewportTransform.compute(1000, 2000, 80, 120, 400, 400, 20f)!!
        assertEquals(0.4f, f.scale, 0f)
        assertEquals(1000f, f.cropWidth, 0f); assertEquals(1000f, f.cropHeight, 0f)
        assertEquals(500f, f.cropTop, 0f)
        assertEquals(80f, f.x + f.cropLeft * f.scale, 0.001f)
        assertEquals(120f, f.y + f.cropTop * f.scale, 0.001f)
        assertEquals(400f, f.cropWidth * f.scale, 0.001f)
        assertEquals(400f, f.cropHeight * f.scale, 0.001f)
    }

    @Test fun nonzeroHorizontalCropIsAccountedForInPosition() {
        val f = ViewportTransform.compute(2000, 1000, 90, 140, 300, 300, 10f)!!
        assertEquals(500f, f.cropLeft, 0.001f)
        assertEquals(90f, f.x + f.cropLeft * f.scale, 0.001f)
        assertEquals(140f, f.y + f.cropTop * f.scale, 0.001f)
    }

    @Test fun fractionalCropMatchesTheVisibleBoxAcrossAnimationSizes() {
        for (w in 100..910 step 17) for (h in 100..1150 step 19) {
            val f = ViewportTransform.compute(1264, 1603, 31, 159, w, h, 40f)!!
            assertEquals(w.toFloat(), f.cropWidth * f.scale, 0.001f)
            assertEquals(h.toFloat(), f.cropHeight * f.scale, 0.001f)
            assertEquals(31f, f.x + f.cropLeft * f.scale, 0.001f)
            assertEquals(159f, f.y + f.cropTop * f.scale, 0.001f)
            assertEquals(40f, f.cornerRadius * f.scale, 0.001f)
        }
    }

    @Test fun invalidDimensionsAreRejected() {
        assertNull(ViewportTransform.compute(0, 1400, 0, 0, 100, 100, 0f))
        assertNull(ViewportTransform.compute(1000, 1400, 0, 0, 0, 100, 0f))
    }
}
