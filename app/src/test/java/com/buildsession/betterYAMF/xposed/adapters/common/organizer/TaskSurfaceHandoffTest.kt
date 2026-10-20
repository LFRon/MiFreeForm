package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceHandoffTest {
    private class Surface(var hidden: Boolean = true, var alpha: Float = 0f)

    private class Transaction {
        private val changes = mutableListOf<() -> Unit>()
        var visibilityWrites = 0
            private set
        fun restoreAlpha(surface: Surface) { changes += { surface.alpha = 1f } }
        fun setVisibility(surface: Surface, visible: Boolean) {
            visibilityWrites++
            changes += { surface.hidden = !visible }
        }
        fun commit() { changes.toList().also { changes.clear() }.forEach { it() } }
    }

    // The adapter must resolve inherited WM fields/methods and the *current* migrated surface.
    private open class Container(val mWmService: Wm) {
        var surface: Surface? = Surface()
        val transaction = Transaction()
        var visible = true
        var transition = false
        var scheduled = false
        fun getSurfaceControl(): Surface? = surface
        fun getSyncTransaction(): Transaction = transaction
        fun isVisible(): Boolean = visible
        fun inTransition(): Boolean = transition
        fun scheduleAnimation() { scheduled = true }
    }
    private class Task(wm: Wm) : Container(wm)
    private class Wm(val mAnimator: Any) { val mGlobalLock = Any() }
    private class Animator {
        val pending = mutableListOf<Container>()
        fun addSurfaceVisibilityUpdate(task: Container) { pending += task }
        fun frame() {
            pending.forEach { task ->
                if (!task.transition) task.transaction.setVisibility(requireNotNull(task.surface), task.visible)
                task.transaction.commit()
            }
            pending.clear()
        }
    }

    @Test fun migratedHiddenSurfaceIsRestoredThroughWmFrameWithoutReusingOldLeash() {
        val animator = Animator()
        val task = Task(Wm(animator))
        val oldLeash = requireNotNull(task.surface)
        task.surface = Surface() // TaskOrganizerController.removeTask revokes the old surface.
        val replacement = requireNotNull(task.surface)

        assertTrue(TaskSurfaceHandoff.finish(task) { surface, transaction ->
            assertTrue(Thread.holdsLock(task.mWmService.mGlobalLock))
            assertSame(replacement, surface)
            (transaction as Transaction).restoreAlpha(surface as Surface)
        })
        assertTrue(task.scheduled)
        assertTrue(replacement.hidden) // No independent transaction ahead of WM's reparent.
        assertEquals(0f, replacement.alpha)
        animator.frame()
        assertFalse(replacement.hidden)
        assertEquals(1f, replacement.alpha)
        assertTrue(oldLeash.hidden)
        assertEquals(0f, oldLeash.alpha)
    }

    @Test fun backgroundReleaseUsesVisibilityAtCommitInsteadOfForcingShow() {
        val animator = Animator()
        val task = Task(Wm(animator))
        TaskSurfaceHandoff.finish(task) { _, _ -> }
        task.visible = false // A close or Home can change visibility before the next WM frame.
        animator.frame()
        assertTrue(requireNotNull(task.surface).hidden)
        assertEquals(1, task.transaction.visibilityWrites)
    }

    @Test fun activeSystemTransitionKeepsVisibilityOwnership() {
        val animator = Animator()
        val task = Task(Wm(animator)).apply { transition = true }
        TaskSurfaceHandoff.finish(task) { _, _ -> }
        animator.frame()
        assertEquals(0, task.transaction.visibilityWrites)
        assertTrue(requireNotNull(task.surface).hidden)
    }

    @Test fun olderVisibilityCapabilityUsesSyncTransactionAndRespectsTransition() {
        val task = Task(Wm(Any()))
        assertTrue(TaskSurfaceHandoff.finish(task) { _, _ -> })
        assertTrue(requireNotNull(task.surface).hidden)
        task.transaction.commit()
        assertFalse(requireNotNull(task.surface).hidden)

        task.transition = true
        TaskSurfaceHandoff.finish(task) { _, _ -> }
        assertEquals(1, task.transaction.visibilityWrites)
    }

    @Test fun removedTaskHasNoSurfaceToResetOrSchedule() {
        val task = Task(Wm(Animator())).apply { surface = null }
        assertFalse(TaskSurfaceHandoff.finish(task) { _, _ -> fail("No surface remains") })
        assertFalse(task.scheduled)
    }
}
