package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropTargetCalculatorTest {

    @Test
    fun cornerTopLeft_isHoveredAndActive() {
        val r = DropTargetCalculator.compute(10f, 10f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.CORNER_TL, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun cornerTopRight_isHoveredAndActive() {
        val r = DropTargetCalculator.compute(995f, 5f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.CORNER_TR, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun cornerBottomLeft_isHoveredAndActive() {
        val r = DropTargetCalculator.compute(5f, 1995f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.CORNER_BL, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun cornerBottomRight_isHoveredAndActive() {
        val r = DropTargetCalculator.compute(995f, 1995f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.CORNER_BR, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun cornerHoverOutsideRadius_isHoveredNotActive() {
        val r = DropTargetCalculator.compute(200f, 0f, 1000, 1000, allowCorners = true)
        assertEquals(DropKind.CORNER_TL, r.hover)
        assertFalse(r.active)
    }

    @Test
    fun justInsideRadius_isActive() {
        val r = DropTargetCalculator.compute(199f, 0f, 1000, 1000, allowCorners = true)
        assertEquals(DropKind.CORNER_TL, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun farFromEdges_isNoHover() {
        val r = DropTargetCalculator.compute(500f, 1000f, 1000, 2000, allowCorners = true)
        assertEquals(null, r.hover)
        assertFalse(r.active)
    }

    @Test
    fun sideLeft_hoverWithoutActiveZone() {
        val r = DropTargetCalculator.compute(300f, 1000f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_LEFT, r.hover)
        assertFalse(r.active)
    }

    @Test
    fun sideLeft_activeInsideZone() {
        val r = DropTargetCalculator.compute(10f, 1000f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_LEFT, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun sideLeft_activeZoneShadowedByCornerProximity() {
        val inside = DropTargetCalculator.compute(10f, 360f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_LEFT, inside.hover)
        assertTrue(inside.active)

        val nearTopIsCorner = DropTargetCalculator.compute(10f, 300f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.CORNER_TL, nearTopIsCorner.hover)
        assertFalse(nearTopIsCorner.active)

        val nearBottomIsCorner = DropTargetCalculator.compute(10f, 1700f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.CORNER_BL, nearBottomIsCorner.hover)
        assertFalse(nearBottomIsCorner.active)
    }

    @Test
    fun sideRight_hoverWithoutActiveZone() {
        val r = DropTargetCalculator.compute(700f, 1000f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_RIGHT, r.hover)
        assertFalse(r.active)
    }

    @Test
    fun sideRight_activeInsideZone() {
        val r = DropTargetCalculator.compute(990f, 500f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_RIGHT, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun sideRight_activeZoneHorizontalBoundary() {
        val boundary = DropTargetCalculator.compute(950f, 1000f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_RIGHT, boundary.hover)
        assertFalse(boundary.active)

        val inside = DropTargetCalculator.compute(951f, 1000f, 1000, 2000, allowCorners = true)
        assertEquals(DropKind.SIDE_RIGHT, inside.hover)
        assertTrue(inside.active)
    }

    @Test
    fun cornersDisabled_stillChecksSides() {
        val r = DropTargetCalculator.compute(10f, 1000f, 1000, 2000, allowCorners = false)
        assertEquals(DropKind.SIDE_LEFT, r.hover)
        assertTrue(r.active)
    }

    @Test
    fun cornersDisabled_atCorner_positionFallsToSideHover() {
        val r = DropTargetCalculator.compute(10f, 10f, 1000, 2000, allowCorners = false)
        assertEquals(DropKind.SIDE_LEFT, r.hover)
        assertFalse(r.active)
    }

    @Test
    fun invalidMetrics_isNoHover() {
        val r = DropTargetCalculator.compute(0f, 0f, 0, 0, allowCorners = true)
        assertEquals(null, r.hover)
        assertFalse(r.active)
    }

    @Test
    fun landscapeCornersBelongToTheSideEvenWhenCallerAllowsCorners() {
        val r = DropTargetCalculator.compute(10f, 10f, 2000, 1000, allowCorners = true)
        assertEquals(DropKind.SIDE_LEFT, r.hover)
        assertTrue(r.active)
    }
}
