package com.buildsession.betterYAMF.window.runtime

import com.buildsession.betterYAMF.window.api.*
import org.junit.Assert.*
import org.junit.Test

class LauncherEntryRuntimeTest {
    private class Fixture : RuntimeScheduler, LauncherEntryPort {
        var owner = true
        var candidateValue: EntryCandidate? = EntryCandidate(7, false, false, true)
        var queries = 0
        var retainedAccepted = true
        var duringQuery: (() -> Unit)? = null
        var duringArm: (() -> Unit)? = null
        var duringPresent: ((EntryTargetFrame) -> Unit)? = null
        override var capabilities = LauncherEntryCapabilities(true, true)
        val views = mutableListOf<EntryTargetFrame>()
        val retained = mutableListOf<EntryDrop>()
        val legacy = mutableListOf<EntryDrop>()
        val suppression = mutableListOf<Long>()
        val runtime = LauncherEntries.create(this, this)
        override fun isCurrentThread() = owner
        override fun post(work: () -> Unit) = work()
        override fun after(delayMs: Long, work: () -> Unit) = WindowOperation {}
        override fun nextFrame(work: (Long) -> Unit) = WindowOperation {}
        override fun candidate(): EntryCandidate? { queries++; duringQuery?.invoke(); return candidateValue }
        override fun present(frame: EntryTargetFrame) { views += frame; duringPresent?.invoke(frame) }
        override fun beginRetained(drop: EntryDrop): Boolean { retained += drop; duringArm?.invoke(); return retainedAccepted }
        override fun queueLegacy(drop: EntryDrop) { legacy += drop }
        override fun suppressNativeGesture(durationMs: Long) { suppression += durationMs }
        fun down() = runtime.onInput(EntryInput(PointerPhase.DOWN, 800f, 2750f, 2750f, 1264, 2780))
        fun move() = runtime.onInput(EntryInput(PointerPhase.MOVE, 1100f, 300f, 300f, 1264, 2780))
        fun up() = runtime.onInput(EntryInput(PointerPhase.UP, 1100f, 300f, 300f, 1264, 2780))
    }

