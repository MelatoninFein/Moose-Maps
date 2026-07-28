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
        if (intent?.action == ACTION_STOP) {
            Logger.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!SensorPermissions.hasConnectPermissions(this)) {
            // The user can revoke Bluetooth access while the service is restarting.
            Logger.w(TAG, "BLUETOOTH_CONNECT not granted, stopping sensor service")
            stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(hub.snapshot.value), type)

        hub.start()
        hub.snapshot.observeForever(snapshotObserver)
        return START_STICKY
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

        @JvmStatic
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SensorService::class.java))
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, SensorService::class.java))
        }
    }
}
