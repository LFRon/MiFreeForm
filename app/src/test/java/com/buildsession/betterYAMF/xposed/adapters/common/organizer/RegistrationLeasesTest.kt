package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import org.junit.Assert.*
import org.junit.Test

class RegistrationLeasesTest {
    private data class Handle(val nativeSurface: Int)

    @Test fun localOrFailedRegistrationReleasesEveryOwnedCopyExactlyOnce() {
        val released = mutableListOf<Handle>()
        val leases = RegistrationLeases<Handle> { released += it }
        val a = Handle(1); val b = Handle(2)
        leases.record(a); leases.record(b); leases.record(a)
        leases.close(); leases.close()
        assertEquals(2, released.size)
        assertTrue(released.any { it === a }); assertTrue(released.any { it === b })
    }
    @Test fun filteringAForeignReturnReleasesOnlyExcludedCopiesAndKeepsTransferredOnesAlive() {
        val released = mutableListOf<Handle>()
        val leases = RegistrationLeases<Handle> { released += it }
        val ours = Handle(1); val other = Handle(2)
        leases.record(ours); leases.record(other)
        leases.discard(ours); leases.transfer(other)
        leases.close()
        assertEquals(listOf(ours), released)
    }
    @Test fun equalNativeSurfacesRemainIndependentReferencesAndBorrowedHandlesAreNeverReleased() {
        val released = mutableListOf<Handle>()
        val leases = RegistrationLeases<Handle> { released += it }
        val a = Handle(1); val b = Handle(1); val borrowed = Handle(1)
        leases.record(a); leases.record(b)
        leases.discard(borrowed); leases.transfer(a); leases.close()
        assertEquals(1, released.size); assertSame(b, released.single())
    }
    @Test fun aBrokenReleaseCannotPreventOtherCopiesFromBeingRetired() {
        val released = mutableListOf<Handle>()
        val leases = RegistrationLeases<Handle> { released += it; error("release failure") }
        leases.record(Handle(1)); leases.record(Handle(2))
        assertThrows(IllegalStateException::class.java) { leases.close() }
        leases.close()
        assertEquals(2, released.size)
    }
    @Test fun anUncertainForeignReturnDoesNotRevokePossiblyReturnedHandles() {
        val released = mutableListOf<Handle>()
        val leases = RegistrationLeases<Handle> { released += it }
        leases.record(Handle(1)); leases.preserveUncertainReturn(); leases.close()
        assertTrue(released.isEmpty())
    }
}
