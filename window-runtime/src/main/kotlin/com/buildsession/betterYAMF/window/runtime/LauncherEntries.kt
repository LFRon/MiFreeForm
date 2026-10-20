package com.buildsession.betterYAMF.window.runtime

import com.buildsession.betterYAMF.window.api.*
import com.buildsession.betterYAMF.window.core.RecentsEntryController
import com.buildsession.betterYAMF.window.core.RecentsEntryEffect

object LauncherEntries {
    fun create(scheduler: RuntimeScheduler, port: LauncherEntryPort): LauncherEntryHandle =
        LauncherEntryRuntime(scheduler, port)
}

private class LauncherEntryRuntime(private val scheduler: RuntimeScheduler,
    private val port: LauncherEntryPort) : LauncherEntryHandle {
    private val core = RecentsEntryController()
    private var dispatching = false
    private var cancelling = false
    private var cancellationEpoch = 0L
    override fun targetBounds(width: Int, height: Int) = core.targetBounds(width, height)
    override fun onInput(input: EntryInput): EntryDisposition {
        if (!scheduler.isCurrentThread() || dispatching || cancelling) return EntryDisposition.PASS_THROUGH
        dispatching = true
        val epoch = cancellationEpoch
        var disposition = EntryDisposition.PASS_THROUGH
        try {
            // The candidate result supersedes the pre-query visual from this same input turn.
            var visual: EntryTargetFrame? = null
            for (effect in core.input(input)) {
                if (epoch != cancellationEpoch) return disposition
                when (effect) {
                    RecentsEntryEffect.QueryCandidate -> {
                        val candidate = port.candidate()
                        if (epoch == cancellationEpoch) visual = core.candidate(candidate)
                    }
                    is RecentsEntryEffect.Present -> if (visual == null) visual = effect.frame
                    is RecentsEntryEffect.Suppress -> port.suppressNativeGesture(effect.durationMs)
                    is RecentsEntryEffect.Drop -> {
                        val caps = port.capabilities
                        if (epoch != cancellationEpoch) return disposition
                        if (caps.retainedSource && port.beginRetained(effect.request)) {
                            // An armed native gate still needs original-UP completion, even if a
                            // port cancelled the candidate synchronously while arming that gate.
                            disposition = EntryDisposition.TAKEOVER_ARMED
                        } else if (epoch == cancellationEpoch) {
                            disposition = core.fallback(caps)
                            if (disposition == EntryDisposition.LEGACY_QUEUED) port.queueLegacy(effect.request)
                        }
                    }
                }
            }
            if (epoch == cancellationEpoch) visual?.let(port::present)
            return disposition
        } catch (error: Throwable) {
            core.reset()
            throw error
        } finally { dispatching = false }
    }
    override fun cancel() {
        fun cancelOnOwner() {
            cancellationEpoch++
            if (cancelling) return
            cancelling = true
            try { core.cancel()?.let(port::present) } finally { cancelling = false }
        }
        if (scheduler.isCurrentThread()) cancelOnOwner() else scheduler.post { cancelOnOwner() }
    }
}
