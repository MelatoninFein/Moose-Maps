package app.organicmaps.cycling.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.media.MediaNotificationListener
import app.organicmaps.cycling.sensors.DiscoveredSensor
import app.organicmaps.cycling.sensors.SensorConnectionState
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorKind
import app.organicmaps.cycling.sensors.SensorPermissions
import app.organicmaps.cycling.sensors.SensorScanner
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorStatus
import app.organicmaps.cycling.sensors.SensorStore
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Settings for the cycling features: sensor pairing, the on-map readout, wheel size, and the
 * one-time grant that lets the media bar show track names.
 *
 * Paired and discovered sensors are drawn as plain rows into a LinearLayout rather than through a
 * RecyclerView - a rider has a handful of sensors, and an adapter would be more machinery than the
 * list is worth.
 */
class CyclingSettingsFragment : BaseMwmFragment() {

    private lateinit var hub: SensorHub
    private lateinit var media: MediaControlHub
    private lateinit var store: SensorStore

    private lateinit var sensorsSwitch: SwitchCompat
    private lateinit var overlaySwitch: SwitchCompat
    private lateinit var wheelInputLayout: TextInputLayout
    private lateinit var wheelField: TextInputEditText
    private lateinit var pairedList: LinearLayout
    private lateinit var pairedEmpty: TextView
    private lateinit var discoveredList: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var mediaAccessSummary: TextView
    private lateinit var mediaAccessButton: Button

    private val discovered = linkedMapOf<String, DiscoveredSensor>()

