package com.buildsession.betterYAMF.xposed.adapters.android17.notification

import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import java.util.concurrent.atomic.AtomicLong

/** Private AD correlation metadata. NMS clones these scalar extras to its listener/SystemUI.
 * A fresh value is minted for EVERY NotificationRecord; never trust an app-provided value.
 * PendingIntent, flags, user and draft remain unchanged. No extra notification update is sent.
 */
internal object NotificationGeneration {
    private const val EXTRA = "com.buildsession.betterYAMF.notification_generation"
    private val sequence by lazy { AtomicLong(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)) }
    fun stamp(sbn: StatusBarNotification) {
        val n = sbn.notification
        n.extras = (n.extras?.let(::Bundle) ?: Bundle()).apply { putLong(EXTRA, sequence.getAndIncrement()) }
    }
    fun read(sbn: StatusBarNotification): Long = sbn.notification.extras?.getLong(EXTRA, 0L) ?: 0L
}
