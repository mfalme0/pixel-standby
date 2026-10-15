package com.pixelstandby.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * MediaNotificationListener exists for one purpose only: to hold the
 * [android.permission.BIND_NOTIFICATION_LISTENER_SERVICE] permission so the app can
 * instantiate a [MediaSessionManager] and query active media sessions.
 *
 * It deliberately does NOT read notification content. The [onNotificationPosted] and
 * [onNotificationRemoved] callbacks are no-ops. All media discovery goes through
 * [MediaSessionManager.getActiveSessions], not through notifications.
 *
 * The user must grant notification access in Settings → Apps → Special app access →
 * Notification access → Pixel Standby. See [MediaSettings] for the check and the settings
 * screen.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Intentionally empty — we never read notification content.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Intentionally empty.
    }
}