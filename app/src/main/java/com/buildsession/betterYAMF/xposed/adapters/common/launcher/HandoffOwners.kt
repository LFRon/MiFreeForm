package com.buildsession.betterYAMF.xposed.adapters.common.launcher

/**
 * Main-thread ownership of platform resources/callbacks. It carries no window geometry or mode.
 * A callback may touch shared Launcher views only while its exact owner is still registered.
 */
internal class HandoffOwners<T> {
    class Owner<T> internal constructor(val displayId: Int, val value: T)
    private val byDisplay = mutableMapOf<Int, Owner<T>>()

    fun register(displayId: Int, value: T): Owner<T> {
        check(displayId !in byDisplay) { "Previous handoff must be released first" }
        return Owner(displayId, value).also { byDisplay[displayId] = it }
    }

    fun owns(owner: Owner<T>): Boolean = byDisplay[owner.displayId] === owner

    fun current(displayId: Int): Owner<T>? = byDisplay[displayId]

    fun take(owner: Owner<T>): T? = if (owns(owner)) {
        byDisplay.remove(owner.displayId)
        owner.value
    } else null

    fun invalidate(displayId: Int): T? = byDisplay.remove(displayId)?.value
    fun all(): List<Owner<T>> = byDisplay.values.toList()
}
