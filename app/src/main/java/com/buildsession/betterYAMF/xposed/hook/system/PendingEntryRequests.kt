package com.buildsession.betterYAMF.xposed.hook.system

import com.buildsession.betterYAMF.window.api.WindowOperation

/** Native transport lifetime before a window exists. All calls use the system's serial owner.
 * Launcher handoff IDs are minted with elapsedRealtimeNanos; keep a per-peer/task watermark so
 * cancellation delivered before OPEN and duplicate broadcasts cannot resurrect that request.
 * This does not decide window modes, geometry, or animation.
 */
internal class PendingEntryRequests(private val failed: (Throwable) -> Unit) {
    data class Key(val packageName: String, val userId: Int, val epoch: Long, val taskId: Int, val handoffId: Long)
    private data class Address(val packageName: String, val userId: Int, val epoch: Long, val taskId: Int)
    class Ticket internal constructor(val key: Key, internal val retired: () -> Unit) {
        internal var operation: WindowOperation? = null
    }
    private val pending = mutableMapOf<Key, Ticket>()
    private val latest = mutableMapOf<Address, Long>()
    private fun Key.address() = Address(packageName, userId, epoch, taskId)

    fun begin(key: Key, retired: () -> Unit): Ticket? {
        if (key.handoffId <= (latest[key.address()] ?: Long.MIN_VALUE)) return null
        latest[key.address()] = key.handoffId
        val old = pending.values.filter { it.key.taskId == key.taskId }
        old.forEach { pending.remove(it.key) }
        val ticket = Ticket(key, retired)
        pending[key] = ticket
        old.forEach(::retire)
        return ticket
    }

    fun attach(ticket: Ticket, operation: WindowOperation) {
        if (pending[ticket.key] === ticket) ticket.operation = operation else operation.cancel()
    }

    fun take(ticket: Ticket): Boolean {
        if (pending[ticket.key] !== ticket) return false
        pending.remove(ticket.key)
        ticket.operation = null
        return true
    }

    fun cancel(key: Key) {
        latest[key.address()] = maxOf(latest[key.address()] ?: Long.MIN_VALUE, key.handoffId)
        pending.remove(key)?.let(::retire)
    }

    fun peerChanged(packageName: String, userId: Int, epoch: Long) {
        val obsolete = pending.values.filter { it.key.packageName == packageName && it.key.userId == userId && it.key.epoch != epoch }
        obsolete.forEach { pending.remove(it.key) }
        latest.keys.removeAll { it.packageName == packageName && it.userId == userId && it.epoch != epoch }
        obsolete.forEach(::retire)
    }

    private fun retire(ticket: Ticket) {
        val operation = ticket.operation
        ticket.operation = null
        runCatching { operation?.cancel() }.onFailure(failed)
        runCatching { ticket.retired() }.onFailure(failed)
    }
}
