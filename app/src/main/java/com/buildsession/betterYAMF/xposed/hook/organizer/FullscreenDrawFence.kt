package com.buildsession.betterYAMF.xposed.hook.organizer

/** Identity/sequence fence for the platform's fresh draw. Access is serialized by the WM lock. */
internal class FullscreenDrawFence {
    class Ticket internal constructor(val window: Any, val surface: Any, val clientSurface: Any, val minimumSequence: Int)
    private var current: Ticket? = null
    private var pendingBuffer = false
    var committed = false
        private set

    fun matches(window: Any, surface: Any, clientSurface: Any = surface): Boolean = current?.let {
        it.window === window && it.surface === surface && it.clientSurface === clientSurface
    } == true

    fun arm(window: Any, surface: Any, minimumSequence: Int, clientSurface: Any = surface) {
        current = Ticket(window, surface, clientSurface, minimumSequence)
        pendingBuffer = false
        committed = false
    }

    fun buffer(window: Any, surface: Any, sequence: Int, hasBuffer: Boolean, clientSurface: Any = surface): Ticket? {
        val ticket = current ?: return null
        if (!hasBuffer || pendingBuffer || committed || !matches(window, surface, clientSurface) || sequence < ticket.minimumSequence) return null
        pendingBuffer = true
        return ticket
    }

    fun commit(ticket: Ticket): Boolean {
        if (current !== ticket || !pendingBuffer) return false
        committed = true
        return true
    }

    fun invalidate() {
        current = null
        pendingBuffer = false
        committed = false
    }
}
