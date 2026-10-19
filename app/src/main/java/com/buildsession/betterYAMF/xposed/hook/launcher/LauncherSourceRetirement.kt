package com.buildsession.betterYAMF.xposed.hook.launcher

import java.lang.reflect.Method

/** Native Launcher state completion; no View properties or private state fields are patched. */
internal class LauncherSourceRetirement private constructor(
    private val stateManager: Any,
    private val restState: Method,
    private val createPlayback: Method,
    private val start: Method,
    private val reapply: Method
) {
    fun finish() {
        val rest = requireNotNull(restState.invoke(stateManager))
        // LoS StateManager:258-280 can skip a same-state goToState after a cancellation, even
        // while stable state, blur, alpha and the Activity transition flag still disagree.
        // Establish an owned native transition, then reapplyState(true) (StateManager:213-238)
        // cancels both the playback and independent state-element animations, applies every
        // state handler, and dispatches transition completion even for that same-state case.
        // 1 is only a positive fraction normalizer: AnimatorPlaybackController.Holder divides
        // by globalDuration. No player/clock is started.
        val playback = requireNotNull(createPlayback.invoke(stateManager, rest, 1L))
        start.invoke(playback)
        reapply.invoke(stateManager, true)
    }

    companion object {
        /** Probe the public StateManager/playback signatures before intercepting the handoff. */
        fun probe(stateManager: Any): LauncherSourceRetirement? = runCatching {
            val methods = stateManager.javaClass.methods
            val rest = methods.firstOrNull { it.name == "getRestState" && it.parameterCount == 0 }
                ?.accessible() ?: return null
            val state = rest.invoke(stateManager) ?: return null
            val create = methods.firstOrNull {
                it.name == "createAnimationToNewWorkspace" && it.parameterCount == 2 &&
                    it.parameterTypes[0].isInstance(state) &&
                    it.parameterTypes[1] == Long::class.javaPrimitiveType
            }?.accessible() ?: return null
            val playback = create.returnType
            val start = playback.getMethod("dispatchOnStart").accessible()
            val reapply = methods.firstOrNull {
                it.name == "reapplyState" && it.parameterTypes.contentEquals(
                    arrayOf(Boolean::class.javaPrimitiveType))
            }?.accessible() ?: return null
            LauncherSourceRetirement(stateManager, rest, create, start, reapply)
        }.getOrNull()

        private fun Method.accessible(): Method = apply { isAccessible = true }
    }
}
