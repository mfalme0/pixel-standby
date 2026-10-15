package com.pixelstandby.media

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Checks whether notification access has been granted to this app.
 *
 * Notification access is required for MediaNotificationListener to hold the
 * BIND_NOTIFICATION_LISTENER_SERVICE permission, which in turn lets us use
 * MediaSessionManager.getActiveSessions.
 */
object NotificationAccess {

    fun isEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledListeners != null && enabledListeners.contains(packageName)
    }
}