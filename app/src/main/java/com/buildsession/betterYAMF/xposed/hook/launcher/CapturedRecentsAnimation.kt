package com.buildsession.betterYAMF.xposed.hook.launcher

import java.lang.reflect.Method

/**
 * Capability probe for the captured controller, independent of RecentsView's mutable reference.
 * LoS 24 RecentsAnimationController:135-181 reports completion through the native result receiver.
 * Older Launcher generations expose the same finish operation without the logging reason.
 */
internal class CapturedRecentsAnimation private constructor(
    val controller: Any,
    private val normal: Invocation,
    private val forced: Invocation?
) {
    private class Invocation(val method: Method, val arguments: (Runnable) -> Array<Any?>) {
        fun run(controller: Any, callback: Runnable) {
            method.invoke(controller, *arguments(callback))
        }
    }

    fun finish(callback: Runnable) = normal.run(controller, callback)

    fun forceFinish(callback: Runnable) {
        checkNotNull(forced) { "Captured recents controller has no force-finish capability" }
            .run(controller, callback)
    }

    companion object {
        fun probe(controller: Any?): CapturedRecentsAnimation? {
            controller ?: return null
            val bool = Boolean::class.javaPrimitiveType
            val methods = controller.javaClass.methods.filter {
                it.name == "finishController" || it.name == "finish"
            }.sortedWith(compareBy<Method> { it.name != "finishController" }.thenByDescending { it.parameterCount })

            fun invocation(force: Boolean): Invocation? {
                for (method in methods) {
                    val p = method.parameterTypes
                    val base = if (force) 4 else 3
                    if (p.size !in base..base + 1 || p[0] != bool ||
                        p[1] != Runnable::class.java || p[2] != bool || (force && p[3] != bool)) continue
                    // The optional final parameter is a logging reason, never a platform handle.
                    if (p.size > base && p.last() == bool) continue
                    val reason = if (p.size > base) createReason(p.last()) ?: continue else null
                    method.isAccessible = true
                    return Invocation(method) { callback ->
                        mutableListOf<Any?>(true, callback, false).apply {
                            if (force) add(true)
                            if (p.size > base) add(reason)
                        }.toTypedArray()
                    }
                }
                return null
            }

            val normal = invocation(force = false) ?: return null
            return CapturedRecentsAnimation(controller, normal, invocation(force = true))
        }

        private fun createReason(type: Class<*>): Any? = runCatching {
            val message = "MiWindow recents handoff"
            val single = type.declaredConstructors.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (single != null) single.apply { isAccessible = true }.newInstance(message)
            else {
                // LoS 24 / the connected phone: CompoundString(String, Object...), including the
                // zero-format-argument call sites that look like a single argument in Java source.
                type.getDeclaredConstructor(String::class.java, Array<Any>::class.java)
                    .apply { isAccessible = true }.newInstance(message, emptyArray<Any>())
            }
        }.getOrNull()
    }
}
