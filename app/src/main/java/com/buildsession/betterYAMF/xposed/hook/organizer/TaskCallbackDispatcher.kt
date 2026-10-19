package com.buildsession.betterYAMF.xposed.hook.organizer

import java.util.concurrent.ConcurrentHashMap

/** Native callbacks leave WM's stack before entering the host's serial event loop. */
internal class TaskCallbackDispatcher(private val post: (() -> Unit) -> Unit) {
    private val owners = ConcurrentHashMap<Int, Any>()

    fun begin(taskId: Int) { owners[taskId] = Any() }

    fun forget(taskId: Int) { owners.remove(taskId) }

    fun dispatch(taskId: Int, event: () -> Unit) {
        val owner = owners[taskId] ?: return
        post {
            if (owners[taskId] === owner) event()
        }
    }
}
