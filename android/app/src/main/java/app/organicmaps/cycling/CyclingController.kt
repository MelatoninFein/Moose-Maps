package app.organicmaps.cycling

import android.location.Location
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.organicmaps.MwmApplication
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.ui.SensorTileView
import app.organicmaps.sdk.location.LocationListener

/**
 * Wires the cycling features into the map activity: the data panels along the bottom of the map,
 * and starting the service that keeps sensor connections alive.
 *
 * Music transport lives in the map's own button column (see
 * [app.organicmaps.cycling.ui.MusicButtons]), so this class never touches it.
 */
class CyclingController(
    private val activity: AppCompatActivity,
    root: View,
) : LocationListener {

    private val sensors = SensorHub.from(activity)
    private val media = MediaControlHub.from(activity)

    private val overlay: LinearLayout = root.findViewById(R.id.cycling_overlay)
    private val sensorPanel: LinearLayout = root.findViewById(R.id.cycling_sensor_panel)
    private val toggle: ImageView = root.findViewById(R.id.cycling_panel_toggle)

    private val gpsSpeedTile: SensorTileView = root.findViewById(R.id.tile_gps_speed)
    private val heartRateTile: SensorTileView = root.findViewById(R.id.tile_heart_rate)
    private val cadenceTile: SensorTileView = root.findViewById(R.id.tile_cadence)
    private val speedTile: SensorTileView = root.findViewById(R.id.tile_speed)
    private val powerTile: SensorTileView = root.findViewById(R.id.tile_power)

    /** Last speed reported by GPS, in m/s. Null until the first fix with a speed. */
    private var gpsSpeedMps: Double? = null

    init {
        toggle.setOnClickListener { setPanelsExpanded(!sensors.store.isOverlayVisible) }
        sensors.snapshot.observe(activity) { snapshot -> bindSensors(snapshot) }
        setPanelsExpanded(sensors.store.isOverlayVisible)
    }

    fun onStart() {
        // Cheap, and covers the case where the user granted notification access or turned Bluetooth
        // on while the app was in the background.
        media.refreshSessions()
        if (sensors.store.isEnabled) {
            SensorService.start(activity)
        }
        MwmApplication.from(activity).getLocationHelper().addListener(this)
    }

    fun onStop() {
        MwmApplication.from(activity).getLocationHelper().removeListener(this)
    }

    // region LocationListener

    override fun onLocationUpdated(location: Location) {
        // hasSpeed() is false on fixes from a stationary or coarse provider; treat those as no
        // reading rather than as zero, so the panel doesn't flicker between 0 and a real value.
        gpsSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else gpsSpeedMps
        bindSensors(sensors.snapshot.value ?: SensorSnapshot.EMPTY)
    }

    // endregion

    /** Collapses to just the chevron, or expands to the full strip. Remembered across launches. */
    private fun setPanelsExpanded(expanded: Boolean) {
        sensors.store.isOverlayVisible = expanded
        sensorPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.setImageResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
    }

    private fun bindSensors(snapshot: SensorSnapshot) {
        val speedUnit = CyclingFormatter.speedUnit(activity)
        gpsSpeedTile.label = speedUnit
        speedTile.label = speedUnit

        // GPS speed is always shown once there has been a fix - it needs no paired hardware.
        gpsSpeedTile.value = gpsSpeedMps?.let { CyclingFormatter.speedValue(it) } ?: "--"
        heartRateTile.value = snapshot.heartRateBpm?.toString()
        cadenceTile.value = snapshot.cadenceRpm?.toString()
        // Only shown when a wheel sensor is reporting; otherwise GPS speed already covers it.
        speedTile.value = snapshot.speedMps?.let { CyclingFormatter.speedValue(it) }
        powerTile.value = snapshot.powerWatts?.toString()
    }
}
