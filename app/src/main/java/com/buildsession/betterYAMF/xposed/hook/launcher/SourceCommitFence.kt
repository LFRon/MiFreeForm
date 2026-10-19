package com.buildsession.betterYAMF.xposed.hook.launcher

/** Facts about one native source's last transform, not a window mode or animation clock. */
internal class SourceCommitFence {
    class Ticket internal constructor()
    private var latest: Ticket? = null
    private var committed: Ticket? = null
    private var closed = false
    private var delivered = false
    var frozen = false
        private set

    fun submitted(): Ticket {
        check(!closed && !frozen)
        return Ticket().also { latest = it }
    }

    fun freeze(): Boolean {
        if (closed || latest == null) return false
        frozen = true
        return true
    }

    fun committed(ticket: Ticket) {
        if (!closed && ticket === latest) committed = ticket
    }

    fun takeReady(): Boolean {
        if (closed || delivered || !frozen || latest == null || committed !== latest) return false
        delivered = true
        return true
    }

    fun close() {
        closed = true
        frozen = false
        latest = null
        committed = null
    }
}
