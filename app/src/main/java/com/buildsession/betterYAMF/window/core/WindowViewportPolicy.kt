package com.buildsession.betterYAMF.window.core

import kotlin.math.roundToInt

/** One fixed viewport, chosen after Home's rotation settles; subsequent motion only scales it. */
object WindowViewportPolicy {
    fun logicalBounds(displayWidth: Int, contentWidth: Int, contentHeight: Int): WindowRect {
        require(displayWidth > 0 && contentWidth > 0 && contentHeight > 0)
        // Match fullscreen UI density in the destination orientation. Always using the short
        // display edge also in landscape creates an incorrectly narrow logical application.
        val height = (displayWidth.toDouble() * contentHeight / contentWidth).roundToInt().coerceAtLeast(1)
        return WindowRect(0, 0, displayWidth, height)
    }
}
