package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import org.junit.Assert.*
import org.junit.Test

class LauncherSourceRetirementTest {
    class State(val name: String)

    /** Models the public LoS StateManager/playback contract and its same-state early return. */
    class NativeStateManager {
        val normal = State("Normal")
        val overview = State("Overview")
        var rest = normal
        var current = normal
        var stable = overview
        var depth = 0.3528f
        var iconAlpha = 0.4f
        var transitionActive = true
        var stateElementAnimationActive = true
        var animation: Playback? = Playback(this, normal)
        var completions = 0
        var onEnd: (() -> Unit)? = null
        fun getRestState(): State = rest
        fun cancelAnimation() { animation = null }
        fun goToState(state: State, animated: Boolean) {
            if (current === state && animation == null) return
            cancelAnimation()
            current = state
            applyState()
            complete()
        }
        fun createAnimationToNewWorkspace(state: State, duration: Long): Playback {
            // Native Holder.globalEndProgress divides by this value, even for manual playback.
            require(duration > 0)
            cancelAnimation()
            return Playback(this, state).also { animation = it }
        }
        fun reapplyState(cancelCurrentAnimation: Boolean) {
            val wasInAnimation = animation != null
            if (cancelCurrentAnimation) {
                stateElementAnimationActive = false
                cancelAnimation()
            }
            if (animation == null) {
                applyState()
                if (wasInAnimation) complete()
            }
        }
        fun applyState() {
            depth = if (current === normal) 0f else 1f
            iconAlpha = if (current === normal) 1f else 0f
        }
        fun complete() {
            stable = current
            transitionActive = false
            animation = null
            completions++
            onEnd?.invoke()
        }
    }

    class Playback(private val manager: NativeStateManager, private val target: State) {
        fun dispatchOnStart(): Playback {
            manager.current = target
            manager.transitionActive = true
            return this
        }
    }

    @Test fun sameStateAfterCancelStillNeedsFinalPropertiesAndTransitionCompletion() {
        val native = NativeStateManager()
        // This was the old adapter's exact order. Native goToState takes the early return.
        native.cancelAnimation()
        native.goToState(native.normal, false)
        assertSame(native.overview, native.stable)
        assertTrue(native.transitionActive)
        assertEquals(0.3528f, native.depth)

        requireNotNull(LauncherSourceRetirement.probe(native)).finish()
        assertSame(native.normal, native.current)
        assertSame(native.normal, native.stable)
        assertEquals(0f, native.depth)
        assertEquals(1f, native.iconAlpha)
        assertFalse(native.transitionActive)
        assertFalse(native.stateElementAnimationActive)
        assertNull(native.animation)
        assertEquals(1, native.completions)
    }

    @Test fun repeatedHandoffsEachLeaveOneCompletedStableState() {
        val native = NativeStateManager()
        val retirement = requireNotNull(LauncherSourceRetirement.probe(native))
        repeat(30) {
            native.stable = native.overview
            native.depth = 0.35f
            native.iconAlpha = 0.4f
            native.transitionActive = true
            native.stateElementAnimationActive = true
            native.animation = Playback(native, native.normal)
            retirement.finish()
            assertSame(native.normal, native.stable)
            assertEquals(0f, native.depth)
            assertEquals(1f, native.iconAlpha)
            assertFalse(native.transitionActive)
            assertFalse(native.stateElementAnimationActive)
            assertNull(native.animation)
        }
        assertEquals(30, native.completions)
    }

    @Test fun nativeEndListenerCanInstallASubsequentAnimationWithoutItBeingCancelled() {
        val native = NativeStateManager()
        val successor = Playback(native, native.overview)
        native.onEnd = {
            native.animation = successor
            native.transitionActive = true
        }
        requireNotNull(LauncherSourceRetirement.probe(native)).finish()
        assertSame(successor, native.animation)
        assertTrue(native.transitionActive)
    }

    @Test fun restStateIsReadAtCompletionAndIsNotHardCodedToHome() {
        val native = NativeStateManager()
        val retirement = requireNotNull(LauncherSourceRetirement.probe(native))
        native.rest = native.overview
        retirement.finish()
        assertSame(native.overview, native.current)
        assertSame(native.overview, native.stable)
        assertFalse(native.transitionActive)
    }

    class StateNameOnly {
        fun getRestState(): State = State("Normal")
        fun goToState(state: State, animated: Boolean) = Unit
    }

    @Test fun stateNameOnlyCapabilityIsInsufficientForAnInterceptedHandoff() {
        assertNull(LauncherSourceRetirement.probe(StateNameOnly()))
    }

    class OptimizedStateManager {
        val delegate = NativeStateManager()
        var lastFlags = -1
        fun getRestState(): State = delegate.getRestState()
        fun createAnimationToNewWorkspace(state: State, duration: Long, flags: Int): OptimizedPlayback {
            lastFlags = flags
            return OptimizedPlayback(delegate.createAnimationToNewWorkspace(state, duration))
        }
        fun reapplyState(cancel: Boolean) = delegate.reapplyState(cancel)
    }

    class OptimizedPlayback(private val delegate: Playback) {
        // R8 removes the fluent result from the reviewed Pixel APK.
        fun dispatchOnStart() { delegate.dispatchOnStart() }
    }

    @Test fun optimizedLauncherUsesTheExplicitThreeArgumentAbiWithNativeDefaultFlags() {
        val native = OptimizedStateManager()
        assertNull(LauncherSourceRetirement.probe(native))
        val retirement = requireNotNull(LauncherSourceRetirement.probe(native,
            LauncherSourceRetirement.Signature.THREE_ARGUMENTS))
        retirement.finish()
        assertEquals(0, native.lastFlags)
        assertSame(native.delegate.normal, native.delegate.stable)
        assertFalse(native.delegate.transitionActive)
        assertEquals(1, native.delegate.completions)
    }

    @Test fun mismatchedAbiIsRejectedBeforeAnInterceptedGesture() {
        assertNull(LauncherSourceRetirement.probe(NativeStateManager(),
            LauncherSourceRetirement.Signature.THREE_ARGUMENTS))
    }
}
