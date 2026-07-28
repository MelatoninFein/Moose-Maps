package app.organicmaps.cycling.sensors

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import app.organicmaps.MwmActivity
import app.organicmaps.R
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.sdk.util.log.Logger

/**
 * Keeps sensor connections alive while the app is in the background.
 *
 * A ride outlasts the map activity: the rider locks the screen, switches to a music app, or drops
 * the map into picture-in-picture. Without a foreground service Android would freeze the process
 * and silently tear down the GATT connections a few minutes in, which shows up as a heart-rate
 * strap that "randomly disconnects".
 *
 * The notification doubles as the live readout on the lock screen.
 */
class SensorService : Service() {

    private val hub: SensorHub by lazy { SensorHub.from(this) }

    private var lastNotificationMs = 0L

    private val snapshotObserver = Observer<SensorSnapshot> { snapshot ->
        // The sensors push several packets per second; redrawing the notification that often is
        // pure battery cost for a readout nobody can follow anyway.
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationMs < NOTIFICATION_THROTTLE_MS) {
            return@Observer
        }
        lastNotificationMs = now
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground BEFORE any decision to stop. The service is launched with
        // startForegroundService(), which obliges us to call startForeground() within a few
        // seconds; returning early via stopSelf() alone kills the whole app with
        // ForegroundServiceDidNotStartInTimeException. Every exit below therefore runs after this.
        if (!promoteToForeground()) {
            disableAndStop("Could not promote the sensor service to the foreground")
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            Logger.i(TAG, "Stop action received")
            disableAndStop(null)
            return START_NOT_STICKY
        }

        if (!SensorPermissions.hasConnectPermissions(this)) {
            // Reachable if Bluetooth access is revoked while the service is restarting.
            disableAndStop("BLUETOOTH_CONNECT not granted")
            return START_NOT_STICKY
        }

        hub.start()
        hub.snapshot.observeForever(snapshotObserver)
        return START_STICKY
    }

    private fun promoteToForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(hub.snapshot.value), type)
        true
    } catch (e: RuntimeException) {
        // Android 14+ rejects a connectedDevice foreground service when no Bluetooth permission is
        // held (SecurityException), and Android 12+ rejects starts from the background
        // (ForegroundServiceStartNotAllowedException, an IllegalStateException).
        Logger.e(TAG, "startForeground failed", e)
        false
    }

    /**
     * Stops the service and clears the persisted "sensors enabled" flag.
     *
     * Clearing the flag is the important half: it is read on every app launch to decide whether to
     * start this service, so a failure that is left recorded turns into a crash on every launch
     * that only clearing app data can undo.
     */
    private fun disableAndStop(reason: String?) {
        reason?.let { Logger.w(TAG, "$it - disabling sensors") }
        hub.store.isEnabled = false
        hub.stop()
        stopSelf()
    }

    override fun onDestroy() {
        hub.snapshot.removeObserver(snapshotObserver)
        hub.stop()
        super.onDestroy()
    }

    private fun buildNotification(snapshot: SensorSnapshot?): Notification {
        val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MwmActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SensorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag,
        )

        val summary = CyclingFormatter.notificationSummary(this, snapshot)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSmallIcon(app.organicmaps.branding.R.drawable.ic_splash)
            .setContentTitle(getString(R.string.cycling_sensors_title))
            .setContentText(summary)
            .addAction(0, getString(R.string.cycling_sensors_disconnect), stopIntent)
            .setContentIntent(contentIntent)
            .setColor(ContextCompat.getColor(this, R.color.notification))
            .build()
    }

    companion object {
        private const val TAG = "SensorService"

        const val CHANNEL_ID = "CYCLING SENSORS"
        const val NOTIFICATION_ID = 54322

        private const val ACTION_STOP = "app.organicmaps.cycling.STOP_SENSORS"
        private const val NOTIFICATION_THROTTLE_MS = 3_000L

        @JvmStatic
        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.cycling_sensors_title))
                .setLightsEnabled(false)
                .setVibrationEnabled(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /**
         * Starts the service, but only when it can legally run.
         *
         * Launching it without Bluetooth permission is not a recoverable error: on Android 14+ the
         * platform refuses a connectedDevice foreground service outright, and the obligation to
         * call startForeground() still applies, so the process gets killed. Refusing here is what
         * keeps a missing permission a no-op instead of a crash.
         */
        @JvmStatic
        fun start(context: Context) {
            if (!SensorPermissions.hasConnectPermissions(context)) {
                Logger.w(TAG, "Not starting sensor service: Bluetooth permission not granted")
                SensorHub.from(context).store.isEnabled = false
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, SensorService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException on Android 12+ when we are backgrounded.
                Logger.e(TAG, "Cannot start sensor service", e)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, SensorService::class.java))
        }
    }
}
