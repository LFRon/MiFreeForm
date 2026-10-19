package com.buildsession.betterYAMF.xposed.hook.organizer

import org.junit.Assert.*
import org.junit.Test

class ViewportTransformTest {
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
