package com.buildsession.betterYAMF.xposed.adapters.common.organizer

import java.util.Collections
import java.util.IdentityHashMap

/** Copies created during one native organizer registration. A kept remote result transfers to
 * Binder; excluded/local/failed results are released here. Equality never substitutes for handle
 * identity. An uncertain foreign return retains its references rather than revoking a borrower.
 */
internal class RegistrationLeases<T : Any>(private val release: (T) -> Unit) : AutoCloseable {
    private val owned = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    fun record(value: T) { owned.add(value) }
    fun transfer(value: T) { owned.remove(value) }
    fun discard(value: T) { if (owned.remove(value)) release(value) }
    fun preserveUncertainReturn() { owned.clear() }
    override fun close() {
        val remaining = owned.toList()
        owned.clear()
        var failure: Throwable? = null
        for (value in remaining) try { release(value) } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
