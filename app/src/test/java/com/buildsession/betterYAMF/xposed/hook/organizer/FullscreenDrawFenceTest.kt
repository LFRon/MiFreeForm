package com.buildsession.betterYAMF.xposed.hook.organizer

import org.junit.Assert.*
import org.junit.Test

class FullscreenDrawFenceTest {
    @Test fun aTimeoutOrOldDrawCannotProveTheNewFullscreenBuffer() {
        val gate = FullscreenDrawFence()
        val window = Any(); val surface = Any()
        gate.arm(window, surface, 20)
        assertNull(gate.buffer(window, surface, Int.MAX_VALUE, hasBuffer = false))
        assertNull(gate.buffer(window, surface, 19, hasBuffer = true))
        assertFalse(gate.committed)
        val ticket = gate.buffer(window, surface, 20, hasBuffer = true)!!
        assertFalse(gate.committed)
        assertTrue(gate.commit(ticket))
        assertTrue(gate.committed)
    }

    @Test fun aMigratedSurfaceRejectsTheOldBuffersLateCommit() {
        val gate = FullscreenDrawFence()
        val window = Any(); val old = Any(); val replacement = Any()
        gate.arm(window, old, 5)
        val oldBuffer = gate.buffer(window, old, 5, true)!!
        gate.arm(window, replacement, 6)
        assertFalse(gate.commit(oldBuffer))
        assertNull(gate.buffer(window, old, 50, true))
        assertFalse(gate.committed)
        val fresh = gate.buffer(window, replacement, 6, true)!!
        assertTrue(gate.commit(fresh))
    }

    @Test fun activityRelaunchRequiresTheReplacementWindowsOwnDraw() {
        val gate = FullscreenDrawFence()
        val oldWindow = Any(); val newWindow = Any(); val surface = Any()
        gate.arm(oldWindow, surface, 10)
        val old = gate.buffer(oldWindow, surface, 10, true)!!
        gate.arm(newWindow, surface, 1)
        assertFalse(gate.commit(old))
        assertNull(gate.buffer(oldWindow, surface, 100, true))
        assertTrue(gate.commit(gate.buffer(newWindow, surface, 1, true)!!))
    }

    @Test fun replacingOnlyTheClientBufferLayerInvalidatesAnOldCommit() {
        val gate = FullscreenDrawFence()
        val window = Any(); val taskSurface = Any(); val oldClient = Any(); val newClient = Any()
        gate.arm(window, taskSurface, 4, oldClient)
        val old = gate.buffer(window, taskSurface, 4, true, oldClient)!!
        gate.arm(window, taskSurface, 5, newClient)
        assertFalse(gate.commit(old))
        assertNull(gate.buffer(window, taskSurface, 9, true, oldClient))
        assertTrue(gate.commit(gate.buffer(window, taskSurface, 5, true, newClient)!!))
    }

    @Test fun newLayoutOnTheSameSurfaceAndCancellationInvalidateOldEvidence() {
        val gate = FullscreenDrawFence()
        val window = Any(); val surface = Any()
        gate.arm(window, surface, 3)
        val oldLayout = gate.buffer(window, surface, 3, true)!!
        gate.arm(window, surface, 4)
        assertFalse(gate.commit(oldLayout))
        val newLayout = gate.buffer(window, surface, 4, true)!!
        gate.invalidate()
        assertFalse(gate.commit(newLayout))
        assertFalse(gate.committed)
        assertFalse(gate.matches(window, surface))
    }
}
