package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import org.junit.Assert.*
import org.junit.Test

class HandoffOwnersTest {
    @Test fun previousTimeoutCannotConsumeTheNextGestureEvenForTheSameTask() {
        val owners = HandoffOwners<Int>()
        val first = owners.register(0, 9)
        val oldTimeout = { owners.take(first) }
        assertEquals(9, owners.invalidate(0))
        val second = owners.register(0, 9)
        assertNull(oldTimeout())
        assertTrue(owners.owns(second))
        assertEquals(9, owners.take(second))
    }

    @Test fun firstDownRevokesOldUiCallbacksBeforeAnotherHandoffIsRegistered() {
        val owners = HandoffOwners<String>()
        val first = owners.register(0, "source")
        var resets = 0
        val oldCommitCallback = { if (owners.take(first) != null) resets++ }
        owners.invalidate(0)
        oldCommitCallback()
        assertEquals(0, resets)
    }

    @Test fun completionIsExactlyOnceAndDisplaysAreIndependent() {
        val owners = HandoffOwners<Int>()
        val first = owners.register(0, 9)
        val otherDisplay = owners.register(1, 10)
        assertEquals(9, owners.take(first))
        assertNull(owners.take(first))
        assertTrue(owners.owns(otherDisplay))
        assertEquals(10, owners.take(otherDisplay))
    }
}
