package com.buildsession.betterYAMF.xposed.adapters.common.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.BroadcastOptions
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import com.buildsession.betterYAMF.xposed.utils.TRUSTED_SENDER_PERMISSION

/** Android transport addresses. The Launcher's user is independent of the app task's user. */
internal object UserScopedBroadcasts {
    data class Sender(val packageName: String, val userId: Int, val uid: Int)

    private val userOf by lazy { UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType) }
    private val userIdOf by lazy { UserHandle::class.java.getMethod("getUserId", Int::class.javaPrimitiveType) }
    private val sendingUser by lazy { BroadcastReceiver::class.java.getMethod("getSendingUserId") }
    private val sentFromUid by lazy { runCatching { BroadcastReceiver::class.java.getMethod("getSentFromUid") }.getOrNull() }
    private val sentFromPackage by lazy { BroadcastReceiver::class.java.getMethod("getSentFromPackage") }
    private val packageUid by lazy { PackageManager::class.java.getMethod("getPackageUidAsUser",
        String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
    private val send by lazy { Context::class.java.getMethod("sendBroadcastAsUser", Intent::class.java, UserHandle::class.java) }
    private val identifiedSend by lazy { Context::class.java.getMethod("sendBroadcastAsUser", Intent::class.java,
        UserHandle::class.java, String::class.java, Bundle::class.java) }
    private val register by lazy { Context::class.java.getMethod("registerReceiverAsUser",
        BroadcastReceiver::class.java, UserHandle::class.java, IntentFilter::class.java,
        String::class.java, Handler::class.java, Int::class.javaPrimitiveType) }

    fun deliveryUser(receiver: BroadcastReceiver): Int =
        (sendingUser.invoke(receiver) as Int).also { require(it >= 0) { "Missing broadcast delivery user" } }

    fun userId(uid: Int): Int = userIdOf.invoke(null, uid) as Int

    fun senderUid(receiver: BroadcastReceiver): Int =
        sentFromUid?.invoke(receiver) as? Int ?: Process.INVALID_UID

    /** Only for messages sent by system_server, never for replies from SystemUI. */
    fun isSystemSender(receiver: BroadcastReceiver): Boolean = senderUid(receiver) == Process.SYSTEM_UID

    fun senderPackage(receiver: BroadcastReceiver): String? = sentFromPackage.invoke(receiver) as? String

    /** New privileged handshakes require an actual sender UID; do not weaken Launcher v2's
     * backwards-compatible receiver to add this capability. The reply always addresses user 0.
     */
    @android.annotation.SuppressLint("NewApi") // Only selected by the reviewed SDK-37 mechanism.
    fun sendIdentifiedPrivileged(context: Context, intent: Intent) {
        // SystemUI has its own PM-assigned UID. The receiver authenticates the actual package/UID;
        // sharing identity does not grant the sender system_server's identity or permissions.
        check(context.checkSelfPermission(TRUSTED_SENDER_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Identified broadcast permission missing for uid=${Process.myUid()}"
        }
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        identifiedSend.invoke(context, intent, userOf.invoke(null, 0), TRUSTED_SENDER_PERMISSION,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

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
    fun registerSystemReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter,
        senderPermission: String? = TRUSTED_SENDER_PERMISSION) {
        check(Process.myUid() == Process.SYSTEM_UID)
        register.invoke(context, receiver, userOf.invoke(null, -1), filter,
            senderPermission, null, Context.RECEIVER_EXPORTED)
    }
}
