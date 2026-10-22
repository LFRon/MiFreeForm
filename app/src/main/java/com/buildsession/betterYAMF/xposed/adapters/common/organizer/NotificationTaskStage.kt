package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import android.graphics.Rect

/** Native resource lease, not a window session or product mode. It can never be an application's
 * fullscreen restoration parent. A fresh per-launch cookie is carried separately by ActivityOptions.
 */
internal class NotificationTaskStage(val taskId: Int, val token: Any,
    val previous: Map<Int, PreviousTask>) {
    data class PreviousTask(val task: Any, val parentToken: Any?, val bounds: Rect, val mode: Int)
    var closed = false
}

/** Native facts captured together by Task.fillTaskInfo under WM's lock. Task IDs can be reused;
 * the delayed serial claim must still address this exact Task and its container token.
 */
internal class NotificationTaskSelection(val taskId: Int, val task: Any, val token: Any)
