package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

import kotlin.math.roundToInt

/** One fixed viewport, chosen after Home's rotation settles; subsequent motion only scales it. */
object WindowViewportPolicy {
    fun logicalBounds(displayWidth: Int, contentWidth: Int, contentHeight: Int): WindowRect {
        require(displayWidth > 0 && contentWidth > 0 && contentHeight > 0)
        // Caller selects the portrait reference width, independent of physical display rotation.
        val height = (displayWidth.toDouble() * contentHeight / contentWidth).roundToInt().coerceAtLeast(1)
        return WindowRect(0, 0, displayWidth, height)
    }
}
