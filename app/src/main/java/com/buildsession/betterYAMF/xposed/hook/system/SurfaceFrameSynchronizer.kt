package com.buildsession.betterYAMF.xposed.hook.system

import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.buildsession.betterYAMF.window.core.CommandStatus
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logError
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * LoS ViewRootImpl:13748 applies IMMEDIATELY when hardware rendering is unavailable. Such a
 * callback cannot acknowledge a View image. Wait for HWUI, then merge before display-list recording
 * (:6314); both pixels and task transforms are carried by the same BLAST frame.
 */
internal class SurfaceFrameSynchronizer(private val view: View, private val touchRegion: () -> Region) {
    private class Submission(
        val id: Long, val write: (SurfaceControl.Transaction) -> Boolean,
        val result: (CommandStatus) -> Unit
    ) {
        var timeout: Runnable? = null
        val draw = RenderedFrameCommit()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var pending: Submission? = null
    private val submitted = mutableMapOf<Long, Submission>()
    private var nextId = 0L
    private var disposed = false
    private var insetsListener: Any? = null
    private val drawApi by lazy { HardwareFrameObserver() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (pending != null) {
            // The initial traversal may precede renderer initialization. Let it draw, retaining
            // this submission for the following traversal; never acknowledge an empty image.
            if (hardwareReady()) pending?.also { pending = null }?.let(::submitOnDraw)
            else view.postInvalidateOnAnimation()
        }
        true
    }

    private fun hardwareReady(): Boolean = view.isHardwareAccelerated && runCatching {
        val root = XposedHelpers.callMethod(view, "getViewRootImpl") ?: return@runCatching false
        XposedHelpers.callMethod(root, "isHardwareEnabled") == true
    }.getOrDefault(false)

    fun attach(): Boolean = runCatching {
        drawApi // Select the HWUI completion interface once, before accepting scene submissions.
        val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val regionMode = XposedHelpers.getStaticIntField(infoClass, "TOUCHABLE_INSETS_REGION")
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "onComputeInternalInsets" -> {
                    val info = args!![0]
                    XposedHelpers.callMethod(info, "setTouchableInsets", regionMode)
                    (XposedHelpers.getObjectField(info, "touchableRegion") as Region).set(touchRegion())
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "MiWindowInsets"
                else -> null
            }
        }
        XposedHelpers.callMethod(view.viewTreeObserver, "addOnComputeInternalInsetsListener", listener)
        insetsListener = listener
        view.viewTreeObserver.addOnPreDrawListener(preDraw)
        true
    }.onFailure { log(TAG, "frame/input synchronization unavailable", it) }.getOrDefault(false)

    private fun complete(submission: Submission, status: CommandStatus) {
        val owned = if (pending === submission) { pending = null; true }
            else submitted.remove(submission.id) === submission
        if (!owned) return
        submission.timeout?.let(handler::removeCallbacks)
        submission.timeout = null
        submission.result(status)
    }

    fun present(write: (SurfaceControl.Transaction) -> Boolean, result: (CommandStatus) -> Unit) {
        if (disposed) { result(CommandStatus.SUPERSEDED); return }
        pending?.let { complete(it, CommandStatus.SUPERSEDED) }
        val submission = Submission(++nextId, write, result)
        pending = submission
        submission.timeout = Runnable {
            logError(TAG, "frame commit timeout id=${submission.id} hw=${view.isHardwareAccelerated} title=${view.layoutParams}")
            complete(submission, CommandStatus.FAILED)
        }.also { handler.postDelayed(it, 5000L) } // BLASTSyncEngine failure bound, never an animation delay.
        view.invalidate()
    }

    private fun submitOnDraw(submission: Submission) {
        val tx = SurfaceControl.Transaction()
        submitted[submission.id] = submission
        runCatching {
            check(submission.write(tx)) { "Task viewport is unavailable" }
            val root = XposedHelpers.callMethod(view, "getViewRootImpl") ?: error("ViewRoot is unavailable")
            check(XposedHelpers.callMethod(root, "isHardwareEnabled") == true) { "HWUI frame is unavailable" }
            drawApi.observe(view) { produced -> handler.post {
                submission.draw.drawn(produced)?.let { complete(submission, it) }
            } }
            tx.addTransactionCommittedListener(executor) {
                submission.draw.committed()?.let { complete(submission, it) }
            }
            val accepted = XposedHelpers.callMethod(root, "applyTransactionOnDraw",
                arrayOf(SurfaceControl.Transaction::class.java), tx)
            check(accepted != false) { "ViewRoot rejected the frame transaction" }
        }.onFailure {
            log(TAG, "synchronized frame failed", it)
            // Never detach a failed window from inside ViewRoot traversal.
            handler.post { complete(submission, CommandStatus.FAILED) }
        }
        tx.close() // ViewRootImpl copies/merges the caller-owned transaction.
    }

    fun dispose() {
        disposed = true
        pending?.let { complete(it, CommandStatus.SUPERSEDED) }
        submitted.values.toList().forEach { complete(it, CommandStatus.SUPERSEDED) }
        if (view.viewTreeObserver.isAlive) {
            view.viewTreeObserver.removeOnPreDrawListener(preDraw)
            insetsListener?.let {
                runCatching { XposedHelpers.callMethod(view.viewTreeObserver, "removeOnComputeInternalInsetsListener", it) }
            }
        }
        insetsListener = null
    }

    companion object {
        private const val TAG = "reYAMF_FrameSync"

        /** LoS ViewRootImpl:2183 requires an explicit per-window hint in system_server. */
        fun configureOverlay(params: WindowManager.LayoutParams): Boolean = runCatching {
            val cls = WindowManager.LayoutParams::class.java
            val trusted = XposedHelpers.getStaticIntField(cls, "PRIVATE_FLAG_TRUSTED_OVERLAY")
            var privateFlags = XposedHelpers.getIntField(params, "privateFlags") or trusted
            val force = XposedHelpers.findFieldIfExists(cls, "RENDERING_HINT_FORCE_HARDWARE_ACCELERATED")
            if (force != null) {
                val hints = XposedHelpers.getIntField(params, "renderingHints")
                XposedHelpers.setIntField(params, "renderingHints", hints or force.getInt(null))
            } else {
                privateFlags = privateFlags or XposedHelpers.getStaticIntField(cls, "PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED")
            }
            XposedHelpers.setIntField(params, "privateFlags", privateFlags)
            XposedHelpers.callMethod(params, "setFitInsetsTypes", 0)
            true
        }.onFailure { log(TAG, "trusted hardware overlay capability unavailable", it) }.getOrDefault(false)
    }

}
