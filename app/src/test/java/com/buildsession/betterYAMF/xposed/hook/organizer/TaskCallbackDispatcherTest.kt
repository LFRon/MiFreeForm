package com.buildsession.betterYAMF.xposed.hook.organizer

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TaskCallbackDispatcherTest {
    @Test fun nativeCallbacksAreDeferredAndRemainOrdered() {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val dispatcher = TaskCallbackDispatcher { queue.add(it) }
        dispatcher.begin(30)
        dispatcher.dispatch(30) { events += "adopted" }
        dispatcher.dispatch(30) { events += "invalidated" }
        assertTrue(events.isEmpty())
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf("adopted", "invalidated"), events)
    }

    @Test fun oldNativeEventsCannotReachAReplacementSessionUsingTheSameTaskId() {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val dispatcher = TaskCallbackDispatcher { queue.add(it) }
        dispatcher.begin(30)
        dispatcher.dispatch(30) { events += "old vanished" }
        dispatcher.forget(30)
        dispatcher.begin(30)
        dispatcher.dispatch(30) { events += "new adopted" }
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf("new adopted"), events)
    }

    @Test fun releaseDiscardsPendingEventsAndEventsWithoutAnOwner() {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<Int>()
        val dispatcher = TaskCallbackDispatcher { queue.add(it) }
        dispatcher.dispatch(10) { events += 10 }
        assertTrue(queue.isEmpty())
        dispatcher.begin(30)
        dispatcher.dispatch(30) { events += 30 }
        dispatcher.forget(30)
        queue.removeFirst()()
        assertTrue(events.isEmpty())
    }

    @Test fun invalidatingOneTaskPreservesOtherTasksEvents() {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<Int>()
        val dispatcher = TaskCallbackDispatcher { queue.add(it) }
        for (taskId in listOf(10, 20)) {
            dispatcher.begin(taskId)
            dispatcher.dispatch(taskId) { events += taskId }
        }
        dispatcher.forget(10)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(listOf(20), events)
    }
}
