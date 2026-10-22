package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.TaskFocusFacts
import com.buildsession.betterYAMF.window.api.TaskFocusOrigin
import com.buildsession.betterYAMF.window.api.EntryOrigin

/** Cross-window task rules. Visual stacking never grants automatic global input focus. */
object WindowSessionPolicy {
    /** One visible host per task; replacement transfers ownership without fullscreen release. */
    fun replaces(requestedTaskId: Int, hostedTaskId: Int): Boolean =
        requestedTaskId > 0 && requestedTaskId == hostedTaskId

    /** Skip every owned window during automatic selection, rather than merely the top window.
     * Direct interaction remains eligible; the platform still checks actual input permission.
     */
    fun acceptsTaskFocus(facts: TaskFocusFacts): Boolean =
        facts.origin == TaskFocusOrigin.DIRECT || !facts.windowOwned

    /** Notification windows must never replace the foreground app with fullscreen on failure. */
    fun restoreToFrontOnFailure(origin: EntryOrigin): Boolean = when (origin) {
        EntryOrigin.RECENTS -> true
        EntryOrigin.NOTIFICATION -> false
    }
}
