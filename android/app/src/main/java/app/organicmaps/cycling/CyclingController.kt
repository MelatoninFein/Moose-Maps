package app.organicmaps.cycling

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.pip.PipController
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.ui.SensorTileView
import app.organicmaps.sdk.routing.RoutingController

/**
 * Wires the three cycling features into the map activity.
 *
 * Everything the map screen needs to know about sensors, media and picture-in-picture lives here so
 * that [app.organicmaps.MwmActivity] gains a handful of one-line lifecycle forwards rather than
 * three subsystems' worth of state.
 */
class CyclingController(
    private val activity: AppCompatActivity,
    root: View,
    private val host: Host,
) {

    /** The bits of map-activity behaviour this controller needs to reach back into. */
    interface Host {
        /** Hides or restores the regular map chrome (buttons, sheets) when entering/leaving PiP. */
        fun setMapUiHidden(hidden: Boolean)
    }

    private val sensors = SensorHub.from(activity)
    private val media = MediaControlHub.from(activity)
    private val pip = PipController(activity, media)

    private val handler = Handler(Looper.getMainLooper())

    private val overlay: LinearLayout = root.findViewById(R.id.cycling_overlay)
    private val sensorPanel: LinearLayout = root.findViewById(R.id.cycling_sensor_panel)
    private val mediaBar: LinearLayout = root.findViewById(R.id.cycling_media_bar)

    private val heartRateTile: SensorTileView = root.findViewById(R.id.tile_heart_rate)
    private val cadenceTile: SensorTileView = root.findViewById(R.id.tile_cadence)
    private val speedTile: SensorTileView = root.findViewById(R.id.tile_speed)
    private val powerTile: SensorTileView = root.findViewById(R.id.tile_power)

    private val artwork: ImageView = root.findViewById(R.id.media_artwork)
    private val mediaTitle: TextView = root.findViewById(R.id.media_title)
    private val mediaSubtitle: TextView = root.findViewById(R.id.media_subtitle)

    private val pipOverlay: LinearLayout = root.findViewById(R.id.cycling_pip_overlay)
    private val pipNextTurn: TextView = root.findViewById(R.id.pip_next_turn)
    private val pipHeartRateTile: SensorTileView = root.findViewById(R.id.pip_tile_heart_rate)
    private val pipSpeedTile: SensorTileView = root.findViewById(R.id.pip_tile_speed)
    private val pipPowerTile: SensorTileView = root.findViewById(R.id.pip_tile_power)

    private var inPictureInPicture = false

    // While in PiP the turn text is the only thing that changes without a callback to hang off.
    private val pipRefresh = object : Runnable {
        override fun run() {
            updateNextTurn()
            handler.postDelayed(this, PIP_REFRESH_MS)
        }
    }

    val isPipSupported: Boolean
        get() = pip.isSupported

    init {
        // Keep the panel clear of the status bar / display cutout. The activity's own listener on
        // the coordinator returns the insets unconsumed, so this child listener still fires.
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = systemBars.top + view.resources.getDimensionPixelSize(R.dimen.margin_half)
                view.layoutParams = params
            }
            insets
        }

        root.findViewById<ImageButton>(R.id.media_previous).setOnClickListener { media.skipToPrevious() }
        root.findViewById<ImageButton>(R.id.media_next).setOnClickListener { media.skipToNext() }
        root.findViewById<ImageButton>(R.id.media_play_pause).setOnClickListener { media.togglePlayPause() }

        // Tapping the track name opens the player that is currently holding the session.
        mediaBar.setOnClickListener { openCurrentPlayer() }

        sensors.snapshot.observe(activity) { snapshot -> bindSensors(snapshot) }
        media.nowPlaying.observe(activity) { nowPlaying -> bindMedia(nowPlaying) }
    }

    fun onStart() {
        // Reconnecting on every foreground is cheap and covers the case where the user granted
        // notification access or turned Bluetooth on while the app was in the background.
        media.refreshSessions()
        if (sensors.store.isEnabled) {
            SensorService.start(activity)
        }
        pip.registerActionReceiver()
    }

    fun onResume() {
        // Auto-enter only makes sense while actively riding a route.
        pip.setAutoEnterEnabled(RoutingController.get().isNavigating())
    }

    fun onStop() {
        pip.unregisterActionReceiver()
        handler.removeCallbacks(pipRefresh)
    }

    /** Called from `onUserLeaveHint`, the pre-Android-12 way to catch the user leaving. */
    fun onUserLeaving() {
        if (RoutingController.get().isNavigating() && !inPictureInPicture) {
            pip.enter()
        }
    }

    /** Entry point for the explicit "shrink to a floating window" control. */
    fun enterPictureInPicture(): Boolean = pip.enter()

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        inPictureInPicture = isInPictureInPictureMode
        host.setMapUiHidden(isInPictureInPictureMode)

        // The full overlay is unreadable at PiP size; the compact strip replaces it.
        overlay.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        pipOverlay.visibility = if (isInPictureInPictureMode) View.VISIBLE else View.GONE

        if (isInPictureInPictureMode) {
            updateNextTurn()
            handler.post(pipRefresh)
        } else {
            handler.removeCallbacks(pipRefresh)
            // Re-apply after leaving, so the tiles reflect anything received while shrunk.
            bindSensors(sensors.snapshot.value ?: SensorSnapshot.EMPTY)
        }
    }

    private fun bindSensors(snapshot: SensorSnapshot) {
        val speedText = snapshot.speedMps?.let { CyclingFormatter.speedValue(it) }
        speedTile.label = CyclingFormatter.speedUnit(activity)
        pipSpeedTile.label = CyclingFormatter.speedUnit(activity)

        heartRateTile.value = snapshot.heartRateBpm?.toString()
        cadenceTile.value = snapshot.cadenceRpm?.toString()
        speedTile.value = speedText
        powerTile.value = snapshot.powerWatts?.toString()

        pipHeartRateTile.value = snapshot.heartRateBpm?.toString()
        pipSpeedTile.value = speedText
        pipPowerTile.value = snapshot.powerWatts?.toString()

        val showPanel = sensors.store.isOverlayVisible && snapshot.hasAnyReading
        sensorPanel.visibility = if (showPanel) View.VISIBLE else View.GONE
        updateOverlayVisibility()
    }

    private fun bindMedia(nowPlaying: MediaControlHub.NowPlaying) {
        if (!nowPlaying.isActive) {
            mediaBar.visibility = View.GONE
            updateOverlayVisibility()
            return
        }

        mediaBar.visibility = View.VISIBLE
        mediaTitle.text = nowPlaying.title ?: activity.getString(R.string.cycling_music_unknown_track)
        mediaSubtitle.text = listOfNotNull(nowPlaying.artist, nowPlaying.appLabel).joinToString(" · ")
        // Marquee only scrolls on the selected view, and only one view can be selected at a time.
        mediaTitle.isSelected = true

        val art = nowPlaying.artwork
        if (art != null) {
            artwork.setImageBitmap(art)
            artwork.imageTintList = null
        } else {
            artwork.setImageResource(R.drawable.ic_cycling_music)
        }

        val playPause: ImageButton = mediaBar.findViewById(R.id.media_play_pause)
        playPause.setImageResource(
            if (nowPlaying.isPlaying) R.drawable.ic_cycling_pause else R.drawable.ic_cycling_play,
        )

        updateOverlayVisibility()
        // Keep the PiP action row's play/pause icon in step with the bar.
        pip.refreshActions()
    }

    private fun updateOverlayVisibility() {
        val anythingVisible = sensorPanel.visibility == View.VISIBLE || mediaBar.visibility == View.VISIBLE
        overlay.visibility = if (anythingVisible && !inPictureInPicture) View.VISIBLE else View.GONE
    }

    private fun updateNextTurn() {
        val info = RoutingController.get().cachedRoutingInfo
        pipNextTurn.text = if (info == null) {
            activity.getString(R.string.cycling_pip_no_route)
        } else {
            listOfNotNull(info.distToTurn.toString(activity), info.nextStreet?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
        }
    }

    private fun openCurrentPlayer() {
        val packageName = media.nowPlaying.value?.packageName ?: return
        activity.packageManager.getLaunchIntentForPackage(packageName)?.let { activity.startActivity(it) }
    }

    companion object {
        private const val PIP_REFRESH_MS = 1_000L
    }
}
