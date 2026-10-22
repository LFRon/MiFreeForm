package com.buildsession.betterYAMF.window.api

/** Current click facts after native unlock checks. No notification arrival event launches a
 * window. Native-specific flags/categories are normalized by the input adapter.
 */
data class NotificationEntryFacts(
    val landscape: Boolean, val activityIntent: Boolean, val clearable: Boolean,
    val ongoing: Boolean, val bubble: Boolean, val fullScreenIntent: Boolean,
    val groupSummary: Boolean, val profileUnlocked: Boolean,
    val systemInteraction: Boolean
)
