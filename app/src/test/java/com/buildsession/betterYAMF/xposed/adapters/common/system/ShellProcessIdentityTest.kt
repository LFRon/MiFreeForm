package com.buildsession.betterYAMF.xposed.adapters.common.system

import org.junit.Assert.*
import org.junit.Test

class ShellProcessIdentityTest {
    private val shell = ShellOwnershipProtocol.PACKAGE

    @Test fun packageAssignedUidCanInstallAndAuthenticateTheShell() {
        // Regression: feedback device's SystemUI is UID 10188, not android.uid.system (1000).
        for (uid in listOf(10188, 1000, 10237)) {
            assertTrue(ShellProcessIdentity.acceptsHost(shell, shell, true, uid, uid))
            assertTrue(ShellProcessIdentity.acceptsSender(0, shell, uid, uid))
            assertTrue(ShellProcessIdentity(uid, 5410).matches(uid, 5410))
        }
    }

    @Test fun hostMustMatchTheActualPackageAndMainProcess() {
        assertFalse(ShellProcessIdentity.acceptsHost("android", "android", true, 1000, 1000))
        assertFalse(ShellProcessIdentity.acceptsHost(shell, "$shell:aux", true, 10188, 10188))
        assertFalse(ShellProcessIdentity.acceptsHost(null, null, true, 10188, 10188))
    }

    @Test fun privilegedSystemUidDoesNotOverrideThePackageUid() {
        assertFalse(ShellProcessIdentity.acceptsHost(shell, shell, true, 1000, 10188))
        assertFalse(ShellProcessIdentity.acceptsSender(0, shell, 1000, 10188))
    }

    @Test fun aSecondaryUsersShellCannotAcknowledgeTheSystemUsersOwner() {
        assertFalse(ShellProcessIdentity.acceptsHost(shell, shell, false, 1010188, 1010188))
        assertFalse(ShellProcessIdentity.acceptsSender(10, shell, 1010188, 1010188))
        assertFalse(ShellProcessIdentity.acceptsSender(0, shell, 1010188, 10188))
    }

    @Test fun missingPackageUidOrSharedIdentityCannotBecomeAuthenticated() {
        assertFalse(ShellProcessIdentity.acceptsHost(shell, shell, true, 10188, -1))
        assertFalse(ShellProcessIdentity.acceptsHost(shell, shell, true, -1, -1))
        assertFalse(ShellProcessIdentity.acceptsSender(0, shell, -1, 10188))
        assertFalse(ShellProcessIdentity.acceptsSender(0, shell, -1, -1))
        assertFalse(ShellProcessIdentity.acceptsSender(0, null, 10188, 10188))
    }

    @Test fun aPackageClaimDoesNotReplaceTheRealBroadcastIdentity() {
        assertFalse(ShellProcessIdentity.acceptsSender(0, "android", 10188, 10188))
        assertFalse(ShellProcessIdentity.acceptsSender(0, "com.example.other", 10188, 10188))
        assertFalse(ShellProcessIdentity.acceptsSender(0, shell, 10237, 10188))
    }

    @Test fun nativeCallerMustMatchBothTheAuthenticatedUidAndPid() {
        val peer = ShellProcessIdentity(10188, 5410)
        assertTrue(peer.matches(10188, 5410))
        assertFalse(peer.matches(1000, 5410))
        assertFalse(peer.matches(10237, 5410))
        assertFalse(peer.matches(10188, 5411))
        assertFalse(peer.matches(10188, -1))
    }

    @Test fun aReplacementProcessDoesNotInheritTheFormerProcessAddress() {
        val old = ShellProcessIdentity(10188, 5410)
        val replacement = ShellProcessIdentity(10188, 6200)
        assertFalse(replacement.matches(old.uid, old.pid))
        // Reusing a PID in a different UID also cannot inherit Shell's identity.
        assertFalse(old.matches(10237, old.pid))
    }

    @Test fun invalidNativeAddressesAreRejectedBeforeRegistration() {
        for ((uid, pid) in listOf(-1 to 5410, 10188 to -1, 10188 to 0)) {
            assertThrows(IllegalArgumentException::class.java) { ShellProcessIdentity(uid, pid) }
        }
    }
}
