package com.buildsession.betterYAMF.xposed.hook.system

/** Execution addresses, not product policy. A new peer epoch retires the former registration. */
internal class EntryPeerRegistry {
    data class Peer(val packageName: String, val userId: Int, val uid: Int, val epoch: Long,
        val asyncSnapshot: Boolean)
    private val peers = linkedMapOf<Pair<String, Int>, Peer>()
    @Synchronized fun register(packageName: String?, epoch: Long = 0L, userId: Int = 0,
        uid: Int = -1, asyncSnapshot: Boolean? = null): Boolean {
        if (packageName.isNullOrBlank() || packageName == "android" || userId < 0) return false
        val key = packageName to userId
        val previous = peers[key]
        if (previous == null || epoch > previous.epoch || epoch == previous.epoch) {
            peers[key] = Peer(packageName, userId, uid, epoch,
                asyncSnapshot ?: previous?.takeIf { it.epoch == epoch }?.asyncSnapshot ?: false)
        }
        // A stale process must not renegotiate capabilities over the current registration.
        if (previous != null && epoch < previous.epoch) return false
        return true
    }
    @Synchronized fun accepts(packageName: String?, epoch: Long, userId: Int = 0): Boolean {
        if (epoch == 0L) return true // Existing v2 peers carry operation-scoped handoff ids.
        return packageName != null && peers[packageName to userId]?.epoch == epoch
    }
    @Synchronized fun peer(packageName: String, userId: Int): Peer? = peers[packageName to userId]
    @Synchronized fun recipients(): List<Peer> = peers.values.toList()
}
