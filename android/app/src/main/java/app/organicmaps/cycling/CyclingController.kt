package app.organicmaps.cycling

import android.location.Location
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.organicmaps.MwmApplication
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.ui.CompassView
import app.organicmaps.sdk.location.LocationListener
import app.organicmaps.sdk.location.SensorListener

/**
 * Wires the cycling features into the map activity: the compass, and starting the service that
 * keeps sensor connections alive.
 *
 * Live figures are shown in the compass rather than in a separate strip along the bottom - that
 * strip crowded the map and fought with the footer buttons for space. Music transport lives in the
 * map's own button column (see [app.organicmaps.cycling.ui.MusicButtons]), so this class never
 * touches it.
 */
class CyclingController(
    private val activity: AppCompatActivity,
    root: View,
) : LocationListener, SensorListener {

    private val sensors = SensorHub.from(activity)
    private val media = MediaControlHub.from(activity)

    private val compass: CompassView = root.findViewById(R.id.cycling_compass)

    /** Last speed reported by GPS, in m/s. Null until the first fix with a speed. */
    private var gpsSpeedMps: Double? = null

    init {
        // The compass sits in the top-right corner, which is exactly where the status bar puts the
        // clock, wifi and battery icons. Offset it by the status bar inset rather than guessing a
        // fixed margin, since that height varies with the device and its cutout.
        ViewCompat.setOnApplyWindowInsetsListener(compass) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = systemBars.top + view.resources.getDimensionPixelSize(R.dimen.margin_base)
                params.rightMargin = systemBars.right + view.resources.getDimensionPixelSize(R.dimen.margin_base)
                view.layoutParams = params
            }
            insets
        }

        sensors.snapshot.observe(activity) { snapshot -> bindSensors(snapshot) }
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
        // reading rather than as zero, so the readout doesn't flicker between 0 and a real value.
        gpsSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else gpsSpeedMps
        // Bookmarks don't move, but the rider does - recompute bearings on each fix, not more often.
        compass.waypoints = CompassWaypoints.nearest(location.latitude, location.longitude)
        bindSensors(sensors.snapshot.value ?: SensorSnapshot.EMPTY)
    }

    // endregion

    override fun onCompassUpdated(north: Double) {
        // The core reports radians from true north; the dial works in degrees.
        compass.headingDegrees = Math.toDegrees(north)
    }

    private fun bindSensors(snapshot: SensorSnapshot) {
        // GPS speed by default, falling back to a wheel sensor only when GPS has produced nothing -
        // GPS is available everywhere, a sensor is not.
        val speed = gpsSpeedMps ?: snapshot.speedMps
        compass.speedText = speed?.let { CyclingFormatter.speedValue(it) } ?: "--"
        compass.speedUnit = CyclingFormatter.speedUnit(activity)
    }
}
