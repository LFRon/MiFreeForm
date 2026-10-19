package com.buildsession.betterYAMF.xposed.hook.launcher

import org.junit.Assert.*
import org.junit.Test

class DeferredGestureEndTest {
    @Test fun failedBridgeResumesNativeGestureExactlyOnce() {
        val gate = DeferredGestureEnd()
        var calls = 0
        assertTrue(gate.defer { calls++ })
        assertEquals(0, calls)
        gate.finish(resume = true)
        gate.finish(resume = true)
        assertEquals(1, calls)
    }

    @Test fun takeoverDiscardsUpAndCannotReplayItIntoTheNextGesture() {
        val gate = DeferredGestureEnd()
        var calls = 0
        gate.defer { calls++ }
        gate.finish(resume = false)
        gate.finish(resume = true)
        assertEquals(0, calls)
        assertFalse(gate.defer { calls++ })
    }

    @Test fun failedDispatchBeforeUpLeavesTheNativeEndUnintercepted() {
        val gate = DeferredGestureEnd()
        gate.finish(resume = true)
        assertFalse(gate.defer { fail("there was no deferred UP") })
    }

    @Test fun duplicateEndDoesNotReplaceTheCapturedContinuation() {
        val gate = DeferredGestureEnd()
        var calls = 0
        gate.defer { calls++ }
        gate.defer { calls += 100 }
        gate.finish(resume = true)
        assertEquals(1, calls)
    }
}
