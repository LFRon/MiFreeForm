package com.buildsession.betterYAMF.window.host

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.buildsession.betterYAMF.window.api.ContentAlignment
import com.buildsession.betterYAMF.window.api.EntryImageVisual
import com.buildsession.betterYAMF.window.api.SceneRect
import com.buildsession.betterYAMF.window.api.SceneMetrics
import com.buildsession.betterYAMF.window.api.SceneStyle
import com.buildsession.betterYAMF.window.api.WindowSceneFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise the real shell compositor on Canvas and HWUI. All targets are offscreen; no task,
 * Activity, input surface or display setting is touched, including when the phone is asleep.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class WindowShellViewRenderingTest {
    private val viewport = SceneRect(8f, 10f, 80f, 80f)

    private fun render(source: Bitmap, remaining: Float, hardware: Boolean): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val node = if (hardware) RenderNode("MiWindow offscreen entry fixture").apply {
            setPosition(0, 0, 112, 112)
        } else null
        val output = if (hardware) null else Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                val view = WindowShellView(instrumentation.targetContext, SceneStyle(1f, 8f, 8f))
                view.layout(0, 0, 112, 112)
                view.retainEntryImage(source)
                view.render(WindowSceneFrame(viewport, viewport, 1f, 1f, 0f, 0f,
                    0f, false, true, showEntryImage = true,
                    entryImage = EntryImageVisual(viewport.coverTop(source.width.toFloat() / source.height),
                        viewport.copy(height = viewport.height * remaining),
                        if (remaining in 0.01f..0.99f) 2f else 0f),
                    contentAlignment = ContentAlignment.TOP, coordinateSpace = SceneMetrics(112, 112, 0, 0)))
                val canvas = node?.beginRecording() ?: Canvas(output!!)
                try {
                    canvas.drawColor(Color.GREEN) // Represents the opaque live task underneath.
                    view.draw(canvas)
                } finally {
                    node?.endRecording()
                    view.releaseEntryImage()
                }
            }
            if (node == null) return checkNotNull(output)
            HardwareBuffer.create(112, 112, HardwareBuffer.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { buffer ->
                HardwareBufferRenderer(buffer).use { renderer ->
                    renderer.setContentRoot(node)
                    val completed = CountDownLatch(1)
                    val result = AtomicReference<HardwareBufferRenderer.RenderResult>()
                    renderer.obtainRenderRequest().draw({ it.run() }) { rendered ->
                        result.set(rendered)
                        completed.countDown()
                    }
                    assertTrue("Offscreen HWUI must finish", completed.await(5, TimeUnit.SECONDS))
                    val rendered = checkNotNull(result.get())
                    rendered.fence.use { fence ->
                        assertEquals(HardwareBufferRenderer.RenderResult.SUCCESS, rendered.status)
                        assertTrue("HWUI pixels must be ready", !fence.isValid || fence.await(Duration.ofSeconds(5)))
                    }
                    val pixels = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, null))
                    return try { checkNotNull(pixels.copy(Bitmap.Config.ARGB_8888, false)) }
                        finally { pixels.recycle() }
                }
            }
        } finally { node?.discardDisplayList() }
    }

    @Test fun portraitAndLandscapeSourcesFillTheViewportAndKeepTheirHeaderOnCanvasAndHwui() {
        for (hardware in listOf(false, true)) {
            for ((width, height) in listOf(64 to 128, 128 to 64)) {
                val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    source.eraseColor(Color.RED)
                    Canvas(source).drawRect(0f, 0f, width.toFloat(), 12f,
                        android.graphics.Paint().apply { color = Color.BLUE })
                    val pixels = render(source, 1f, hardware)
                    try {
                        assertEquals("Header must stay at the top", Color.BLUE, pixels.getPixel(48, 13))
                        assertEquals("Left pillar must not appear", Color.RED, pixels.getPixel(9, 80))
                        assertEquals("Right pillar must not appear", Color.RED, pixels.getPixel(86, 80))
                        assertEquals("Source stays inside the common crop", Color.GREEN, pixels.getPixel(4, 50))
                    } finally { pixels.recycle() }
                } finally { source.recycle() }
            }
        }
    }

    @Test fun replacementMixesLayoutsOnlyInsideItsTwoPixelEdgeOnCanvasAndHwui() {
        val source = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            for (hardware in listOf(false, true)) {
                val pixels = render(source, 0.5f, hardware)
                try {
                    for (y in 11 until 48) assertEquals("Retained content must stay opaque at y=$y", Color.RED, pixels.getPixel(48, y))
                    for (y in 50 until 89) assertEquals("Replaced content must stay opaque at y=$y", Color.GREEN, pixels.getPixel(48, y))
                } finally { pixels.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun theInitialSourceHasNoPrematureTransparencyAtItsBottomEdge() {
        val source = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            for (hardware in listOf(false, true)) {
                val pixels = render(source, 1f, hardware)
                try { assertEquals(Color.RED, pixels.getPixel(48, 89)) }
                finally { pixels.recycle() }
            }
        } finally { source.recycle() }
    }
}
