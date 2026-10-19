package com.buildsession.betterYAMF.xposed.hook.organizer

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class TaskVisibilityRefreshTest {
    @Test fun anOldInvisibleNotificationReadsTheNewVisibleStateWhenDispatched() {
        val queue = ArrayDeque<() -> Unit>()
        var visible = false
        val events = mutableListOf<Boolean>()
        val refresh = TaskVisibilityRefresh({ queue.add(it) }, { visible }, { _, value -> events += value })
        refresh.request(30) // Old task-moved / task-info event.
        visible = true // Organizer has made the destination visible before main handles it.
        refresh.request(30)
        assertEquals(1, queue.size)
        queue.removeFirst()()
        assertEquals(listOf(true), events)
    }

    @Test fun firstTrueIsDeliveredAndLaterRealHideAndShowArePreserved() {
        val queue = ArrayDeque<() -> Unit>()
        var visible = true
        val events = mutableListOf<Boolean>()
        val refresh = TaskVisibilityRefresh({ queue.add(it) }, { visible }, { _, value -> events += value })
        for (state in listOf(true, false, true)) {
            visible = state
            refresh.request(30)
            queue.removeFirst()()
        }
        assertEquals(listOf(true, false, true), events)
    }

    @Test fun releasingAndReusingATaskIdCannotLetTheOldRefreshConsumeTheNewOne() {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<Boolean>()
        val refresh = TaskVisibilityRefresh({ queue.add(it) }, { true }, { _, value -> events += value })
        refresh.request(30)
        refresh.forget(30)
        refresh.request(30)
        queue.removeFirst()()
        assertTrue(events.isEmpty())
        queue.removeFirst()()
        assertEquals(listOf(true), events)
    }

    @Test fun aVanishedNativeTaskDoesNotSynthesizeVisibility() {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<Boolean>()
        val refresh = TaskVisibilityRefresh({ queue.add(it) }, { null }, { _, value -> events += value })
        refresh.request(30)
        queue.removeFirst()()
        assertTrue(events.isEmpty())
    }
}
