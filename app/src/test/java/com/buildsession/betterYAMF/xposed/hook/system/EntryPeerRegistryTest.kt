package com.buildsession.betterYAMF.xposed.hook.system

import org.junit.Assert.*
import org.junit.Test

class EntryPeerRegistryTest {
    @Test fun peersAreExplicitAndDoNotAssumeALauncherPackage() {
        val registry = EntryPeerRegistry()
        assertTrue(registry.recipients().isEmpty())
        assertFalse(registry.register(null))
        assertFalse(registry.register("android", 9))
        registry.register("example.recents", 20)
        assertEquals(listOf("example.recents"), registry.recipients().map { it.packageName })
    }
    @Test fun oldEpochsAndLateLegacyQueriesCannotOverrideANewProcess() {
        val registry = EntryPeerRegistry()
        registry.register("example.recents", 10)
        registry.register("example.recents", 20)
        registry.register("example.recents", 0)
        registry.register("example.recents", 10)
        assertTrue(registry.accepts("example.recents", 20))
        assertFalse(registry.accepts("example.recents", 10))
        assertFalse(registry.accepts("unknown", 20))
        assertTrue(registry.accepts("example.recents", 0)) // Explicit existing-v2 compatibility.
    }

    @Test fun sameLauncherPackageInDifferentUsersKeepsIndependentEpochUidAndCapabilities() {
        val registry = EntryPeerRegistry()
        registry.register("example.recents", 10, 0, 10200, true)
        registry.register("example.recents", 30, 10, 1010200, false)
        assertTrue(registry.accepts("example.recents", 10, 0))
        assertFalse(registry.accepts("example.recents", 10, 10))
        assertTrue(registry.accepts("example.recents", 30, 10))
        assertEquals(10200, registry.peer("example.recents", 0)?.uid)
        assertEquals(1010200, registry.peer("example.recents", 10)?.uid)
        assertTrue(requireNotNull(registry.peer("example.recents", 0)).asyncSnapshot)
        assertFalse(requireNotNull(registry.peer("example.recents", 10)).asyncSnapshot)
        assertEquals(setOf(0, 10), registry.recipients().map { it.userId }.toSet())
    }

    @Test fun routineQueriesPreserveNegotiationButNewProcessesMustRenegotiate() {
        val registry = EntryPeerRegistry()
        registry.register("example.recents", 10, 0, 10200, true)
        registry.register("example.recents", 10, 0, 10200)
        assertTrue(requireNotNull(registry.peer("example.recents", 0)).asyncSnapshot)
        assertFalse(registry.register("example.recents", 0, 0, 10200, false))
        assertFalse(registry.register("example.recents", 9, 0, 10200, false))
        assertTrue(requireNotNull(registry.peer("example.recents", 0)).asyncSnapshot)
        registry.register("example.recents", 20, 0, 10200)
        assertFalse(requireNotNull(registry.peer("example.recents", 0)).asyncSnapshot)
    }
}
