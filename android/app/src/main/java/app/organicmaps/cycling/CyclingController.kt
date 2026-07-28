package app.organicmaps.cycling

import android.location.Location
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.organicmaps.MwmApplication
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.ui.SensorTileView
import app.organicmaps.cycling.ui.CompassView
import app.organicmaps.sdk.location.LocationListener
import app.organicmaps.sdk.location.SensorListener

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
) : LocationListener, SensorListener {

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
    private val compass: CompassView = root.findViewById(R.id.cycling_compass)

    /** Last speed reported by GPS, in m/s. Null until the first fix with a speed. */
    private var gpsSpeedMps: Double? = null

    /** Last position, kept so waypoint bearings can be recomputed as the rider moves. */
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    init {
        // Lift the readout clear of the navigation bar as well as the map's own bottom button row.
        // The XML margin alone only clears the buttons, so on a device with a 3-button navigation
        // bar the panel sat on top of the footer. The activity's listener on the coordinator
        // returns insets unconsumed, so this child listener still fires.
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin =
                    systemBars.bottom + view.resources.getDimensionPixelSize(R.dimen.cycling_panel_bottom_margin)
                view.layoutParams = params
            }
            insets
        }

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
        MwmApplication.from(activity).getSensorHelper().addListener(this)
    }

    fun onStop() {
        MwmApplication.from(activity).getLocationHelper().removeListener(this)
        MwmApplication.from(activity).getSensorHelper().removeListener(this)
    }

    // region LocationListener

    override fun onLocationUpdated(location: Location) {
        // hasSpeed() is false on fixes from a stationary or coarse provider; treat those as no
        // reading rather than as zero, so the panel doesn't flicker between 0 and a real value.
        gpsSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else gpsSpeedMps
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        // Bookmarks don't move, but the rider does - recompute bearings on each fix, not more often.
        compass.waypoints = CompassWaypoints.nearest(location.latitude, location.longitude)
        bindSensors(sensors.snapshot.value ?: SensorSnapshot.EMPTY)
    }

    // endregion

    override fun onCompassUpdated(north: Double) {
        // The core reports radians from true north; the dial works in degrees.
        compass.headingDegrees = Math.toDegrees(north)
    }

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

        // The compass shows GPS speed by default and only falls back to a wheel sensor when GPS
        // has produced nothing - GPS is available everywhere, a sensor is not.
        val compassSpeed = gpsSpeedMps ?: snapshot.speedMps
        compass.speedText = compassSpeed?.let { CyclingFormatter.speedValue(it) } ?: "--"
        compass.speedUnit = speedUnit
    }
}
