package com.buildsession.betterYAMF.xposed.hook.organizer

/** Platform-independent arithmetic for the adapter's uniform-scale / cover-crop contract. */
internal data class ViewportTransform(
    val scale: Float,
    val x: Float,
    val y: Float,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
    val cornerRadius: Float
) {
    companion object {
        fun compute(
            logicalWidth: Int, logicalHeight: Int,
            left: Int, top: Int, width: Int, height: Int, visualCornerRadius: Float
        ): ViewportTransform? {
            if (logicalWidth <= 0 || logicalHeight <= 0 || width <= 0 || height <= 0) return null
            val scale = maxOf(width.toFloat() / logicalWidth, height.toFloat() / logicalHeight)
            val cropW = (width / scale).coerceIn(0f, logicalWidth.toFloat())
            val cropH = (height / scale).coerceIn(0f, logicalHeight.toFloat())
            val cropLeft = (logicalWidth - cropW) / 2f
            val cropTop = (logicalHeight - cropH) / 2f
            return ViewportTransform(
                scale,
                left - cropLeft * scale + (width - cropW * scale) / 2f,
                top - cropTop * scale + (height - cropH * scale) / 2f,
                cropLeft, cropTop, cropW, cropH, visualCornerRadius.coerceAtLeast(0f) / scale
            )
        }
    }
}
