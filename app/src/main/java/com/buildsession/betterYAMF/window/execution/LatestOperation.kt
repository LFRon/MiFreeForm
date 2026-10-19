package com.buildsession.betterYAMF.window.execution

/** Serial executor ownership, independent of product state and platform resource types. */
internal class LatestOperation<T>(private val cancelResource: (T) -> Unit) {
    internal class Ticket<T> {
        internal enum class State { PENDING, COMPLETED, CANCELLED }
        internal var state = State.PENDING
        internal var resource: T? = null
    }

    private var active: Ticket<T>? = null

    fun begin(): Ticket<T> {
        val previous = active
        val next = Ticket<T>()
        active = next
        cancel(previous)
        return next
    }

    /** Completion may have happened synchronously, before the API returned its resource. */
    fun attach(ticket: Ticket<T>, resource: T) {
        when (ticket.state) {
            Ticket.State.PENDING -> ticket.resource = resource
            Ticket.State.CANCELLED -> cancelResource(resource)
            Ticket.State.COMPLETED -> Unit
        }
    }

    fun complete(ticket: Ticket<T>): Boolean {
        if (active !== ticket || ticket.state != Ticket.State.PENDING) return false
        active = null
        ticket.state = Ticket.State.COMPLETED
        ticket.resource = null
        return true
    }

    fun cancel() {
        val previous = active
        active = null
        cancel(previous)
    }

    private fun cancel(ticket: Ticket<T>?) {
        if (ticket == null || ticket.state != Ticket.State.PENDING) return
        ticket.state = Ticket.State.CANCELLED
        val resource = ticket.resource
        ticket.resource = null
        resource?.let(cancelResource)
    }
}
