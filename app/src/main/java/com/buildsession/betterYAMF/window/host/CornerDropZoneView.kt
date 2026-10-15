package com.buildsession.betterYAMF.window.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.View
import com.buildsession.betterYAMF.common.ui.dpToPx
import com.buildsession.betterYAMF.window.core.DropKind

internal class CornerDropZoneView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun getRealScreenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        context.display?.getRealMetrics(dm)
        return dm
    }

    private val radius: Float
        get() {
            val dm = getRealScreenMetrics()
            return if (dm.widthPixels > dm.heightPixels) {
                dm.heightPixels * 0.20f
            } else {
                dm.widthPixels * 0.20f
            }
        }

    private var currentCorner = -1 // -1: none, 0: TL, 1: TR, 2: BL, 3: BR
    private var isInsideActiveZone = false

    init {
        paint.style = Paint.Style.FILL
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = 2f.dpToPx()
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(10f.dpToPx(), 10f.dpToPx()), 0f)
    }

    fun render(hover: DropKind?, active: Boolean) {
        currentCorner = when (hover) {
            DropKind.CORNER_TL -> 0
            DropKind.CORNER_TR -> 1
            DropKind.CORNER_BL -> 2
            DropKind.CORNER_BR -> 3
            DropKind.SIDE_LEFT -> 4
            DropKind.SIDE_RIGHT -> 5
            null -> -1
        }
        isInsideActiveZone = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val dm = getRealScreenMetrics()
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        if (w == 0f || h == 0f || currentCorner == -1) return

        when (currentCorner) {
            0 -> drawCorner(canvas, 0f, 0f, 0f, 90f) // TL
            1 -> drawCorner(canvas, w, 0f, 90f, 90f) // TR
            2 -> drawCorner(canvas, 0f, h, 270f, 90f) // BL
            3 -> drawCorner(canvas, w, h, 180f, 90f) // BR
            4 -> drawSide(canvas, 0f, h * 0.1f, w * 0.05f, h * 0.9f) // Left
            5 -> drawSide(canvas, w * 0.95f, h * 0.1f, w, h * 0.9f) // Right
        }
    }

    private fun drawCorner(canvas: Canvas, cx: Float, cy: Float, startAngle: Float, sweepAngle: Float) {
        val color = if (isInsideActiveZone) Color.parseColor("#CC61D4FF") else Color.parseColor("#40FFFFFF")
        val strokeColor = if (isInsideActiveZone) Color.parseColor("#FF61D4FF") else Color.WHITE

        paint.color = color
        dashPaint.color = strokeColor

        val rectF = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, true, paint)
        canvas.drawArc(rectF, startAngle, sweepAngle, true, dashPaint)
    }

    private fun drawSide(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val color = if (isInsideActiveZone) Color.parseColor("#CC61D4FF") else Color.parseColor("#40FFFFFF")
        val strokeColor = if (isInsideActiveZone) Color.parseColor("#FF61D4FF") else Color.WHITE

        paint.color = color
        dashPaint.color = strokeColor

        val rectF = RectF(left, top, right, bottom)
        val rx = 10f.dpToPx()
        canvas.drawRoundRect(rectF, rx, rx, paint)
        canvas.drawRoundRect(rectF, rx, rx, dashPaint)
    }
}
