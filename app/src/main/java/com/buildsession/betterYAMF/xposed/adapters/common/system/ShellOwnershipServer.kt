package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.xposed.utils.log
import com.buildsession.betterYAMF.xposed.utils.logWarning

/** system_server resource prerequisite. No WM lock is held across IPC, and no thread waits for
 * SystemUI. Marker acknowledgement precedes root creation; binder death revokes new entry.
 */
internal object ShellOwnershipServer {
    val cookie: IBinder = Binder()
    private class Peer(val binder: IBinder, val identity: ShellProcessIdentity, val death: IBinder.DeathRecipient)
    @Volatile private var acknowledged = false
    // isBinderAlive reads the proxy's liveness fact; it performs no synchronous remote request.
    val ready: Boolean get() = !invalidated && acknowledged && peer?.binder?.isBinderAlive == true
    /** Read one immutable peer snapshot. Keep protecting existing windows if availability was
     * withdrawn, but never recognize an unacknowledged/dead/replaced process by PID alone.
     */
    fun matchesCaller(uid: Int, pid: Int): Boolean {
        val current = peer ?: return false
        return current.identity.matches(uid, pid) && current.binder.isBinderAlive
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var application: Context? = null
    @Volatile private var peer: Peer? = null
    private var changed: ((Boolean) -> Unit)? = null
    @Volatile private var invalidated = false
    private var reportedRejection: String? = null
    private var negotiationFailure: Throwable? = null
    @Volatile private var invalidationFailure: Throwable? = null
    @Volatile private var invalidationReason: String? = null

    /** Cached native facts for query-time diagnostics. This performs no remote request. */
    fun readiness(): String {
        val current = peer
        return "initialized=${application != null} acknowledged=$acknowledged invalidated=$invalidated " +
            "peer=${current?.identity} alive=${current?.binder?.isBinderAlive == true} " +
            "issue=$reportedRejection invalidation=$invalidationReason"
    }
    fun failureCause(): Throwable? = invalidationFailure ?: negotiationFailure

    fun initialize(context: Context, onChanged: (Boolean) -> Unit) {
        changed = onChanged
        if (application != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    val sender = UserScopedBroadcasts.sender(context, this, ShellOwnershipProtocol.PACKAGE)
                        ?: return reject("sender package/UID/permission could not be verified")
                    val actualUid = UserScopedBroadcasts.senderUid(this)
                    if (!ShellProcessIdentity.acceptsSender(sender.userId,
                            UserScopedBroadcasts.senderPackage(this), actualUid, sender.uid))
                        return reject("sender identity mismatch: actualUid=$actualUid expectedUid=${sender.uid} user=${sender.userId}")
                    val version = intent.getIntExtra(ShellOwnershipProtocol.VERSION, 0)
                    val build = intent.getLongExtra(ShellOwnershipProtocol.BUILD, 0L)
                    if (version != ShellOwnershipProtocol.CURRENT || build != BuildConfig.BUILD_TIME)
                        return reject("peer version/build mismatch: version=$version build=$build expectedBuild=${BuildConfig.BUILD_TIME}")
                    when (intent.action) {
                        ShellOwnershipProtocol.QUERY -> announce()
                        ShellOwnershipProtocol.READY -> {
                            if (invalidated) return
                            if (intent.extras?.getBinder(ShellOwnershipProtocol.COOKIE) != cookie)
                                return reject("stale owner cookie")
                            val next = intent.extras?.getBinder(ShellOwnershipProtocol.PEER)
                                ?: return reject("missing peer lifetime Binder")
                            val pid = intent.getIntExtra(ShellOwnershipProtocol.PID, -1)
                            if (pid <= 0 || pid == android.os.Process.myPid()) return reject("invalid Shell pid=$pid")
                            val identity = ShellProcessIdentity(sender.uid, pid)
                            val previous = peer
                            if (previous != null && next == previous.binder && identity != previous.identity)
                                return reject("peer lifetime Binder changed its process address")
                            if (!intent.getBooleanExtra(ShellOwnershipProtocol.AVAILABLE, false)) {
                                if (previous == null || next == previous.binder) {
                                    val failure = intent.getStringExtra(ShellOwnershipProtocol.FAILURE)?.take(2048)
                                    reject("peer isolation unavailable: uid=${identity.uid} pid=${identity.pid} reason=${failure ?: "not supplied"}")
                                }
                                if (next == previous?.binder && acknowledged) {
                                    acknowledged = false
                                    logWarning(TAG, "Shell isolation withdrawn: uid=${identity.uid} pid=${identity.pid}")
                                    changed?.invoke(false)
                                }
                                return
                            }
                            if (next == previous?.binder && ready) return
                            lateinit var registration: Peer
                            val listener = IBinder.DeathRecipient {
                                main.post {
                                    if (peer === registration) {
                                        peer = null; acknowledged = false
                                        reportedRejection = "authenticated peer died: ${registration.identity}"
                                        logWarning(TAG, "Shell owner died; entry suspended until isolation is acknowledged again")
                                        changed?.invoke(false)
                                    }
                                }
                            }
                            registration = Peer(next, identity, listener)
                            next.linkToDeath(listener, 0)
                            if (!next.isBinderAlive) { next.unlinkToDeath(listener, 0); return }
                            previous?.let { runCatching { it.binder.unlinkToDeath(it.death, 0) } }
                            peer = registration; acknowledged = true
                            reportedRejection = null
                            negotiationFailure = null
                            log(TAG) { "Shell isolation acknowledged uid=${identity.uid} pid=${identity.pid} build=${BuildConfig.BUILD_TIME}" }
                            changed?.invoke(true)
                        }
                    }
                }.onFailure {
                    negotiationFailure = it
                    reportedRejection = "negotiation failed: ${it.javaClass.name}: ${it.message}"
                    logWarning(TAG, "Shell ownership negotiation failed", it)
                }
            }
        }
        UserScopedBroadcasts.registerSystemReceiver(context, receiver, IntentFilter().apply {
            addAction(ShellOwnershipProtocol.QUERY); addAction(ShellOwnershipProtocol.READY)
        })
        application = context
        announce()
    }

    private fun announce() {
        val context = application ?: return
        UserScopedBroadcasts.sendIdentifiedPrivileged(context, Intent(ShellOwnershipProtocol.OWNER)
            .setPackage(ShellOwnershipProtocol.PACKAGE)
            .putExtra(ShellOwnershipProtocol.VERSION, ShellOwnershipProtocol.CURRENT)
            .putExtra(ShellOwnershipProtocol.BUILD, BuildConfig.BUILD_TIME)
            .putExtras(Bundle().apply { putBinder(ShellOwnershipProtocol.COOKIE, cookie) }))
    }

    private fun reject(reason: String) {
        if (reportedRejection == reason) return
        reportedRejection = reason
        logWarning(TAG, "Shell ownership rejected: $reason")
    }

    fun invalidate(reason: String, error: Throwable) {
        if (invalidated) return
        invalidationReason = reason
        invalidationFailure = error
        invalidated = true
        acknowledged = false
        logWarning(TAG, reason, error)
        main.post { changed?.invoke(false) }
    }

    private const val TAG = "reYAMF_Shell"
}