    private lateinit var sensorStatus: TextView

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startScan()
            } else {
                showSensorStatus(R.string.cycling_sensors_permission_denied)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_cycling_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))

        hub = SensorHub.from(requireContext())
        media = MediaControlHub.from(requireContext())
        store = hub.store

        sensorsSwitch = view.findViewById(R.id.cycling_sensors_enabled)
        overlaySwitch = view.findViewById(R.id.cycling_overlay_enabled)
        wheelInputLayout = view.findViewById(R.id.cycling_wheel_input)
        wheelField = view.findViewById(R.id.cycling_wheel_circumference)
        pairedList = view.findViewById(R.id.cycling_paired_list)
        pairedEmpty = view.findViewById(R.id.cycling_paired_empty)
        discoveredList = view.findViewById(R.id.cycling_discovered_list)
        scanButton = view.findViewById(R.id.cycling_scan_button)
        sensorStatus = view.findViewById(R.id.cycling_sensor_status)
        mediaAccessSummary = view.findViewById(R.id.cycling_media_access_summary)
        mediaAccessButton = view.findViewById(R.id.cycling_media_access_button)

        sensorsSwitch.isChecked = store.isEnabled
        overlaySwitch.isChecked = store.isOverlayVisible
        wheelField.setText(store.wheelCircumferenceMm.toString())

        sensorsSwitch.setOnCheckedChangeListener { _, isChecked -> onSensorsToggled(isChecked) }
        overlaySwitch.setOnCheckedChangeListener { _, isChecked -> store.isOverlayVisible = isChecked }
        scanButton.setOnClickListener { onScanClicked() }
        mediaAccessButton.setOnClickListener {
            startActivity(Intent(MediaNotificationListener.settingsIntentAction))
        }

        hub.statuses.observe(viewLifecycleOwner) { statuses -> bindPaired(statuses) }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted notification access in system settings and come straight back.
        bindMediaAccess()
    }

    override fun onPause() {
        hub.stopScan()
        applyWheelCircumference()
        super.onPause()
    }

    private fun onSensorsToggled(enabled: Boolean) {
        store.isEnabled = enabled
        if (enabled) {
            SensorService.start(requireContext())
        } else {
            SensorService.stop(requireContext())
        }
    }

    /**
     * Persists the wheel size. An empty or out-of-range entry is reverted to the stored value rather
     * than saved: a bad circumference silently corrupts every speed reading afterwards.
     */
    private fun applyWheelCircumference() {
        val entered = wheelField.text?.toString()?.toIntOrNull()
        if (entered == null || entered !in SensorStore.MIN_CIRCUMFERENCE_MM..SensorStore.MAX_CIRCUMFERENCE_MM) {
            wheelInputLayout.error = getString(
                R.string.cycling_wheel_circumference_error,
                SensorStore.MIN_CIRCUMFERENCE_MM,
                SensorStore.MAX_CIRCUMFERENCE_MM,
            )
            wheelField.setText(store.wheelCircumferenceMm.toString())
            return
        }
        wheelInputLayout.error = null
        store.wheelCircumferenceMm = entered
    }

    private fun onScanClicked() {
        if (SensorPermissions.hasScanPermissions(requireContext())) {
            startScan()
        } else {
            permissionRequest.launch(SensorPermissions.requiredForScan)
        }
    }

    private fun startScan() {
        discovered.clear()
        discoveredList.removeAllViews()
        sensorStatus.visibility = View.GONE

        val started = hub.startScan(
            SensorScanner.Listener { sensor -> onSensorFound(sensor) },
        ) {
            // The fragment may already be gone by the time the 15 s scan ends.
            if (isAdded) {
                scanButton.isEnabled = true
                scanButton.setText(R.string.cycling_sensors_scan)
                if (discovered.isEmpty()) {
                    showSensorStatus(R.string.cycling_sensors_none_found)
                }
            }
        }

        if (started) {
            scanButton.isEnabled = false
            scanButton.setText(R.string.cycling_sensors_scanning)
        } else {
            showSensorStatus(R.string.cycling_sensors_bluetooth_off)
        }
    }

    private fun onSensorFound(sensor: DiscoveredSensor) {
        if (!isAdded || hub.isPaired(sensor.address) || discovered.containsKey(sensor.address)) {
            return
        }
        discovered[sensor.address] = sensor

        val row = layoutInflater.inflate(R.layout.item_cycling_sensor, discoveredList, false)
        row.findViewById<TextView>(R.id.sensor_name).text = sensor.name
        row.findViewById<TextView>(R.id.sensor_status).text = describeKinds(sensor.kinds)
        row.findViewById<ImageView>(R.id.sensor_action).visibility = View.GONE
        row.setOnClickListener {
            hub.pair(sensor)
            discovered.remove(sensor.address)
            discoveredList.removeView(row)
            if (!store.isEnabled) {
                // Pairing a first sensor without enabling the feature would do nothing visible.
                sensorsSwitch.isChecked = true
            }
        }
        discoveredList.addView(row)
    }

    private fun bindPaired(statuses: List<SensorStatus>) {
        pairedList.removeAllViews()
        pairedEmpty.visibility = if (statuses.isEmpty()) View.VISIBLE else View.GONE

        statuses.forEach { status ->
            val row = layoutInflater.inflate(R.layout.item_cycling_sensor, pairedList, false)
            row.findViewById<TextView>(R.id.sensor_name).text = status.sensor.name
            row.findViewById<TextView>(R.id.sensor_status).text = describeStatus(status)
            row.findViewById<ImageView>(R.id.sensor_action).setOnClickListener {
                hub.forget(status.sensor.address)
            }
            pairedList.addView(row)
        }
    }

    private fun bindMediaAccess() {
        val granted = MediaNotificationListener.isEnabled(requireContext())
        mediaAccessSummary.setText(
            if (granted) R.string.cycling_music_access_granted else R.string.cycling_music_access_missing,
        )
        mediaAccessButton.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            media.refreshSessions()
        }
    }

    private fun describeStatus(status: SensorStatus): String {
        val state = getString(
            when (status.state) {
                SensorConnectionState.CONNECTED -> R.string.cycling_sensor_connected
                SensorConnectionState.CONNECTING -> R.string.cycling_sensor_connecting
                SensorConnectionState.DISCONNECTED -> R.string.cycling_sensor_disconnected
            },
        )
        val battery = status.batteryPercent?.let { getString(R.string.cycling_sensor_battery, it) }
        return listOfNotNull(state, describeKinds(status.sensor.kinds), battery).joinToString(" · ")
    }

    private fun describeKinds(kinds: Set<SensorKind>): String = kinds.sorted().joinToString(", ") { kind ->
        getString(
            when (kind) {
                SensorKind.HEART_RATE -> R.string.cycling_metric_heart_rate
                SensorKind.CADENCE -> R.string.cycling_metric_cadence
                SensorKind.SPEED -> R.string.cycling_metric_speed
                SensorKind.POWER -> R.string.cycling_metric_power
            },
        )
    }

    private fun showSensorStatus(messageRes: Int) {
        if (!isAdded) {
            return
        }
        sensorStatus.setText(messageRes)
        sensorStatus.visibility = View.VISIBLE
    }
}
