package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import org.junit.Assert.*
import org.junit.Test

class LauncherInputOwnershipTest {
    private data class Gesture(val taskId: Int)

    @Test fun navigationDownMustCreateTheExactGestureUsedByItsConsumer() {
        val leases = LauncherInputOwnership()
        val handler = Any()
        val previous = Gesture(42)
        val current = Gesture(42)
        leases.bind(handler)
        val down = requireNotNull(leases.beforeDown(handler, previous))
        assertTrue(leases.afterDown(down, current, current))
        assertTrue(leases.owns(handler, current, current))
        assertFalse(leases.owns(handler, previous, previous))
        assertFalse(leases.owns(handler, current, Gesture(42)))
    }

    @Test fun ignoredDownCannotReuseTheLastSwipeEvenWithAnUnchangedTask() {
        val leases = LauncherInputOwnership()
        val handler = Any()
        val old = Gesture(42)
        leases.bind(handler)
        val initial = requireNotNull(leases.beforeDown(handler, null))
        assertTrue(leases.afterDown(initial, old, old))
        val ignored = requireNotNull(leases.beforeDown(handler, old))
        assertFalse(leases.afterDown(ignored, old, old))
        assertFalse(leases.owns(handler, old, old))
    }

    @Test fun differentConsumersAndMissingResourcesCannotAdmitNavigation() {
        for (consumerGesture in listOf(null, Gesture(7))) {
            val leases = LauncherInputOwnership()
            val handler = Any()
            val current = Gesture(7)
            leases.bind(handler)
            val down = requireNotNull(leases.beforeDown(handler, null))
            assertFalse(leases.afterDown(down, current, consumerGesture))
            assertFalse(leases.owns(handler, current, current))
        }
    }

    @Test fun lateCallbacksFromAnOldComponentCannotStealANewDown() {
        val leases = LauncherInputOwnership()
        val oldHandler = Any()
        val newHandler = Any()
        leases.bind(oldHandler)
        val oldDown = requireNotNull(leases.beforeDown(oldHandler, null))
        leases.bind(newHandler)
        val newDown = requireNotNull(leases.beforeDown(newHandler, null))
        val oldGesture = Gesture(3)
        assertFalse(leases.afterDown(oldDown, oldGesture, oldGesture))
        assertNull(leases.beforeDown(oldHandler, null))
        val current = Gesture(3)
        assertTrue(leases.afterDown(newDown, current, current))
        assertTrue(leases.owns(newHandler, current, current))
        assertFalse(leases.owns(oldHandler, current, current))
    }

    @Test fun aSupersededDownOnTheSameHandlerCannotAdmitTheNextGesture() {
        val leases = LauncherInputOwnership()
        val handler = Any()
        leases.bind(handler)
        val old = requireNotNull(leases.beforeDown(handler, null))
        val next = requireNotNull(leases.beforeDown(handler, null))
        val current = Gesture(3)
        assertFalse(leases.afterDown(old, current, current))
        assertTrue(leases.afterDown(next, current, current))
        assertFalse(leases.afterDown(next, current, current))
    }

    @Test fun rebindingEvenTheSameHandlerRevokesItsPreviousInputLease() {
        val leases = LauncherInputOwnership()
        val handler = Any()
        leases.bind(handler)
        val previous = requireNotNull(leases.beforeDown(handler, null))
        leases.bind(handler)
        val current = Gesture(3)
        assertFalse(leases.afterDown(previous, current, current))
        assertFalse(leases.owns(handler, current, current))
        val next = requireNotNull(leases.beforeDown(handler, null))
        assertTrue(leases.afterDown(next, current, current))
    }

    @Test fun completionAndDestructionRevokePendingAndAcceptedInput() {
        val leases = LauncherInputOwnership()
        val handler = Any()
        val current = Gesture(3)
        leases.bind(handler)
        var down = requireNotNull(leases.beforeDown(handler, null))
        assertTrue(leases.afterDown(down, current, current))
        leases.clearGesture()
        assertFalse(leases.owns(handler, current, current))
        down = requireNotNull(leases.beforeDown(handler, null))
        leases.close()
        assertFalse(leases.afterDown(down, current, current))
        assertFalse(leases.accepts(handler))
    }
}
