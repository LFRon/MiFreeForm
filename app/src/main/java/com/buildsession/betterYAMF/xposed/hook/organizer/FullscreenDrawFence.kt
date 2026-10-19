package com.buildsession.betterYAMF.xposed.hook.organizer

/** Identity/sequence fence for the platform's client redraw acknowledgement. Access is serialized by the WM lock. */
internal class FullscreenDrawFence {
    class Ticket internal constructor(val window: Any, val surface: Any, val clientSurface: Any, val minimumSequence: Int)
    private var current: Ticket? = null
    private var pendingDraw = false
    var committed = false
        private set
    val awaitingDraw: Boolean get() = current != null && !pendingDraw && !committed

    fun matches(window: Any, surface: Any, clientSurface: Any = surface): Boolean = current?.let {
        it.window === window && it.surface === surface && it.clientSurface === clientSurface
    } == true

    fun arm(window: Any, surface: Any, minimumSequence: Int, clientSurface: Any = surface) {
        current = Ticket(window, surface, clientSurface, minimumSequence)
        pendingDraw = false
        committed = false
    }

    fun drawing(window: Any, surface: Any, sequence: Int, hasTransaction: Boolean, clientSurface: Any = surface): Ticket? {
        val ticket = current ?: return null
        if (!hasTransaction || pendingDraw || committed || !matches(window, surface, clientSurface) || sequence < ticket.minimumSequence) return null
        pendingDraw = true
        return ticket
    }

    fun commit(ticket: Ticket): Boolean {
        if (current !== ticket || !pendingDraw) return false
        committed = true
        return true
    }

    fun invalidate() {
        current = null
        pendingDraw = false
        committed = false
    }
}
