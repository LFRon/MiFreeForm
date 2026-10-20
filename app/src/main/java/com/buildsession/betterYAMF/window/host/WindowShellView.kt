package com.buildsession.betterYAMF.window.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import com.buildsession.betterYAMF.window.api.SceneStyle
import com.buildsession.betterYAMF.window.api.WindowSceneFrame
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
    private val imageMask = LinearGradient(0f, 0f, 0f, 1f,
        Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val imageMaskMatrix = Matrix()
    private val imageMaskPaint = Paint().apply { blendMode = BlendMode.DST_IN; shader = imageMask }
    private val bridgeBounds = RectF()
    private var applicationIcon: Drawable? = null

    fun setApplicationIcon(icon: Drawable?) { applicationIcon = icon?.mutate() }

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

    fun render(frame: WindowSceneFrame) {
        this.frame = frame
        drawEntryImage = frame.showEntryImage
        // SurfaceFrameSynchronizer schedules the draw after replacing its matching task frame.
    }

    /** Window-local input region; the transparent area outside the shell is never touchable. */
    fun touchRegion(): Region {
        val f = frame ?: return Region()
        if (f.alpha <= 0f) return Region()
        getLocationOnScreen(origin)
        val o = f.input.bounds?.rounded() ?: return Region()
        val result = Region(o.left, o.top, o.right, o.bottom)
        f.input.passThrough?.rounded()?.let { c ->
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
        f.fullscreen?.let { bridge ->
            val b = bridge.outline
            bridgeBounds.set(b.left, b.top, b.right, b.bottom)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = (bridge.outlineAlpha * f.alpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(bridgeBounds, bridge.outlineRadius, bridge.outlineRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = minOf(style.fullscreenOutlineWidth,
                bridgeBounds.width() / 2f, bridgeBounds.height() / 2f).coerceAtLeast(0f)
            // Canvas centers strokes on their path. Keep the whole border inside the scene
            // bounds so it retains its thickness when the preview reaches the display edges.
            val inset = paint.strokeWidth / 2f
            bridgeBounds.inset(inset, inset)
            val radius = (bridge.outlineRadius - inset).coerceAtLeast(0f)
            paint.color = 0xFF999999.toInt()
            paint.alpha = (bridge.outlineAlpha * f.alpha * style.fullscreenOutlineAlphaGain * 255f)
                .roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(bridgeBounds, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        val image = f.entryImage
        if (drawEntryImage && image != null) entryImage?.let { bitmap ->
            val alpha = (f.alpha * 255f).roundToInt().coerceIn(0, 255)
            // The opaque flight needs only clipping. Allocate a compositing layer only when
            // masking the replacement edge or applying group opacity.
            val layer = if (image.featherPx > 0f || alpha < 255) canvas.saveLayerAlpha(content, alpha)
                else canvas.save()
            imageClip.rewind()
            imageClip.addRoundRect(content, f.contentRadius, f.contentRadius, Path.Direction.CW)
            canvas.clipPath(imageClip)
            val clip = image.clip
            canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom)
            paint.alpha = 255
            val bounds = image.bounds
            imageBounds.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
            // CORE supplies one top-aligned cover projection and an opaque remaining region.
            // Only the narrow replacement edge can overlap layouts; never fade the whole image.
            paint.isFilterBitmap = true
            canvas.drawBitmap(bitmap, null, imageBounds, paint)
            if (image.featherPx > 0f) {
                val edgeTop = clip.bottom - image.featherPx
                imageMaskMatrix.setScale(1f, image.featherPx)
                imageMaskMatrix.postTranslate(0f, edgeTop)
                imageMask.setLocalMatrix(imageMaskMatrix)
                canvas.drawRect(clip.left, edgeTop, clip.right, clip.bottom, imageMaskPaint)
            }
            canvas.restoreToCount(layer)
        }
        for (i in 0..3) radii[i] = f.outerRadius
        for (i in 4..7) radii[i] = f.outerBottomRadius
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
        drawFullscreenBridge(canvas, f)
        paint.alpha = 255
        canvas.restoreToCount(save)
    }

    private fun drawFullscreenBridge(canvas: Canvas, frame: WindowSceneFrame) {
        val bridge = frame.fullscreen ?: return
        if (bridge.maskAlpha <= 0f) return
        val alpha = (frame.alpha * bridge.maskAlpha * 255f).roundToInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF2EFE6.toInt()
        paint.alpha = alpha
        canvas.drawRoundRect(outer, bridge.cornerRadius, bridge.cornerRadius, paint)
        val cx = outer.centerX()
        val cy = outer.centerY()
        val size = minOf(44f * style.density, outer.width() * 0.23f, outer.height() * 0.23f)
        val iconCy = cy - size * 0.35f
        bridgeBounds.set(cx - size / 2f, iconCy - size / 2f, cx + size / 2f, iconCy + size / 2f)
        paint.color = 0xFFFAF8F2.toInt()
        paint.alpha = alpha
        canvas.drawRoundRect(bridgeBounds, size * 0.28f, size * 0.28f, paint)
        paint.color = 0xFFDCD9D0.toInt()
        paint.alpha = alpha
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = style.density * 0.6f
        canvas.drawRoundRect(bridgeBounds, size * 0.28f, size * 0.28f, paint)
        applicationIcon?.let { icon ->
            val half = size * 0.30f
            icon.setBounds((cx - half).roundToInt(), (iconCy - half).roundToInt(),
                (cx + half).roundToInt(), (iconCy + half).roundToInt())
            icon.alpha = alpha
            icon.draw(canvas)
            icon.alpha = 255
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFF454545.toInt()
        paint.alpha = alpha
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = minOf(14f * style.density, size * 0.36f)
        canvas.drawText("全屏", cx, iconCy + size * 1.05f, paint)
    }
}
