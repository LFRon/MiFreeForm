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
        if (physicalWidth <= 0 || physicalHeight <= 0) return null
        val scale = minOf(
            physicalWidth.toFloat() / spoofedWidth,
            physicalHeight.toFloat() / spoofedHeight
        )
        val scaledWidth = spoofedWidth * scale
        val scaledHeight = spoofedHeight * scale
        return TaskSurfaceTransform(
            scaleX = scale,
            scaleY = scale,
            positionX = physicalLeft + (physicalWidth - scaledWidth) / 2f,
            positionY = physicalTop + (physicalHeight - scaledHeight) / 2f
        )
    }

    fun inputScale(surfaceScaleX: Float): Float =
        if (surfaceScaleX > 0f) 1.0f / surfaceScaleX else 1.0f
}
