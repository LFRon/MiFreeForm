package com.buildsession.betterYAMF.window.host

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.buildsession.betterYAMF.window.api.RuntimeScheduler
import com.buildsession.betterYAMF.window.api.WindowOperation

/** Infrastructure adapter. Frame nanoseconds and MotionEvent time share the uptime clock. */
internal class AndroidRuntimeScheduler(private val handler: Handler) : RuntimeScheduler {
    override fun isCurrentThread() = Looper.myLooper() == handler.looper
    override fun post(work: () -> Unit) { handler.post { work() } }
    override fun after(delayMs: Long, work: () -> Unit): WindowOperation {
        val run = Runnable { work() }
        handler.postDelayed(run, delayMs)
        return WindowOperation { handler.removeCallbacks(run) }
    }
    override fun nextFrame(work: (Long) -> Unit): WindowOperation {
        val clock = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { work(it / 1_000_000L) }
        clock.postFrameCallback(callback)
        return WindowOperation { clock.removeFrameCallback(callback) }
    }
}
