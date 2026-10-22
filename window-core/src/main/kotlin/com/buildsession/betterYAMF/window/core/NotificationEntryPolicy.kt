package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.NotificationEntryFacts

object NotificationEntryPolicy {
    /** Rechecked immediately before launch if the display changed after the original click. */
    fun acceptsDisplay(landscape: Boolean): Boolean = landscape

    fun accepts(f: NotificationEntryFacts): Boolean = acceptsDisplay(f.landscape) && f.activityIntent && f.clearable &&
        !f.ongoing && !f.bubble && !f.fullScreenIntent && !f.groupSummary && f.profileUnlocked && !f.systemInteraction
}
