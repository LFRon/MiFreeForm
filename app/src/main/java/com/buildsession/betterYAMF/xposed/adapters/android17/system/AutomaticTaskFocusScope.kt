package com.buildsession.betterYAMF.xposed.adapters.android17.system

/** Native call-stack correlation only, confined to AD. Never a gesture timer or global flag.
 * Each focusTopTask call can redirect its first native selection on the same service/thread;
 * subsequent lifecycle callbacks and direct task focus must keep their original semantics.
 */
internal class AutomaticTaskFocusScope {
    class Request internal constructor(val service: Any, val displayId: Int,
        internal val previous: Request?) {
        internal var consumed = false
    }

    private val current = ThreadLocal<Request>()

    fun enter(service: Any, displayId: Int): Request =
        Request(service, displayId, current.get()).also(current::set)

    fun consume(service: Any): Request? {
        val request = current.get() ?: return null
        if (request.service !== service || request.consumed) return null
        request.consumed = true
        return request
    }

    fun leave(request: Request) {
        check(current.get() === request) { "Mismatched native focus call" }
        if (request.previous == null) current.remove() else current.set(request.previous)
    }
}
