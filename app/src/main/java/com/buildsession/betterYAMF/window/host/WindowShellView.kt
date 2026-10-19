package com.buildsession.betterYAMF.window.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave
import com.buildsession.betterYAMF.window.core.SceneStyle
import com.buildsession.betterYAMF.window.core.WindowSceneFrame
import kotlin.math.roundToInt

/** Stable transparent surface. No animated WindowManager layout and no decoration snapshots. */
internal class WindowShellView(context: Context, private val style: SceneStyle) : View(context) {
    var frame: WindowSceneFrame? = null
        private set
    var onPointer: ((MotionEvent) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val outer = RectF()
    private val content = RectF()
    private val origin = IntArray(2)
    private val radii = FloatArray(8)
    private var entryImage: Bitmap? = null
    private var drawEntryImage = false
    private val imageClip = Path()
    private val imageBounds = RectF()
    val hasEntryImage: Boolean get() = entryImage != null

    fun retainEntryImage(bitmap: Bitmap) {
        entryImage = bitmap
        drawEntryImage = true
    }

    fun releaseEntryImage() {
        entryImage = null
        drawEntryImage = false
        // Hardware bitmap references recorded in an older display list remain owned by HWUI.
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    fun render(frame: WindowSceneFrame, showEntryImage: Boolean = hasEntryImage) {
        this.frame = frame
        drawEntryImage = showEntryImage
        // SurfaceFrameSynchronizer schedules the draw after replacing its matching task frame.
    }

    /** Window-local input region; the transparent area outside the shell is never touchable. */
    fun touchRegion(): Region {
        val f = frame ?: return Region()
        if (f.alpha <= 0f) return Region()
        getLocationOnScreen(origin)
        val o = f.outer.rounded()
        val result = Region(o.left, o.top, o.right, o.bottom)
        if (f.acceptsContent) {
            val c = f.content.rounded()
            result.op(c.left, c.top, c.right, c.bottom, Region.Op.DIFFERENCE)
        }
        result.translate(-origin[0], -origin[1])
        return result
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onPointer?.invoke(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        if (f.alpha <= 0f) return
        getLocationOnScreen(origin)
        val save = canvas.save()
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        outer.set(f.outer.left, f.outer.top, f.outer.right, f.outer.bottom)
        content.set(f.content.left, f.content.top, f.content.right, f.content.bottom)
        if (drawEntryImage) entryImage?.let { bitmap ->
            canvas.withSave {
                imageClip.rewind()
                imageClip.addRoundRect(content, f.contentRadius, f.contentRadius, Path.Direction.CW)
                canvas.clipPath(imageClip)
                val scale = maxOf(content.width() / bitmap.width, content.height() / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                imageBounds.set(content.centerX() - w / 2f, content.centerY() - h / 2f,
                    content.centerX() + w / 2f, content.centerY() + h / 2f)
                paint.alpha = (f.alpha * 255f).roundToInt().coerceIn(0, 255)
                paint.isFilterBitmap = true
                canvas.drawBitmap(bitmap, null, imageBounds, paint)
            }
        }
        for (i in 0..3) radii[i] = f.outerRadius
        for (i in 4..7) radii[i] = f.outerBottomRadius
        if (f.fullscreenHint) {
            ring.rewind()
            ring.fillType = Path.FillType.EVEN_ODD
            ring.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            ring.addRoundRect(outer, radii, Path.Direction.CW)
            paint.color = 0x8C000000.toInt()
            canvas.drawPath(ring, paint)
        }
        ring.rewind()
        ring.fillType = Path.FillType.EVEN_ODD
        ring.addRoundRect(outer, radii, Path.Direction.CW)
        ring.addRoundRect(content, f.contentRadius, f.contentRadius, Path.Direction.CW)
        fun channel(start: Int) = (start + (224f - start) * f.miniFraction).roundToInt()
        paint.color = Color.rgb(channel(Color.red(f.controllerColor)), channel(Color.green(f.controllerColor)), channel(Color.blue(f.controllerColor)))
        paint.alpha = (f.alpha * 255f).roundToInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        canvas.drawPath(ring, paint)

        // Controls morph with the same scene frame as the task and ring, including their alpha.
        paint.color = f.controlsColor
        paint.alpha = (f.controlsAlpha * 255f).roundToInt().coerceIn(0, 255)
        val topHeight = f.content.top - f.outer.top
        val dotRadius = minOf(style.density, topHeight / 6f)
        val centerX = f.outer.left + f.outer.width / 2f
        val centerY = (f.outer.top + f.content.top) / 2f
        for (index in -1..1) canvas.drawCircle(centerX + index * dotRadius * 4f, centerY, dotRadius, paint)
        if (f.portrait) {
            val handleWidth = minOf(style.handleWidth, f.content.width * 0.6f)
            val thickness = minOf(style.handleThickness, (f.outer.bottom - f.content.bottom) / 3f)
            val cy = (f.content.bottom + f.outer.bottom) / 2f
            canvas.drawRoundRect(centerX - handleWidth / 2f, cy - thickness / 2f, centerX + handleWidth / 2f, cy + thickness / 2f, thickness / 2f, thickness / 2f, paint)
        } else {
            val cx = (f.content.right + f.outer.right) / 2f
            val cy = (f.content.top + f.content.bottom) / 2f
            val halfHeight = minOf(50f * style.density, f.content.height / 3f)
            val halfWidth = style.handleThickness / 2f
            canvas.drawRoundRect(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight, halfWidth, halfWidth, paint)
        }
        if (f.fullscreenHint) {
            paint.textSize = 16f * style.density
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.WHITE
            canvas.drawText("松开以全屏显示", centerX, f.content.top + f.content.height / 2f, paint)
        }
        paint.alpha = 255
        canvas.restoreToCount(save)
    }
}
