package com.buildsession.betterYAMF.xposed.hook.freeform

data class TaskSurfaceTransform(
    val scaleX: Float,
    val scaleY: Float,
    val positionX: Float,
    val positionY: Float
)

object FreeformSurfaceMath {
    fun compute(
        physicalLeft: Int,
        physicalTop: Int,
        physicalWidth: Int,
        physicalHeight: Int,
        spoofedWidth: Int,
        spoofedHeight: Int
    ): TaskSurfaceTransform? {
        if (spoofedWidth <= 0 || spoofedHeight <= 0) return null
        return TaskSurfaceTransform(
            scaleX = physicalWidth.toFloat() / spoofedWidth,
            scaleY = physicalHeight.toFloat() / spoofedHeight,
            positionX = physicalLeft.toFloat(),
            positionY = physicalTop.toFloat()
        )
    }

    fun inputScale(surfaceScaleX: Float): Float = 1.0f / surfaceScaleX
}
