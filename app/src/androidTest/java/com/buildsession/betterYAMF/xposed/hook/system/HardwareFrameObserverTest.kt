package com.buildsession.betterYAMF.xposed.hook.system

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buildsession.betterYAMF.window.api.CommandStatus
import com.buildsession.betterYAMF.xposed.hook.launcher.SourceCommitFence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class HardwareFrameObserverTest {
    /** An unattached windowless hierarchy exercises real HWUI without appearing on the display
     * or receiving input. No Activity, permission change, launcher gesture or app task is used.
     */
    @Test fun actualHwuiProductionAndMergedTransactionReachDestinationAndSourceFences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val draws = AtomicInteger()
        val traversals = AtomicInteger()
        val produced = AtomicReference<Boolean?>()
        val committed = AtomicBoolean()
        var host: SurfaceControlViewHost? = null
        try {
          instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            host = SurfaceControlViewHost(context, display, null as IBinder?)
            val observer = HardwareFrameObserver()
            val fence = RenderedFrameCommit()
            val view = object : View(context) {
                override fun onDraw(canvas: Canvas) { draws.incrementAndGet(); canvas.drawColor(Color.BLUE) }
            }
            val getRoot = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            listener = ViewTreeObserver.OnPreDrawListener {
                val root = getRoot.invoke(view)
                traversals.incrementAndGet()
                val enabled = root.javaClass.getMethod("isHardwareEnabled").invoke(root) == true
                if (!enabled) view.postInvalidateOnAnimation()
                else {
                    view.viewTreeObserver.removeOnPreDrawListener(listener)
                    fun result(status: CommandStatus?) {
                        if (status == null) return
                        if (status != CommandStatus.DONE) failure.set(AssertionError("HWUI did not produce the fixture buffer: $status"))
                        done.countDown()
                    }
                    try {
                        // LoS ViewRootImpl:5942 skips screen-off draws unless one is explicitly
                        // reported. Force only this hidden fixture's draw, leaving the phone asleep.
                        root.javaClass.getDeclaredMethod("reportNextDraw", String::class.java)
                            .apply { isAccessible = true }.invoke(root, "MiWindow hidden draw fixture")
                        observer.observe(view) { buffer -> view.post { produced.set(buffer); result(fence.drawn(buffer)) } }
                        SurfaceControl.Transaction().use { tx ->
                            // The source adapter observes the borrowed native transaction before
                            // ViewRoot merges it. Its listener must survive that native merge.
                            val source = SourceCommitFence()
                            val ticket = source.submitted()
                            tx.addTransactionCommittedListener({ view.post(it) }) {
                                source.committed(ticket)
                                if (!source.takeReady() || source.takeReady()) {
                                    failure.set(AssertionError("Merged native source must acknowledge exactly once"))
                                }
                                source.close()
                                done.countDown()
                            }
                            assertTrue(source.freeze())
                            assertFalse("A queued transform is not a committed source", source.takeReady())
                            tx.addTransactionCommittedListener({ view.post(it) }) { committed.set(true); result(fence.committed()) }
                            root.javaClass.getMethod("applyTransactionOnDraw", SurfaceControl.Transaction::class.java).invoke(root, tx)
                        }
                    } catch (error: Throwable) { failure.set(error); done.countDown() }
                }
                true
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            val attrs = WindowManager.LayoutParams(32, 32, WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT)
            SurfaceControlViewHost::class.java.getMethod("setView", View::class.java, WindowManager.LayoutParams::class.java)
                .invoke(host, view, attrs)
          }
            val completed = done.await(10, TimeUnit.SECONDS)
            assertTrue("Hidden HWUI fixture must draw and commit: traversals=$traversals draws=$draws produced=$produced committed=$committed", completed)
            failure.get()?.let { throw AssertionError("Real HWUI callback failed", it) }
        } finally { instrumentation.runOnMainSync { host?.release() } }
    }
}
