package com.buildsession.betterYAMF.xposed.adapters.common.launcher

/** Local native leases, confined to the input thread. A service/component replacement invalidates
 * every former DOWN; an ignored DOWN cannot reuse a previous consumer's running swipe handler.
 * These are resource identities, not another entry-policy state machine.
 */
internal class LauncherInputOwnership {
    class Down internal constructor(internal val owner: Any, internal val previousGesture: Any?)
    private var owner: Any? = null
    private var pending: Down? = null
    private var gesture: Any? = null

    fun bind(owner: Any) { this.owner = owner; clearGesture() }
    fun accepts(owner: Any): Boolean = this.owner === owner
    fun beforeDown(owner: Any, previousGesture: Any?): Down? {
        if (!accepts(owner)) return null
        clearGesture()
        return Down(owner, previousGesture).also { pending = it }
    }
    fun afterDown(down: Down, currentGesture: Any?, consumerGesture: Any?): Boolean {
        if (!accepts(down.owner) || pending !== down) return false
        pending = null
        if (currentGesture == null || currentGesture === down.previousGesture ||
            currentGesture !== consumerGesture) return false
        gesture = currentGesture
        return true
    }
    fun owns(owner: Any, currentGesture: Any?, consumerGesture: Any?): Boolean =
        accepts(owner) && gesture != null && gesture === currentGesture && gesture === consumerGesture

    fun clearGesture() { pending = null; gesture = null }
    fun close() { owner = null; clearGesture() }
}
