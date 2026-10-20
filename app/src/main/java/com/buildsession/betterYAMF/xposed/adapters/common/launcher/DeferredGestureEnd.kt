package com.buildsession.betterYAMF.xposed.adapters.common.launcher

/** One captured native UP continuation. A failed bridge resumes it; native takeover discards it. */
internal class DeferredGestureEnd {
    private var closed = false
    private var continuation: (() -> Unit)? = null

    fun defer(run: () -> Unit): Boolean {
        if (closed) return false
        if (continuation == null) continuation = run
        return true
    }

    fun finish(resume: Boolean) {
        if (closed) return
        closed = true
        val run = continuation
        continuation = null
        if (resume) run?.invoke()
    }
}
