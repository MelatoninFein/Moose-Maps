package app.organicmaps.cycling

import android.location.Location
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.organicmaps.MwmApplication
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.rides.LiveSegmentProgress
import app.organicmaps.cycling.rides.LiveSegmentTracker
import app.organicmaps.cycling.rides.SegmentBestStore
import app.organicmaps.cycling.rides.SegmentStore
import app.organicmaps.cycling.ui.CompassView
import app.organicmaps.cycling.ui.SensorTileView
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

    private val liveTiles: LinearLayout = root.findViewById(R.id.cycling_live_tiles)
    private val liveHeartRate: SensorTileView = root.findViewById(R.id.live_heart_rate)
    private val liveCadence: SensorTileView = root.findViewById(R.id.live_cadence)
    private val livePower: SensorTileView = root.findViewById(R.id.live_power)

    private val segmentBanner: LinearLayout = root.findViewById(R.id.cycling_segment_banner)
    private val segmentName: TextView = root.findViewById(R.id.segment_name)
    private val segmentElapsed: TextView = root.findViewById(R.id.segment_elapsed)
    private val segmentDelta: TextView = root.findViewById(R.id.segment_delta)

    /**
     * Built once per foregrounding rather than per fix: reading every segment and best time off
     * disk on each GPS update would be pointless work at 1 Hz.
     */
    private var segmentTracker: LiveSegmentTracker? = null

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
                params.rightMargin =
                    systemBars.right + view.resources.getDimensionPixelSize(R.dimen.cycling_compass_margin_end)
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

        val segments = SegmentStore(activity).list()
        val bests = SegmentBestStore(activity).loadAll(segments.map { it.id })
        segmentTracker = LiveSegmentTracker(segments, bests)
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
        updateSegment(location)
        bindSensors(sensors.snapshot.value ?: SensorSnapshot.EMPTY)
    }

    // endregion

    override fun onCompassUpdated(north: Double) {
        // The core reports radians from true north; the dial works in degrees.
        compass.headingDegrees = Math.toDegrees(north)
    }

    /** Drives the live segment banner: name, elapsed so far, and the gap to your best. */
    private fun updateSegment(location: Location) {
        val progress = segmentTracker?.onPosition(location.latitude, location.longitude, location.time)
        if (progress == null) {
            segmentBanner.visibility = View.GONE
            return
        }

        segmentBanner.visibility = View.VISIBLE
        segmentName.text = progress.segmentName
        segmentElapsed.text = formatClock(progress.elapsedMillis)

        val delta = progress.deltaMillis
        if (delta == null) {
            segmentDelta.setText(R.string.cycling_segment_no_best)
            segmentDelta.setTextColor(ContextCompat.getColor(activity, R.color.text_dark_subtitle))
        } else {
            val seconds = delta / 1000.0
            segmentDelta.text = String.format(java.util.Locale.getDefault(), "%+.0fs", seconds)
            // Ahead reads green, behind red - the only thing worth taking in at a glance.
            val colour = if (delta <= 0) R.color.segment_ahead else R.color.segment_behind
            segmentDelta.setTextColor(ContextCompat.getColor(activity, colour))
        }

        if (progress.finished) {
            // Leave the final time up briefly rather than snapping away at the line.
            segmentBanner.postDelayed({ segmentBanner.visibility = View.GONE }, FINISH_LINGER_MS)
        }
    }

    private fun formatClock(millis: Long): String {
        val totalSeconds = millis / 1000
        return String.format(java.util.Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun bindSensors(snapshot: SensorSnapshot) {
        // GPS speed by default, falling back to a wheel sensor only when GPS has produced nothing -
        // GPS is available everywhere, a sensor is not.
        val speed = gpsSpeedMps ?: snapshot.speedMps
        compass.speedText = speed?.let { CyclingFormatter.speedValue(it) } ?: "--"
        compass.speedUnit = CyclingFormatter.speedUnit(activity)

        // Each tile hides itself when its value is null, so the column shrinks to whatever the
        // rider actually has connected, and disappears entirely with no sensors at all.
        liveHeartRate.value = snapshot.heartRateBpm?.toString()
        liveCadence.value = snapshot.cadenceRpm?.toString()
        livePower.value = snapshot.powerWatts?.toString()
        liveTiles.visibility = if (snapshot.heartRateBpm != null || snapshot.cadenceRpm != null ||
            snapshot.powerWatts != null
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private companion object {
        const val FINISH_LINGER_MS = 8_000L
    }
}