package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Platform-only completion of an organizer handoff, after the hierarchy WCT has returned. */
internal object TaskSurfaceHandoff {
    /**
     * LoS 24 TaskOrganizerController:421 revokes the old leash by migrating the task surface.
     * Task:4441 creates its replacement hidden. Reset the CURRENT surface in WM's transaction,
     * then use WindowAnimator:298-323 to reconcile visibility without taking over a transition.
     * The reset callback borrows both handles; it must neither apply nor close the transaction.
     */
    fun finish(task: Any, reset: (surface: Any, transaction: Any) -> Unit): Boolean {
        val wm = requireNotNull(task.field("mWmService").get(task))
        val lock = requireNotNull(wm.field("mGlobalLock").get(wm))
        synchronized(lock) {
            val surface = task.method("getSurfaceControl").invoke(task) ?: return false
            val transaction = requireNotNull(task.method("getSyncTransaction").invoke(task))
            reset(surface, transaction)

            val animator = requireNotNull(wm.field("mAnimator").get(wm))
            val reconcile = animator.findMethod("addSurfaceVisibilityUpdate", task.javaClass)
            if (reconcile != null) {
                // WM evaluates isVisible() on the next frame and skips surfaces owned by an
                // active transition. In particular, a close-to-background must not force show.
                reconcile.invoke(animator, task)
            } else if (task.method("inTransition").invoke(task) == false) {
                // Earlier platform capability: use WM's visibility and its sync transaction.
                transaction.method("setVisibility", surface.javaClass, Boolean::class.javaPrimitiveType!!)
                    .invoke(transaction, surface, task.method("isVisible").invoke(task))
            }
            task.method("scheduleAnimation").invoke(task)
            return true
        }
    }

    private fun Any.field(name: String): Field =
        hierarchy().mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }
            .firstOrNull()?.apply { isAccessible = true }
            ?: throw NoSuchFieldException("${javaClass.name}.$name")

    private fun Any.method(name: String, vararg types: Class<*>): Method =
        findMethod(name, *types) ?: throw NoSuchMethodException("${javaClass.name}.$name")

    private fun Any.findMethod(name: String, vararg types: Class<*>): Method? =
        hierarchy().flatMap { it.declaredMethods.asSequence() }.firstOrNull { method ->
            method.name == name && method.parameterTypes.size == types.size &&
                method.parameterTypes.indices.all { method.parameterTypes[it].isAssignableFrom(types[it]) }
        }?.apply { isAccessible = true }

    private fun Any.hierarchy(): Sequence<Class<*>> = generateSequence(javaClass) { it.superclass }
}
