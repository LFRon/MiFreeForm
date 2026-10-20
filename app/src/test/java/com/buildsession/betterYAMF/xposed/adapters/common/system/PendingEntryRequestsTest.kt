package com.buildsession.betterYAMF.xposed.adapters.common.system

import com.buildsession.betterYAMF.window.api.WindowOperation
import org.junit.Assert.*
import org.junit.Test

class PendingEntryRequestsTest {
    private fun key(id: Long, user: Int = 0, epoch: Long = 10, task: Int = 42) =
        PendingEntryRequests.Key("example.launcher", user, epoch, task, id)

    @Test fun cancellationBeforeOpenAndDuplicateBroadcastCannotCreateAnEntry() {
        val requests = PendingEntryRequests { throw it }
        requests.cancel(key(100))
        assertNull(requests.begin(key(100)) { fail("Never started") })
        assertNull(requests.begin(key(99)) { fail("Older than cancellation") })
        val accepted = requireNotNull(requests.begin(key(101)) {})
        assertTrue(requests.take(accepted))
        assertNull(requests.begin(key(101)) { fail("Duplicate after completion") })
    }

    @Test fun replacingAPendingEntryInvalidatesItBeforeSynchronousCancellationCallbacks() {
        val requests = PendingEntryRequests { throw it }
        var retired = 0
        val old = requireNotNull(requests.begin(key(100)) { retired++ })
        requests.attach(old, WindowOperation { assertFalse(requests.take(old)) })
        val current = requireNotNull(requests.begin(key(101)) {})
        assertEquals(1, retired)
        requests.cancel(key(100))
        assertTrue(requests.take(current))
        assertFalse(requests.take(old))
    }

    @Test fun attachingAfterSynchronousCancellationImmediatelyCancelsOnlyThatRead() {
        val requests = PendingEntryRequests { throw it }
        var cancelled = 0
        var retired = 0
        val ticket = requireNotNull(requests.begin(key(100)) { retired++ })
        requests.cancel(key(100))
        requests.attach(ticket, WindowOperation { cancelled++ })
        requests.cancel(key(100))
        assertEquals(1, cancelled)
        assertEquals(1, retired)
    }

    @Test fun peerRestartCannotCancelAnotherUserOrAnotherTaskAndOldCallbacksStayInvalid() {
        val requests = PendingEntryRequests { throw it }
        var retired = 0
        val old = requireNotNull(requests.begin(key(100)) { retired++ })
        val other = requireNotNull(requests.begin(key(100, user = 10, task = 90)) {})
        val parallel = requireNotNull(requests.begin(key(90, task = 91)) {})
        requests.peerChanged("example.launcher", 0, 20)
        assertEquals(1, retired)
        assertFalse(requests.take(old))
        assertFalse(requests.take(parallel))
        assertTrue(requests.take(other))
        assertNotNull(requests.begin(key(1, epoch = 20)) {})
    }

    @Test fun faultyCancellationCannotSkipRetirementOrDamageTheReplacement() {
        val errors = mutableListOf<Throwable>()
        val requests = PendingEntryRequests(errors::add)
        var retired = false
        val old = requireNotNull(requests.begin(key(100)) { retired = true })
        requests.attach(old, WindowOperation { error("broken cancellation") })
        val current = requireNotNull(requests.begin(key(101)) {})
        assertTrue(retired)
        assertEquals(1, errors.size)
        assertTrue(requests.take(current))
    }
}
