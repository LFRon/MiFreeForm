package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import java.util.concurrent.ConcurrentHashMap

/** Native events invalidate a cache; their old boolean snapshots never become presentation state.
 * The posted turn reads the current owner. Identity fencing also handles reuse of the same task id.
 */
internal class TaskVisibilityRefresh(
    private val post: (() -> Unit) -> Unit,
    private val readCurrent: (Int) -> Boolean?,
    private val emit: (Int, Boolean) -> Unit
) {
    private val pending = ConcurrentHashMap<Int, Any>()

    fun request(taskId: Int) {
        val token = Any()
        if (pending.putIfAbsent(taskId, token) != null) return
        post {
            if (pending.remove(taskId, token)) readCurrent(taskId)?.let { emit(taskId, it) }
        }
    }

    fun forget(taskId: Int) { pending.remove(taskId) }
}
