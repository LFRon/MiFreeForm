package com.buildsession.betterYAMF.xposed.services

import android.app.ActivityManagerHidden
import android.content.AttributionSource
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.common.ipc.ServiceConnectionProtocol
import com.buildsession.betterYAMF.xposed.adapters.common.system.UserScopedBroadcasts
import com.buildsession.betterYAMF.xposed.utils.logWarning
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.adapter.UidObserverAdapter
import java.util.concurrent.ConcurrentHashMap

/** Event-driven binding for app settings/window commands, not an activation detector.
 * Provider IPC leaves AM/WM/main threads and always releases its external reference.
 */
object UserService {
    const val PROVIDER_AUTHORITY = "com.buildsession.betterYAMF.ServiceProvider"
    private const val TAG = "reYAMFUserService"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker by lazy { Handler(HandlerThread("BetterYAMF-AppBridge").apply { start() }.looper) }
    private val appUids = ConcurrentHashMap.newKeySet<Int>()
    private val pending = mutableSetOf<Int>() // protected by pending
    private val scheduled = mutableSetOf<Int>()
    private lateinit var removeExternal: java.lang.reflect.Method
    private lateinit var activityManager: Any

    fun register(context: Context, activityManager: Any) {
        this.activityManager = activityManager
        removeExternal = activityManager.javaClass.getMethod("removeContentProviderExternalAsUser",
            String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
        val isUidActive = activityManager.javaClass.getMethod("isUidActive",
            Int::class.javaPrimitiveType, String::class.java)
        runCatching { context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid }
            .getOrNull()?.let(appUids::add)
        UserScopedBroadcasts.registerSystemReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    val uid = UserScopedBroadcasts.senderUid(this)
                    if (uid < 0 || UserScopedBroadcasts.senderPackage(this) != BuildConfig.APPLICATION_ID ||
                        context.packageManager.getPackagesForUid(uid)?.contains(BuildConfig.APPLICATION_ID) != true) return
                    val user = UserScopedBroadcasts.deliveryUser(this)
                    if (user != UserScopedBroadcasts.userId(uid)) return
                    appUids.add(uid)
                    enqueue(user)
                }.onFailure { logWarning(TAG, "App service request rejected", it) }
            }
        }, IntentFilter(ServiceConnectionProtocol.REQUEST), senderPermission = null)
        ActivityManagerApis.registerUidObserver(object : UidObserverAdapter() {
            override fun onUidActive(uid: Int) {
                if (uid !in appUids) return
                main.post {
                    if (context.packageManager.getPackagesForUid(uid)?.contains(BuildConfig.APPLICATION_ID) != true) {
                        appUids.remove(uid)
                        return@post
                    }
                    enqueue(UserScopedBroadcasts.userId(uid))
                }
            }
        }, ActivityManagerHidden.UID_OBSERVER_ACTIVE, ActivityManagerHidden.PROCESS_STATE_UNKNOWN, null)
        // Observe before sampling: registration does not replay an already-active UID.
        appUids.forEach { uid ->
            if (isUidActive.invoke(activityManager, uid, "android") == true)
                enqueue(UserScopedBroadcasts.userId(uid))
        }
    }

    private fun enqueue(userId: Int) {
        synchronized(pending) {
            pending.add(userId)
            if (scheduled.add(userId)) worker.post { deliver(userId) }
        }
    }

    private fun deliver(userId: Int) {
        synchronized(pending) { pending.remove(userId) }
        publish(userId)
        synchronized(pending) {
            if (pending.contains(userId)) worker.post { deliver(userId) }
            else scheduled.remove(userId)
        }
    }

    private fun publish(userId: Int) {
        val token = Binder()
        var acquired = false
        try {
            val provider = ActivityManagerApis.getContentProviderExternal(PROVIDER_AUTHORITY, userId,
                token, "BetterYAMF app binding") ?: return
            acquired = true
            val attribution = AttributionSource.Builder(android.os.Process.SYSTEM_UID).setPackageName("android").build()
            val payload = Bundle().apply { putBinder("binder", YAMFManager) }
            if (provider.call(attribution, PROVIDER_AUTHORITY, "", null, payload) == null)
                logWarning(TAG, "App rejected the service binding for user $userId")
        } catch (error: Throwable) {
            logWarning(TAG, "Cannot deliver app service for user $userId", error)
        } finally {
            if (acquired) runCatching { removeExternal.invoke(activityManager, PROVIDER_AUTHORITY, token, userId) }
                .onFailure { logWarning(TAG, "Cannot release external app provider for user $userId", it) }
        }
    }
}
