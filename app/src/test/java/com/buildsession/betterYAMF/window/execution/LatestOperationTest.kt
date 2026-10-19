package com.buildsession.betterYAMF.window.execution

import org.junit.Assert.*
import org.junit.Test

class LatestOperationTest {
    @Test fun aCancelledCallbackCannotClearTheReplacementOperation() {
        val cancelled = mutableListOf<String>()
        val slot = LatestOperation<String> { cancelled += it }
        val old = slot.begin()
        slot.attach(old, "old")
        val next = slot.begin()
        slot.attach(next, "next")
        assertEquals(listOf("old"), cancelled)
        assertFalse(slot.complete(old))
        assertTrue(slot.complete(next))
        assertFalse(slot.complete(next))
        slot.cancel()
        assertEquals(listOf("old"), cancelled)
    }

    @Test fun synchronousCompletionDoesNotLeaveACompletedResourcePending() {
        val cancelled = mutableListOf<String>()
        val slot = LatestOperation<String> { cancelled += it }
        val ticket = slot.begin()
        assertTrue(slot.complete(ticket))
        slot.attach(ticket, "already complete")
        slot.cancel()
        assertTrue(cancelled.isEmpty())
    }

    @Test fun destructionBeforeTheApiReturnsCancelsTheLateResource() {
        val cancelled = mutableListOf<String>()
        val slot = LatestOperation<String> { cancelled += it }
        val ticket = slot.begin()
        slot.cancel()
        slot.attach(ticket, "late")
        assertEquals(listOf("late"), cancelled)
        assertFalse(slot.complete(ticket))
        slot.cancel()
        assertEquals(listOf("late"), cancelled)
    }

    @Test fun synchronousCancellationCannotCompleteTheNewTicket() {
        lateinit var slot: LatestOperation<() -> Unit>
        slot = LatestOperation { it() }
        val old = slot.begin()
        var oldResultAccepted = true
        slot.attach(old) { oldResultAccepted = slot.complete(old) }
        val next = slot.begin()
        assertFalse(oldResultAccepted)
        assertTrue(slot.complete(next))
    }

    @Test fun replacingAnOperationBeforeItsResourceReturnsStillCancelsIt() {
        val cancelled = mutableListOf<String>()
        val slot = LatestOperation<String> { cancelled += it }
        val old = slot.begin()
        val next = slot.begin()
        slot.attach(old, "old")
        slot.attach(next, "next")
        slot.cancel()
        assertEquals(listOf("old", "next"), cancelled)
    }
}
