package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*

/** The drop target stays at the current overlay viewport's upper-right corner. */
object RecentsGesturePolicy {
    fun dropZone(width: Int, height: Int): WindowRect {
        require(width > 0 && height > 0)
        return if (width > height) {
            // A larger upper-right target while leaving a straight central overview swipe clear.
            WindowRect(width - width * 45 / 100, 0, width, height * 60 / 100)
        } else {
            WindowRect(width - width * 60 / 100, 0, width, height * 30 / 100)
        }
    }
}
