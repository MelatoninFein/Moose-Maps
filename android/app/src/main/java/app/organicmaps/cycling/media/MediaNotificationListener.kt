package app.organicmaps.cycling.media

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Deliberately does nothing.
 *
 * `MediaSessionManager.getActiveSessions` is gated behind notification-listener access, and the only
 * way to hold that access is to declare a [NotificationListenerService]. This app never reads a
 * notification: it overrides nothing, so every posted notification is ignored, and the registration
 * exists purely to unlock the media-session API.
 *
 * The user has to enable it by hand in system settings - see [isEnabled] and [settingsIntentAction].
 */
class MediaNotificationListener : NotificationListenerService() {

    companion object {

        /** The system screen listing apps that may read notifications. */
        const val settingsIntentAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS

        private const val ENABLED_LISTENERS_SETTING = "enabled_notification_listeners"

        @JvmStatic
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, MediaNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS_SETTING)
                ?: return false
            // The setting is a colon-separated list of flattened component names.
            return enabled.split(':').any {
                val parsed = ComponentName.unflattenFromString(it)
                parsed == component
            }
        }

        @JvmStatic
        fun componentName(context: Context) = ComponentName(context, MediaNotificationListener::class.java)
    }
}
