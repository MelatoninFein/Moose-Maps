package app.organicmaps.cycling

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.media.MediaNotificationListener
import app.organicmaps.cycling.media.MusicApps
import app.organicmaps.cycling.pip.PipController
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.ui.SensorTileView
import app.organicmaps.sdk.routing.RoutingController
import com.google.android.material.floatingactionbutton.FloatingActionButton

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

    private val heartRateTile: SensorTileView = root.findViewById(R.id.tile_heart_rate)
    private val cadenceTile: SensorTileView = root.findViewById(R.id.tile_cadence)
    private val speedTile: SensorTileView = root.findViewById(R.id.tile_speed)
    private val powerTile: SensorTileView = root.findViewById(R.id.tile_power)

    // Music: a FAB that slides an off-canvas panel in from the trailing edge.
    private val mediaFab: FloatingActionButton = root.findViewById(R.id.cycling_media_fab)
    private val mediaPanel: LinearLayout = root.findViewById(R.id.cycling_media_panel)
    private val mediaScrim: View = root.findViewById(R.id.cycling_media_scrim)
    private val artwork: ImageView = root.findViewById(R.id.cycling_media_artwork)
    private val mediaTitle: TextView = root.findViewById(R.id.cycling_media_title)
    private val mediaSubtitle: TextView = root.findViewById(R.id.cycling_media_subtitle)
    private val mediaEmpty: TextView = root.findViewById(R.id.cycling_media_empty)
    private val mediaAccessHint: TextView = root.findViewById(R.id.cycling_media_access_hint)
    private val mediaGrantAccess: Button = root.findViewById(R.id.cycling_media_grant_access)
    private val mediaApps: LinearLayout = root.findViewById(R.id.cycling_media_apps)
    private val mediaPlayPause: ImageButton = root.findViewById(R.id.cycling_media_play_pause)

    private var mediaPanelOpen = false

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

        root.findViewById<ImageButton>(R.id.cycling_media_previous).setOnClickListener { media.skipToPrevious() }
        root.findViewById<ImageButton>(R.id.cycling_media_next).setOnClickListener { media.skipToNext() }
        mediaPlayPause.setOnClickListener { media.togglePlayPause() }
        root.findViewById<ImageView>(R.id.cycling_media_close).setOnClickListener { closeMediaPanel() }

        mediaGrantAccess.setOnClickListener {
            activity.startActivity(Intent(MediaNotificationListener.settingsIntentAction))
            closeMediaPanel()
        }

        mediaFab.setOnClickListener { openMediaPanel() }
        mediaScrim.setOnClickListener { closeMediaPanel() }
        // Tapping the artwork jumps to the player that owns the session.
        artwork.setOnClickListener { openCurrentPlayer() }

        sensors.snapshot.observe(activity) { snapshot -> bindSensors(snapshot) }
        media.nowPlaying.observe(activity) { nowPlaying -> bindMedia(nowPlaying) }
    }

    fun onStart() {
        // Reconnecting on every foreground is cheap and covers the case where the user granted
        // notification access or turned Bluetooth on while the app was in the background.
        media.refreshSessions()
        // Re-read the setting: it may have been toggled in Settings since the map was last shown.
        updateMediaFabVisibility()
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
        updateMediaFabVisibility()

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
        val hasSession = nowPlaying.isActive

        mediaTitle.text = if (hasSession) {
            nowPlaying.title ?: activity.getString(R.string.cycling_music_unknown_track)
        } else {
            ""
        }
        mediaSubtitle.text = listOfNotNull(nowPlaying.artist, nowPlaying.appLabel).joinToString(" · ")
        mediaTitle.visibility = if (hasSession) View.VISIBLE else View.GONE
        mediaSubtitle.visibility = if (hasSession) View.VISIBLE else View.GONE
        mediaEmpty.visibility = if (hasSession) View.GONE else View.VISIBLE

        val art = nowPlaying.artwork
        if (art != null) {
            artwork.setImageBitmap(art)
            artwork.imageTintList = null
        } else {
            artwork.setImageResource(R.drawable.ic_cycling_music)
        }

        mediaPlayPause.setImageResource(
            if (nowPlaying.isPlaying) R.drawable.ic_cycling_pause else R.drawable.ic_cycling_play,
        )

        // Surface the missing grant where the user notices it, not only in Settings.
        val needsAccess = !media.hasMetadataAccess
        mediaAccessHint.visibility = if (needsAccess) View.VISIBLE else View.GONE
        mediaGrantAccess.visibility = if (needsAccess) View.VISIBLE else View.GONE

        bindPlayerShortcuts()
        updateMediaFabVisibility()
        // Keep the PiP action row's play/pause icon in step with the panel.
        pip.refreshActions()
    }

    /** Buttons to launch the installed players, so the panel is useful before anything is playing. */
    private fun bindPlayerShortcuts() {
        val installed = MusicApps.installed(activity)
        if (mediaApps.childCount == installed.size) {
            return // Installed apps don't change while the map is open.
        }
        mediaApps.removeAllViews()
        installed.forEach { app ->
            val button = Button(activity, null, 0, R.style.CyclingMediaAppButton)
            button.text = activity.getString(R.string.cycling_music_open_app, app.displayName)
            button.setOnClickListener {
                MusicApps.launchIntent(activity, app.packageName)?.let { activity.startActivity(it) }
                closeMediaPanel()
            }
            mediaApps.addView(button)
        }
    }

    /**
     * The button is shown whenever the user hasn't turned it off, and hidden only in
     * picture-in-picture, where the system action row takes over.
     *
     * It deliberately does NOT depend on a player being installed or on a session being visible:
     * transport controls work through media key events even with no permission granted and no
     * recognised app, and a control the user cannot find is a feature that does not exist. The
     * panel itself explains anything that is missing.
     */
    private fun updateMediaFabVisibility() {
        val show = sensors.store.isMediaControlsVisible && !inPictureInPicture
        mediaFab.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && mediaPanelOpen) {
            closeMediaPanel()
        }
    }

    private fun openMediaPanel() {
        if (mediaPanelOpen) {
            return
        }
        mediaPanelOpen = true
        // Refresh on open: the user may have granted notification access since the map started.
        media.refreshSessions()

        mediaScrim.visibility = View.VISIBLE
        mediaScrim.alpha = 0f
        mediaScrim.animate().alpha(1f).setDuration(PANEL_ANIM_MS).start()

        mediaPanel.visibility = View.VISIBLE
        // Slide in from the trailing edge; RTL flips the sign so it still enters from off-screen.
        val offscreen = if (mediaPanel.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            -mediaPanel.width.toFloat()
        } else {
            mediaPanel.width.toFloat()
        }
        mediaPanel.translationX = offscreen
        mediaPanel.animate().translationX(0f).setDuration(PANEL_ANIM_MS).start()
    }

    private fun closeMediaPanel() {
        if (!mediaPanelOpen) {
            return
        }
        mediaPanelOpen = false
        val offscreen = if (mediaPanel.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            -mediaPanel.width.toFloat()
        } else {
            mediaPanel.width.toFloat()
        }
        mediaPanel.animate().translationX(offscreen).setDuration(PANEL_ANIM_MS)
            .withEndAction { mediaPanel.visibility = View.GONE }.start()
        mediaScrim.animate().alpha(0f).setDuration(PANEL_ANIM_MS)
            .withEndAction { mediaScrim.visibility = View.GONE }.start()
    }

    /** Lets the activity's back handling dismiss the panel before it closes anything else. */
    fun onBackPressed(): Boolean {
        if (mediaPanelOpen) {
            closeMediaPanel()
            return true
        }
        return false
    }

    private fun updateOverlayVisibility() {
        val show = sensorPanel.visibility == View.VISIBLE && !inPictureInPicture
        overlay.visibility = if (show) View.VISIBLE else View.GONE
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
        private const val PANEL_ANIM_MS = 200L
    }
}
