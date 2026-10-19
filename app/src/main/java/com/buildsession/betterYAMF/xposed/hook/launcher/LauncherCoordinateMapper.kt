package com.buildsession.betterYAMF.xposed.hook.launcher

/** Native display-coordinate normalization. Product drop-zone policy stays in CORE. */
internal object LauncherCoordinateMapper {
    data class Point(val x: Float, val y: Float)

    /** LoS MotionEvent.createRotateMatrix:4440. Output dimensions belong to [toRotation]. */
    fun rotatePoint(x: Float, y: Float, fromRotation: Int, toRotation: Int,
        width: Int, height: Int): Point {
        require(fromRotation in 0..3 && toRotation in 0..3 && width > 0 && height > 0)
        return when ((toRotation - fromRotation + 4) % 4) {
            1 -> Point(y, height - x)
            2 -> Point(width - x, height - y)
            3 -> Point(width - y, x)
            else -> Point(x, y)
        }
    }
}
