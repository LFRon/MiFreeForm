package com.buildsession.betterYAMF.xposed.hook.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.withSave

/** Preserve the existing target appearance, excluding the live card from both fill and text.
 * Sampling in onDraw observes the simulator after native MOVE; View invalidations coalesce it
 * to the display clock instead of allocating a Drawable and posting a runnable per input sample.
 */
internal class RecentsDropZoneView(context: Context) : View(context) {
    var sourcePose: (() -> Pair<RectF, Float>?)? = null
    private var active = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val excluded = Path()
    private val rect = RectF()
    private val origin = IntArray(2)
    // Dimensions are the existing HookLauncher target's pixel values; this is a layering fix.
    private val dash = DashPathEffect(floatArrayOf(20f, 15f), 0f)

    fun setActive(value: Boolean) {
        active = value
        alpha = if (value) 1f else 0.8f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withSave {
            sourcePose?.invoke()?.let { (bounds, radius) ->
                getLocationOnScreen(origin)
                bounds.offset(-origin[0].toFloat(), -origin[1].toFloat())
                excluded.rewind()
                excluded.addRoundRect(bounds, radius, radius, Path.Direction.CW)
                clipOutPath(excluded)
            }
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.style = Paint.Style.FILL
            paint.color = if (active) 0x8061D4FF.toInt() else 0x30FFFFFF
            paint.pathEffect = null
            drawRoundRect(rect, 40f, 40f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            paint.pathEffect = dash
            drawRoundRect(rect, 40f, 40f, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 16f * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.CENTER
            val baseline = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            drawText("拖到此处以小窗打开", width / 2f, baseline, paint)
        }
    }
}
