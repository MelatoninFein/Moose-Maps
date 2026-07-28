package app.organicmaps.cycling.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.sdk.util.log.Logger

/**
 * Drives picture-in-picture for the map.
 *
 * PiP is the feature that makes the map usable *while doing something else* - reading a message,
 * changing a playlist - without losing the route. The window cannot receive touches, so the only
 * interaction available is the small action row the system draws over it, which is wired here to
 * the music transport rather than to map controls: skipping a track is the thing a rider actually
 * wants while the map is a thumbnail.
 *
 * Everything is guarded on API 26 (PiP with parameters). On older devices [isSupported] is false
 * and the entry points are inert, so callers need no version checks of their own.
 */
class PipController(
    private val activity: Activity,
    private val media: MediaControlHub,
) {

    private var receiverRegistered = false

    /** Mirrored here because PictureInPictureParams has no getter; every rebuild must re-apply it. */
    private var autoEnterEnabled = false

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Enters PiP now. Returns false when unsupported or the system refused. */
    fun enter(): Boolean {
        if (!isSupported) {
            return false
        }
        return try {
            enterCompat()
        } catch (e: IllegalStateException) {
            // Thrown when the activity is not resumed, e.g. a dialog took over.
            Logger.w(TAG, "Cannot enter PiP: ${e.message}")
            false
        }
    }

    /**
     * Asks the system to enter PiP by itself when the user leaves the app. Only Android 12+ supports
     * this; below it, callers fall back to entering explicitly from `onUserLeaveHint`.
     */
    fun setAutoEnterEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        autoEnterEnabled = enabled
        applyParams()
    }

    /** Refreshes the PiP action row after the playback state changes. */
    fun refreshActions() {
        if (!isSupported) {
            return
        }
        applyParams()
    }

    fun registerActionReceiver() {
        if (!isSupported || receiverRegistered) {
            return
        }
        val filter = IntentFilter(ACTION_MEDIA_CONTROL)
        ContextCompat.registerReceiver(activity, actionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    fun unregisterActionReceiver() {
        if (!receiverRegistered) {
            return
        }
        activity.unregisterReceiver(actionReceiver)
        receiverRegistered = false
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MEDIA_CONTROL) {
                return
            }
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PREVIOUS -> media.skipToPrevious()
                CONTROL_PLAY_PAUSE -> media.togglePlayPause()
                CONTROL_NEXT -> media.skipToNext()
            }
            // The play/pause icon has to flip immediately; the session callback is too slow to see.
            refreshActions()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterCompat(): Boolean = activity.enterPictureInPictureMode(buildParams())

    private fun applyParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        try {
            activity.setPictureInPictureParams(buildParams())
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Cannot update PiP params: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            // 4:3 keeps enough map visible to read the road ahead; 16:9 crops it to a sliver.
            .setAspectRatio(Rational(PIP_ASPECT_WIDTH, PIP_ASPECT_HEIGHT))
            .setActions(buildActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnterEnabled)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildActions(): List<RemoteAction> {
        val nowPlaying = media.nowPlaying.value
        // With no controllable session an action row would be three dead buttons.
        if (nowPlaying?.isActive != true && !media.hasMetadataAccess) {
            return emptyList()
        }

        // The getter only exists on API 31+; below it the platform guarantees at least three slots.
        val maxActions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.maxNumPictureInPictureActions
        } else {
            MIN_GUARANTEED_PIP_ACTIONS
        }
        val playPauseIcon = if (nowPlaying?.isPlaying == true) {
            R.drawable.ic_cycling_pause
        } else {
            R.drawable.ic_cycling_play
        }
        val playPauseLabel = activity.getString(
            if (nowPlaying?.isPlaying == true) R.string.cycling_music_pause else R.string.cycling_music_play,
        )

        return listOf(
            remoteAction(R.drawable.ic_cycling_previous, R.string.cycling_music_previous, CONTROL_PREVIOUS),
            remoteAction(playPauseIcon, playPauseLabel, CONTROL_PLAY_PAUSE),
            remoteAction(R.drawable.ic_cycling_next, R.string.cycling_music_next, CONTROL_NEXT),
        ).take(maxActions)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun remoteAction(iconRes: Int, labelRes: Int, control: Int): RemoteAction =
        remoteAction(iconRes, activity.getString(labelRes), control)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun remoteAction(iconRes: Int, label: String, control: Int): RemoteAction {
        val intent = Intent(ACTION_MEDIA_CONTROL)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_CONTROL, control)
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            control,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(activity, iconRes), label, label, pendingIntent)
    }

    companion object {
        private const val TAG = "PipController"

        private const val ACTION_MEDIA_CONTROL = "app.organicmaps.cycling.PIP_MEDIA_CONTROL"
        private const val EXTRA_CONTROL = "control"

        private const val CONTROL_PREVIOUS = 1
        private const val CONTROL_PLAY_PAUSE = 2
        private const val CONTROL_NEXT = 3

        private const val PIP_ASPECT_WIDTH = 4
        private const val PIP_ASPECT_HEIGHT = 3

        private const val MIN_GUARANTEED_PIP_ACTIONS = 3
    }
}
