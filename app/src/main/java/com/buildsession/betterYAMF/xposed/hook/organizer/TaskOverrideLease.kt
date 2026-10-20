package com.buildsession.betterYAMF.xposed.hook.organizer

import com.buildsession.betterYAMF.window.api.WindowRect

/** Ownership of the fixed viewport written by one native adoption. A migrated task surface is
 * already revoked by WM. Only a still-matching layout override may be removed, never a new
 * owner's layout or another Task object which reused the ID. Native mode 5 is FREEFORM.
 */
internal class TaskOverrideLease(val task: Any, val viewport: WindowRect) {
    data class Restoration(val clearFreeformMode: Boolean)
    fun restoration(currentTask: Any, currentBounds: WindowRect, overrideMode: Int): Restoration? =
        if (currentTask === task && currentBounds == viewport) Restoration(overrideMode == 5) else null
}
