package com.buildsession.betterYAMF.xposed.adapters.common.launcher

import java.lang.reflect.Method

/** Native state completion. The Android + Launcher unit explicitly chooses the playback ABI. */
internal class LauncherSourceRetirement private constructor(
    private val stateManager: Any, private val api: Api
) {
    enum class Signature { TWO_ARGUMENTS, THREE_ARGUMENTS }

    fun finish() {
        val rest = requireNotNull(api.restState.invoke(stateManager))
        // LoS StateManager:367 delegates the two-arg method to (state, duration, 0).
        // Pixel Launcher 17/907 retains that three-arg method in DEX. A positive duration is
        // only a fraction normalizer; no animation clock is started. Preserve native handlers
        // and transition completion, including the same-state cancellation case.
        val playback = requireNotNull(if (api.signature == Signature.TWO_ARGUMENTS)
            api.create.invoke(stateManager, rest, 1L) else api.create.invoke(stateManager, rest, 1L, 0))
        api.start.invoke(playback)
        api.reapply.invoke(stateManager, true)
    }

    class Api internal constructor(type: Class<*>, internal val signature: Signature) {
        internal val restState: Method = type.getMethod("getRestState").accessible()
        internal val create: Method = type.methods.single { method ->
            method.name == "createAnimationToNewWorkspace" &&
                method.parameterCount == (if (signature == Signature.TWO_ARGUMENTS) 2 else 3) &&
                method.parameterTypes[0].isAssignableFrom(restState.returnType) &&
                method.parameterTypes[1] == Long::class.javaPrimitiveType &&
                (signature == Signature.TWO_ARGUMENTS || method.parameterTypes[2] == Int::class.javaPrimitiveType)
        }.accessible()
        internal val start: Method = create.returnType.getMethod("dispatchOnStart").accessible()
        internal val reapply: Method = type.getMethod("reapplyState", Boolean::class.javaPrimitiveType).accessible()
        init {
            // LoS returns this for fluent dispatch; Pixel's R8 build removes the unused result.
            // Both dispatch the same native start listeners. This adapter ignores that result.
            check(start.returnType == Void.TYPE || start.returnType == create.returnType) {
                "Unreviewed dispatchOnStart return type: ${start.returnType.name}"
            }
            check(reapply.returnType == Void.TYPE)
        }
        fun bind(manager: Any): LauncherSourceRetirement {
            check(restState.declaringClass.isInstance(manager))
            checkNotNull(restState.invoke(manager))
            return LauncherSourceRetirement(manager, this)
        }
    }

    companion object {
        fun resolve(type: Class<*>, signature: Signature): Api = Api(type, signature)
        /** Compatibility entry for fixtures; production resolves the selected ABI at initialization. */
        fun probe(stateManager: Any, signature: Signature = Signature.TWO_ARGUMENTS): LauncherSourceRetirement? =
            runCatching { resolve(stateManager.javaClass, signature).bind(stateManager) }.getOrNull()
        private fun Method.accessible(): Method = apply { isAccessible = true }
    }
}
