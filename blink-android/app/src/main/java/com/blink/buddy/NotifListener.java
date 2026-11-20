package com.blink.buddy;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Only notices THAT a new notification arrived, so Blink can carry an envelope.
 * It never reads titles, text or senders, and stores nothing.
 */
public class NotifListener extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.isOngoing()) return;
        if (getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bus.notificationPosted();
    }
}
