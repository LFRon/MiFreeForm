package com.buildsession.betterYAMF.ipc;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.buildsession.betterYAMF.ipc.INotificationWindowCallback;

/** One-way only. Never blocks SystemUI or invokes a peer while the WM lock is held. */
oneway interface INotificationWindowBridge {
    void launch(long requestId, in StatusBarNotification notification, in PendingIntent original,
        in Intent fillInIntent, in Bundle nativeOptions, int rank, int count, INotificationWindowCallback callback);
    void cancel(long requestId);
}
