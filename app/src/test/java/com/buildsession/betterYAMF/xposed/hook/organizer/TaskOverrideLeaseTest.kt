package com.buildsession.betterYAMF.xposed.hook.organizer

import com.buildsession.betterYAMF.window.api.WindowRect
import org.junit.Assert.*
import org.junit.Test

class TaskOverrideLeaseTest {
    @Test fun externalMigrationRetiresOnlyTheViewportAndModeStillOwnedByThisAdoption() {
        val task = Any()
        val viewport = WindowRect(0, 0, 1264, 1605)
        val lease = TaskOverrideLease(task, viewport)
        assertTrue(requireNotNull(lease.restoration(task, viewport, 5)).clearFreeformMode)
        // A native relaunch may already select fullscreen while leaving the old bounds.
        assertFalse(requireNotNull(lease.restoration(task, viewport, 1)).clearFreeformMode)
        // A new owner's split/freeform geometry cannot be erased.
        assertNull(lease.restoration(task, WindowRect(0, 0, 632, 2780), 5))
        assertNull(lease.restoration(task, WindowRect(0, 0, 0, 0), 1))
        // Task IDs alone cannot authorize changes to a replacement native object.
        assertNull(lease.restoration(Any(), viewport, 5))
    }
}
