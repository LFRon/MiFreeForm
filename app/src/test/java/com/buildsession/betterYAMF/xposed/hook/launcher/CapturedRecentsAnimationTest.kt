package com.buildsession.betterYAMF.xposed.hook.launcher

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class CapturedRecentsAnimationTest {
    // Exact constructor descriptor checked against the connected phone's Launcher APK.
    class Reason(val value: String, vararg val arguments: Any?)
    class CurrentController {
        var callback: Runnable? = null
        var flags: List<Boolean>? = null
        var reason: Reason? = null
        var failForced = false
        fun finishController(toHome: Boolean, callback: Runnable, leaveHint: Boolean, reason: Reason) {
            this.callback = callback
            flags = listOf(toHome, leaveHint)
            this.reason = reason
        }
        fun finishController(toHome: Boolean, callback: Runnable, leaveHint: Boolean, force: Boolean, reason: Reason) {
            this.callback = callback
            flags = listOf(toHome, leaveHint, force)
            this.reason = reason
            if (failForced) error("Native force finish failed")
        }
    }
    class LegacyController {
        var flags: List<Boolean>? = null
        fun finish(toHome: Boolean, callback: Runnable, leaveHint: Boolean) {
            flags = listOf(toHome, leaveHint)
            callback.run()
        }
        fun finishController(toHome: Boolean, callback: Runnable, leaveHint: Boolean, force: Boolean) {
            flags = listOf(toHome, leaveHint, force)
            callback.run()
        }
    }
    class ViewOnly {
        fun finishRecentsAnimation(toHome: Boolean, shouldPip: Boolean, callback: Runnable) {
            callback.run() // RecentsView can do this without owning any native controller.
        }
    }

    @Test fun nativeCompletionRemainsRequiredAfterViewReferenceIsCleared() {
        val native = CurrentController()
        var viewReference: Any? = native
        val captured = requireNotNull(CapturedRecentsAnimation.probe(viewReference))
        viewReference = null
        var completed = false
        captured.finish(Runnable { completed = true })
        assertNull(viewReference)
        assertFalse(completed)
        assertEquals(listOf(true, false), native.flags)
        assertNotNull(native.reason)
        requireNotNull(native.callback).run()
        assertTrue(completed)
    }

    @Test fun forcedCleanupTargetsCapturedControllerAndDisablesPip() {
        val native = CurrentController()
        val captured = requireNotNull(CapturedRecentsAnimation.probe(native))
        var completed = false
        captured.forceFinish(Runnable { completed = true })
        assertEquals(listOf(true, false, true), native.flags)
        assertFalse(completed)
        requireNotNull(native.callback).run()
        assertTrue(completed)
    }

    @Test fun legacySignaturesNeedNoPlatformVersionBranch() {
        val native = LegacyController()
        val captured = requireNotNull(CapturedRecentsAnimation.probe(native))
        var completions = 0
        captured.finish(Runnable { completions++ })
        assertEquals(listOf(true, false), native.flags)
        captured.forceFinish(Runnable { completions++ })
        assertEquals(listOf(true, false, true), native.flags)
        assertEquals(2, completions)
    }

    @Test fun forcedFailureCannotFallBackToASuccessfulNormalFinishOrFabricateCompletion() {
        val native = CurrentController().apply { failForced = true }
        val captured = requireNotNull(CapturedRecentsAnimation.probe(native))
        var completions = 0
        val error = assertThrows(InvocationTargetException::class.java) {
            captured.forceFinish(Runnable { completions++ })
        }
        assertTrue(error.cause is IllegalStateException)
        assertEquals(listOf(true, false, true), native.flags)
        assertEquals(0, completions)
    }

    @Test fun absentNativeControllerAndViewOnlyCapabilityFailClosed() {
        assertNull(CapturedRecentsAnimation.probe(null))
        assertNull(CapturedRecentsAnimation.probe(ViewOnly()))
    }
}
