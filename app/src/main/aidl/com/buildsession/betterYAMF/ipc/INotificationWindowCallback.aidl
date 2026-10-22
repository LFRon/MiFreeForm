package com.buildsession.betterYAMF.ipc;

/** Authenticated, optional SystemUI bridge. Results: 0=sent, 1=interactive frame committed,
 * -1=rejected before PendingIntent send, -2=failed after send, -3=superseded after send.
 * These are execution facts; 0 alone must never dismiss the notification.
 */
oneway interface INotificationWindowCallback {
    void onResult(long requestId, int result);
}