    @Test fun aDropArmsOnlyTheRetainedSourceAndPreservesTheNativeInputPath() {
        val f = Fixture(); f.down(); f.move()
        assertTrue(f.views.last().visible); assertTrue(f.views.last().active)
        assertEquals(EntryDisposition.TAKEOVER_ARMED, f.up())
        assertEquals(listOf(EntryDrop(7, 1100, 300)), f.retained)
        assertTrue(f.legacy.isEmpty())
        assertFalse(f.views.last().visible)
        assertEquals(listOf(900L, 450L), f.suppression)
    }
    @Test fun unsupportedRetainedCaptureUsesOnlyTheDeclaredLegacyCapability() {
        val f = Fixture(); f.capabilities = LauncherEntryCapabilities(false, true)
        f.down(); f.move()
        assertEquals(EntryDisposition.LEGACY_QUEUED, f.up())
        assertTrue(f.retained.isEmpty()); assertEquals(1, f.legacy.size)
    }
    @Test fun aRejectedCaptureWithoutSafeFallbackLeavesTheNativeGestureAlone() {
        val f = Fixture(); f.capabilities = LauncherEntryCapabilities(true, false); f.retainedAccepted = false
        f.down(); f.move()
        assertEquals(EntryDisposition.PASS_THROUGH, f.up())
        assertTrue(f.legacy.isEmpty())
    }
    @Test fun homeAndInvisibleSourcesAreNeverAcceptedAsWindowCandidates() {
        for (value in listOf(null, EntryCandidate(7, true, true, true), EntryCandidate(7, false, false, false))) {
            val f = Fixture(); f.candidateValue = value
            f.down(); f.move(); f.move(); f.up()
            assertEquals(1, f.queries); assertTrue(f.retained.isEmpty()); assertTrue(f.legacy.isEmpty())
        }
    }
    @Test fun cancelAndReleaseOutsideTheCurrentTargetCannotOpenAWindow() {
        val f = Fixture(); f.down(); f.move()
        f.runtime.onInput(EntryInput(PointerPhase.CANCEL, 1100f, 300f, 300f, 1264, 2780))
        assertTrue(f.retained.isEmpty()); assertFalse(f.views.last().visible)
        f.down(); f.move()
        f.runtime.onInput(EntryInput(PointerPhase.UP, 100f, 900f, 900f, 1264, 2780))
        assertTrue(f.retained.isEmpty())
    }
    @Test fun crossingTheThresholdQueriesNativeFactsOnceWhileTheTargetRemainsVisible() {
        val f = Fixture(); f.down()
        f.runtime.onInput(EntryInput(PointerPhase.MOVE, 800f, 2500f, 2500f, 1264, 2780))
        assertEquals(0, f.queries)
        repeat(30) { f.move() }
        assertEquals(1, f.queries)
    }
    @Test fun explicitCancellationAndInvalidCrossDisplayInputHideTheTarget() {
        val f = Fixture(); f.down(); f.move()
        f.runtime.cancel()
        assertFalse(f.views.last().visible)
        f.up(); assertTrue(f.retained.isEmpty())
        f.down(); f.move()
        f.runtime.onInput(EntryInput(PointerPhase.UP, 1100f, 300f, 300f, 1264, 2780, displayId = 2))
        assertFalse(f.views.last().visible)
        assertTrue(f.retained.isEmpty())
        f.down(); f.move()
        f.runtime.onInput(EntryInput(PointerPhase.MOVE, Float.NaN, 300f, 300f, 1264, 2780))
        assertFalse(f.views.last().visible)
        f.up(); assertTrue(f.retained.isEmpty())
    }
    @Test fun rotationUsesDownDisplacementAndTheNewDisplaysUpperRightTarget() {
        val f = Fixture(); f.down()
        f.runtime.onInput(EntryInput(PointerPhase.MOVE, 2600f, 200f, 400f, 2780, 1264, rotation = 1))
        assertEquals(2780, f.views.last().bounds.right)
        assertTrue(f.views.last().active)
        assertEquals(EntryDisposition.TAKEOVER_ARMED,
            f.runtime.onInput(EntryInput(PointerPhase.UP, 2600f, 200f, 400f, 2780, 1264, rotation = 1)))
    }
    @Test fun wrongThreadAndReentrantInputCannotArmANativeGate() {
        val f = Fixture(); f.owner = false
        f.down(); f.move(); f.up()
        assertTrue(f.views.isEmpty()); assertTrue(f.retained.isEmpty())
        f.owner = true; f.down()
        f.duringQuery = { assertEquals(EntryDisposition.PASS_THROUGH, f.up()) }
        f.move()
        assertTrue(f.retained.isEmpty())
        f.duringQuery = null
        assertEquals(EntryDisposition.TAKEOVER_ARMED, f.up())
    }

    @Test fun cancellationDuringCandidateLookupCannotReviveTheCancelledGesture() {
        val f = Fixture(); f.down()
        f.duringQuery = { f.runtime.cancel() }
        f.move()
        assertFalse(f.views.last().visible)
        assertEquals(EntryDisposition.PASS_THROUGH, f.up())
        assertTrue(f.retained.isEmpty())
        assertTrue(f.legacy.isEmpty())
    }

    @Test fun cancellationWhileArmingPreservesNativeCompletionAndCannotStartAFallback() {
        for (accepted in listOf(true, false)) {
            val f = Fixture(); f.retainedAccepted = accepted
            f.down(); f.move()
            f.duringArm = { f.runtime.cancel() }
            assertEquals(if (accepted) EntryDisposition.TAKEOVER_ARMED else EntryDisposition.PASS_THROUGH, f.up())
            assertTrue(f.legacy.isEmpty())
            assertFalse(f.views.last().visible)
        }
    }

    @Test fun cancellationCallbacksCannotRecursivelyCancelOrDispatchANativeInput() {
        val f = Fixture(); f.down(); f.move()
        f.duringPresent = { frame ->
            if (!frame.visible) {
                f.runtime.cancel()
                assertEquals(EntryDisposition.PASS_THROUGH, f.up())
            }
        }
        f.runtime.cancel()
        assertFalse(f.views.last().visible)
        assertTrue(f.retained.isEmpty())
    }
}
