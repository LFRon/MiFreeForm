package com.buildsession.betterYAMF.xposed.adapters.android17.notification

/** Identity of the server NotificationRecord, which LOS replaces on every update. Notification
 * key, post time and InstanceId alone are NOT update generations. The caller must hold NMS's
 * notification lock during both current() and the successful action, including queued work.
 */
internal class NotificationRecordLease(val key: String, private val expected: Any) {
    private var consumed = false
    fun consume(current: () -> Any?, replacementPending: () -> Boolean = { false }, action: (Any) -> Unit): Boolean {
        if (consumed) return false
        consumed = true
        if (current() !== expected || replacementPending()) return false
        action(expected)
        return true
    }
}
