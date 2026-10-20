package com.buildsession.betterYAMF.xposed.hook.system

import android.content.Intent
import android.os.SystemClock
import com.buildsession.betterYAMF.common.ipc.Protocol

/** Optional extensions to v2. Native buffers still travel through the existing one-shot Binder. */
internal object EntryProtocolIdentity {
    val epoch = SystemClock.elapsedRealtimeNanos()
    val peers = EntryPeerRegistry()

    fun acceptsDestination(intent: Intent, userId: Int): Boolean {
        val destination = intent.getLongExtra(Protocol.EXTRA_DESTINATION_EPOCH, 0L)
        return (destination == 0L || destination == epoch) && peers.accepts(
            intent.getStringExtra(Protocol.EXTRA_SOURCE_PACKAGE), intent.getLongExtra(Protocol.EXTRA_SOURCE_EPOCH, 0L), userId)
    }
}
