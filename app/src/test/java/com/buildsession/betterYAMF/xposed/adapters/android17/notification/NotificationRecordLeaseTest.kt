package com.buildsession.betterYAMF.xposed.adapters.android17.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationRecordLeaseTest {
    @Test fun anEnqueuedButNotYetPostedUpdateAlsoPreventsByKeyRemoval() {
        val original = Any(); var removed = false
        val lease = NotificationRecordLease("key", original)
        assertFalse(lease.consume({ original }, { true }) { removed = true })
        assertFalse(removed)
    }
    @Test fun queuedCompletionCannotDeleteAnUpdateWithTheSameKeyOrAnUnrelatedMessage() {
        val original = Any(); val update = Any(); val unrelated = Any()
        val posted = mutableMapOf("same-key" to original, "other-message" to unrelated)
        val lease = NotificationRecordLease("same-key", original)
        val queued = { lease.consume({ posted[lease.key] }) { posted.remove(lease.key) } }
        // Update happens AFTER a scene commit queues cancellation but BEFORE NMS executes it.
        posted["same-key"] = update
        assertFalse(queued())
        assertSame(update, posted["same-key"])
        assertSame(unrelated, posted["other-message"])
    }

    @Test fun successfulConsumptionIsExactlyOnceAndNeverCascadesToAnotherNotification() {
        val original = Any(); val unrelated = Any()
        val posted = mutableMapOf("clicked" to original, "other" to unrelated)
        val lease = NotificationRecordLease("clicked", original)
        assertTrue(lease.consume({ posted[lease.key] }) { posted.remove(lease.key) })
        posted["clicked"] = Any()
        assertFalse(lease.consume({ posted[lease.key] }) { fail("Duplicate completion") })
        assertSame(unrelated, posted["other"])
    }
}
