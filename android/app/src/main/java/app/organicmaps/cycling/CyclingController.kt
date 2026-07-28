package app.organicmaps.cycling

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.organicmaps.R
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.cycling.ui.SensorTileView

/**
 * Wires the cycling features into the map activity.
 *
 * Scope is deliberately small: the live sensor readout over the map, and starting the service that
 * keeps sensor connections alive. Music transport lives in the map's own button column (see
 * [app.organicmaps.cycling.ui.MusicButtons]) rather than here, so this class never touches it.
 */
class CyclingController(
    private val activity: AppCompatActivity,
    root: View,
) {

    private val sensors = SensorHub.from(activity)
    private val media = MediaControlHub.from(activity)

    private val overlay: LinearLayout = root.findViewById(R.id.cycling_overlay)
    private val sensorPanel: LinearLayout = root.findViewById(R.id.cycling_sensor_panel)

    private val heartRateTile: SensorTileView = root.findViewById(R.id.tile_heart_rate)
    private val cadenceTile: SensorTileView = root.findViewById(R.id.tile_cadence)
    private val speedTile: SensorTileView = root.findViewById(R.id.tile_speed)
    private val powerTile: SensorTileView = root.findViewById(R.id.tile_power)

    init {
        // Keep the readout clear of the status bar / display cutout. The activity's own listener on
        // the coordinator returns the insets unconsumed, so this child listener still fires.
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = systemBars.top + view.resources.getDimensionPixelSize(R.dimen.margin_half)
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
    }

    private fun bindSensors(snapshot: SensorSnapshot) {
        speedTile.label = CyclingFormatter.speedUnit(activity)

        heartRateTile.value = snapshot.heartRateBpm?.toString()
        cadenceTile.value = snapshot.cadenceRpm?.toString()
        speedTile.value = snapshot.speedMps?.let { CyclingFormatter.speedValue(it) }
        powerTile.value = snapshot.powerWatts?.toString()

        val show = sensors.store.isOverlayVisible && snapshot.hasAnyReading
        sensorPanel.visibility = if (show) View.VISIBLE else View.GONE
        overlay.visibility = if (show) View.VISIBLE else View.GONE
    }
}
