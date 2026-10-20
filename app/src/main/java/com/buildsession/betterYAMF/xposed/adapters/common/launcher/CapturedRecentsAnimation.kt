package com.buildsession.betterYAMF.xposed.adapters.common.launcher

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

    class Api private constructor(private val type: Class<*>, private val normal: Invocation,
        private val forced: Invocation?) {
        val canForceFinish: Boolean get() = forced != null
        fun bind(controller: Any): CapturedRecentsAnimation {
            check(type.isInstance(controller))
            return CapturedRecentsAnimation(controller, normal, forced)
        }

        companion object {
            internal fun resolve(type: Class<*>): Api? {
                val bool = Boolean::class.javaPrimitiveType
                val methods = type.methods.filter {
                    it.returnType == Void.TYPE && (it.name == "finishController" || it.name == "finish")
                }.sortedWith(compareBy<Method> { it.name != "finishController" }.thenByDescending { it.parameterCount })

                fun invocation(force: Boolean): Invocation? {
                    for (method in methods) {
                        val p = method.parameterTypes
                        val base = if (force) 4 else 3
                        if (p.size !in base..base + 1 || p[0] != bool ||
                            p[1] != Runnable::class.java || p[2] != bool || (force && p[3] != bool)) continue
                        if (p.size > base && p.last() == bool) continue
                        // Resolve the logging constructor without initializing APK classes. Neither
                        // a native controller nor a logging instance is cached across handoffs.
                        val reason = if (p.size > base) reasonFactory(p.last()) ?: continue else null
                        method.isAccessible = true
                        return Invocation(method) { callback ->
                            mutableListOf<Any?>(true, callback, false).apply {
                                if (force) add(true)
                                if (reason != null) add(reason())
                            }.toTypedArray()
                        }
                    }
                    return null
                }

                return Api(type, invocation(force = false) ?: return null, invocation(force = true))
            }
        }
    }

    fun finish(callback: Runnable) = normal.run(controller, callback)

    fun forceFinish(callback: Runnable) {
        checkNotNull(forced) { "Captured recents controller has no force-finish capability" }
            .run(controller, callback)
    }

    companion object {
        fun resolve(type: Class<*>): Api? = Api.resolve(type)
        fun probe(controller: Any?): CapturedRecentsAnimation? =
            controller?.let { resolve(it.javaClass)?.bind(it) }

        private fun reasonFactory(type: Class<*>): (() -> Any)? = runCatching {
            val message = "MiWindow recents handoff"
            val single = type.declaredConstructors.firstOrNull {
                it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (single != null) {
                single.isAccessible = true
                val create: () -> Any = { single.newInstance(message) }
                create
            }
            else {
                // LoS 24 / the connected phone: CompoundString(String, Object...), including the
                // zero-format-argument call sites that look like a single argument in Java source.
                val constructor = type.getDeclaredConstructor(String::class.java, Array<Any>::class.java)
                    .apply { isAccessible = true }
                val create: () -> Any = { constructor.newInstance(message, emptyArray<Any>()) }
                create
            }
        }.getOrNull()
    }
}
