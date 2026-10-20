package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

object DropTargetCalculator {
    const val CORNER_RADIUS_RATIO = 0.20f
    const val PROXIMITY_FACTOR = 1.75f
    const val SIDE_WIDTH_RATIO = 0.05f
    const val SIDE_HEIGHT_START_RATIO = 0.10f
    const val SIDE_HEIGHT_END_RATIO = 0.90f

    data class Highlight(val hover: DropKind?, val active: Boolean)

    fun compute(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        allowCorners: Boolean
    ): Highlight {
        val sw = screenWidth.toFloat()
        val sh = screenHeight.toFloat()
        if (sw == 0f || sh == 0f) return Highlight(null, false)

        val radius = if (sw > sh) sh * CORNER_RADIUS_RATIO else sw * CORNER_RADIUS_RATIO
        val proximityRadius = radius * PROXIMITY_FACTOR

        val distTL = sqrt(x.pow(2) + y.pow(2))
        val distTR = sqrt((sw - x).pow(2) + y.pow(2))
        val distBL = sqrt(x.pow(2) + (sh - y).pow(2))
        val distBR = sqrt((sw - x).pow(2) + (sh - y).pow(2))

        val minDist = min(distTL, min(distTR, min(distBL, distBR)))

        val corner = if (allowCorners) {
            when {
                distTL == minDist && distTL < proximityRadius -> DropKind.CORNER_TL
                distTR == minDist && distTR < proximityRadius -> DropKind.CORNER_TR
                distBL == minDist && distBL < proximityRadius -> DropKind.CORNER_BL
                distBR == minDist && distBR < proximityRadius -> DropKind.CORNER_BR
                else -> null
            }
        } else {
            null
        }

        if (corner != null) {
            return Highlight(corner, minDist < radius)
        }

        val side = when {
            x < proximityRadius -> DropKind.SIDE_LEFT
            x > sw - proximityRadius -> DropKind.SIDE_RIGHT
            else -> null
        }

        val active = when (side) {
            DropKind.SIDE_LEFT ->
                x < sw * SIDE_WIDTH_RATIO && y > sh * SIDE_HEIGHT_START_RATIO && y < sh * SIDE_HEIGHT_END_RATIO
            DropKind.SIDE_RIGHT ->
                x > sw * (1f - SIDE_WIDTH_RATIO) && y > sh * SIDE_HEIGHT_START_RATIO && y < sh * SIDE_HEIGHT_END_RATIO
            else -> false
        }

        return Highlight(side, active)
    }
}
