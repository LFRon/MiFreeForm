package com.buildsession.betterYAMF.xposed.hook.launcher

import org.junit.Assert.*
import org.junit.Test

class SourceCommitFenceTest {
    @Test fun finalUpCannotPublishUntilItsOwnNativeTransformCommits() {
        val fence = SourceCommitFence()
        val move = fence.submitted()
        val up = fence.submitted()
        assertTrue(fence.freeze())
        assertTrue(fence.frozen)
        fence.committed(move)
        assertFalse(fence.takeReady())
        fence.committed(up)
        assertTrue(fence.takeReady())
        assertFalse(fence.takeReady())
    }

    @Test fun committedMoveCannotAcknowledgeAFinalUpThatHasNotCommitted() {
        val fence = SourceCommitFence()
        fence.committed(fence.submitted())
        fence.submitted()
        assertTrue(fence.freeze())
        assertFalse(fence.takeReady())
    }

    @Test fun earlyCommitWaitsForTheImmutablePoseAndDeliversOnlyOnce() {
        val fence = SourceCommitFence()
        fence.committed(fence.submitted())
        assertFalse(fence.takeReady())
        assertTrue(fence.freeze())
        assertTrue(fence.takeReady())
        assertFalse(fence.takeReady())
    }

    @Test fun noNativeTransactionIsNotAReadySource() {
        val fence = SourceCommitFence()
        assertFalse(fence.freeze())
        assertFalse(fence.frozen)
        assertFalse(fence.takeReady())
    }

    @Test fun cancellationUnfreezesAndRejectsAnOldCommitFromTheNextSession() {
        val old = SourceCommitFence()
        val ticket = old.submitted()
        assertTrue(old.freeze())
        old.close()
        assertFalse(old.frozen)
        old.committed(ticket)
        assertFalse(old.takeReady())
        val next = SourceCommitFence()
        val nextTicket = next.submitted()
        assertTrue(next.freeze())
        next.committed(ticket)
        assertFalse(next.takeReady())
        next.committed(nextTicket)
        assertTrue(next.takeReady())
    }

    @Test fun aFrozenSourceRejectsFurtherNativeWritesUntilClosed() {
        val fence = SourceCommitFence()
        fence.submitted()
        assertTrue(fence.freeze())
        assertThrows(IllegalStateException::class.java) { fence.submitted() }
        fence.close()
        assertThrows(IllegalStateException::class.java) { fence.submitted() }
    }
}
