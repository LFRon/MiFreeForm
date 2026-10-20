package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import com.buildsession.betterYAMF.xposed.utils.TRUSTED_SENDER_PERMISSION

/** Android transport addresses. The Launcher's user is independent of the app task's user. */
internal object UserScopedBroadcasts {
    data class Sender(val packageName: String, val userId: Int, val uid: Int)

    private val userOf by lazy { UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType) }
    private val sendingUser by lazy { BroadcastReceiver::class.java.getMethod("getSendingUserId") }
    private val sentFromUid by lazy { runCatching { BroadcastReceiver::class.java.getMethod("getSentFromUid") }.getOrNull() }
    private val packageUid by lazy { PackageManager::class.java.getMethod("getPackageUidAsUser",
        String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
    private val send by lazy { Context::class.java.getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java) }
    private val register by lazy { Context::class.java.getMethod("registerReceiverAsUser",
        BroadcastReceiver::class.java, UserHandle::class.java, IntentFilter::class.java,
        String::class.java, Handler::class.java, Int::class.javaPrimitiveType) }

    fun deliveryUser(receiver: BroadcastReceiver): Int =
        (sendingUser.invoke(receiver) as Int).also { require(it >= 0) { "Missing broadcast delivery user" } }

    /** The receiver's permission gate is retained on older platforms which do not share sender UID. */
    fun sender(context: Context, receiver: BroadcastReceiver, packageName: String?): Sender? {
        if (packageName.isNullOrBlank() || packageName == "android") return null
        val user = deliveryUser(receiver)
        val uid = packageUid.invoke(context.packageManager, packageName, 0, user) as Int
        val actual = sentFromUid?.invoke(receiver) as? Int ?: Process.INVALID_UID
        if (actual >= 0 && actual != uid) return null
        if (context.checkPermission(TRUSTED_SENDER_PERMISSION, -1, uid) != PackageManager.PERMISSION_GRANTED)
            return null
        return Sender(packageName, user, uid)
    }

    fun send(context: Context, intent: Intent, userId: Int) {
        require(userId >= 0) { "Broadcast replies must target one known user" }
        send.invoke(context, intent, userOf.invoke(null, userId))
    }

    /** System commands are received for their real delivery user, retaining sender permission. */
    fun registerSystemReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        check(Process.myUid() == Process.SYSTEM_UID)
        register.invoke(context, receiver, userOf.invoke(null, -1), filter,
            TRUSTED_SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
    }
}
