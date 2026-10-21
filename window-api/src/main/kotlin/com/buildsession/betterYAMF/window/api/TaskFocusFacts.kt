package com.buildsession.betterYAMF.window.api

/** Automatic top-task selection is distinct from a direct tap/explicit task activation. */
enum class TaskFocusOrigin { AUTOMATIC, DIRECT }

/** Current native ownership of ONE candidate, not a cached task id or a window's visual order.
 * AD reads this fact under the platform's task-selection lock for every eligible candidate.
 * A shared container and all of its window tasks count as owned, including pending adoption;
 * an externally migrated task no longer counts once native ownership has been relinquished.
 */
data class TaskFocusFacts(val origin: TaskFocusOrigin, val windowOwned: Boolean)
