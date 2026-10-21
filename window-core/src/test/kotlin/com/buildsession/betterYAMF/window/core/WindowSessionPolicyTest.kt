package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.TaskFocusFacts
import com.buildsession.betterYAMF.window.api.TaskFocusOrigin
import org.junit.Assert.*
import org.junit.Test

class WindowSessionPolicyTest {
    @Test fun automaticFocusSkipsAllWindowsAndPreservesTheRemainingNativeOrder() {
        // Top-to-bottom native candidates: two windows, recents/Home, foreground/background app.
        val candidates = listOf(14 to true, 13 to true, 8 to false, 15 to false)
        val allowed = candidates.filter { (_, owned) ->
            WindowSessionPolicy.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC, owned))
        }
        assertEquals(listOf(8, 15), allowed.map { it.first })
    }

    @Test fun directInteractionAllowsEitherWindowWithoutChangingTheAutomaticRule() {
        for (owned in listOf(true, false))
            assertTrue(WindowSessionPolicy.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.DIRECT, owned)))
        assertFalse(WindowSessionPolicy.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC, true)))
    }

    @Test fun nativeOwnershipHandbackImmediatelyRestoresAutomaticEligibility() {
        assertFalse(WindowSessionPolicy.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC, true)))
        assertTrue(WindowSessionPolicy.acceptsTaskFocus(TaskFocusFacts(TaskFocusOrigin.AUTOMATIC, false)))
    }
}
