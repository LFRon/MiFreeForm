package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import com.buildsession.betterYAMF.window.api.WindowRect

/** Viewport and focus suppression owned by one native adoption. A migrated surface is already
 * revoked by WM. Clean each override only while its native identity and value still match;
 * another Task object which reused the ID is never ours. Native mode 5 is FREEFORM.
 */
internal class TaskOverrideLease(val task: Any, var viewport: WindowRect) {
    /** Only true after this adoption suppressed an otherwise-focusable native container. */
    var focusSuppressed = false
    fun canRestoreFocus(currentTask: Any, currentFocusable: Boolean): Boolean =
        focusSuppressed && currentTask === task && !currentFocusable
    data class Restoration(val clearFreeformMode: Boolean)
    fun restoration(currentTask: Any, currentBounds: WindowRect, overrideMode: Int): Restoration? =
        if (currentTask === task && currentBounds == viewport) Restoration(overrideMode == 5) else null
}
