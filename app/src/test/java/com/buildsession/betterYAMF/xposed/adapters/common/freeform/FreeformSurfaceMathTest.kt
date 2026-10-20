package com.buildsession.betterYAMF.xposed.adapters.common.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeformSurfaceMathTest {

    @Test
    fun computesUniformScaleAndPosition() {
        val t = FreeformSurfaceMath.compute(
            physicalLeft = 100,
            physicalTop = 200,
            physicalWidth = 300,
            physicalHeight = 400,
            spoofedWidth = 600,
            spoofedHeight = 800
        )!!
        assertEquals(0.5f, t.scaleX, 0.0001f)
        assertEquals(0.5f, t.scaleY, 0.0001f)
        assertEquals(100f, t.positionX, 0.0001f)
        assertEquals(200f, t.positionY, 0.0001f)
    }

    @Test
    fun fitsUniformScaleAndCentersContentVertically() {
        val t = FreeformSurfaceMath.compute(0, 0, 250, 500, 1000, 1000)!!
        assertEquals(0.25f, t.scaleX, 0.0001f)
        assertEquals(0.25f, t.scaleY, 0.0001f)
        assertEquals(0f, t.positionX, 0.0001f)
        assertEquals(125f, t.positionY, 0.0001f)
    }

    @Test
    fun fitsUniformScaleAndCentersContentHorizontally() {
        val t = FreeformSurfaceMath.compute(10, 20, 1000, 500, 1000, 1000)!!
        assertEquals(0.5f, t.scaleX, 0.0001f)
        assertEquals(0.5f, t.scaleY, 0.0001f)
        assertEquals(260f, t.positionX, 0.0001f)
        assertEquals(20f, t.positionY, 0.0001f)
    }

    @Test
    fun rejectsEmptySpoofedBounds() {
        assertNull(FreeformSurfaceMath.compute(0, 0, 300, 400, 0, 800))
        assertNull(FreeformSurfaceMath.compute(0, 0, 300, 400, 600, 0))
        assertNull(FreeformSurfaceMath.compute(0, 0, 300, 400, -1, 800))
    }

    @Test
    fun inputScaleIsInverseOfSurfaceScale() {
        assertEquals(2f, FreeformSurfaceMath.inputScale(0.5f), 0.0001f)
        assertEquals(0.5f, FreeformSurfaceMath.inputScale(2f), 0.0001f)
    }
}
