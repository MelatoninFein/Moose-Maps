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
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.cycling.media.MediaControlHub
import app.organicmaps.cycling.rides.RidesFragment
import app.organicmaps.cycling.rides.SegmentsFragment
import app.organicmaps.cycling.sensors.DiscoveredSensor
import app.organicmaps.cycling.sensors.SensorConnectionState
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.cycling.sensors.SensorKind
import app.organicmaps.cycling.sensors.SensorPermissions
import app.organicmaps.cycling.sensors.SensorScanner
import app.organicmaps.cycling.sensors.SensorService
import app.organicmaps.cycling.sensors.SensorSnapshot
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
    private lateinit var wheelInputLayout: TextInputLayout
    private lateinit var wheelField: TextInputEditText
    private lateinit var maxHeartRateField: TextInputEditText
    private lateinit var pairedList: LinearLayout
    private lateinit var pairedEmpty: TextView
    private lateinit var discoveredList: LinearLayout
    private lateinit var scanButton: Button

    private val discovered = linkedMapOf<String, DiscoveredSensor>()

    /** Status lines of the paired rows, so a new reading updates text instead of rebuilding views. */
    private val pairedStatusViews = linkedMapOf<String, TextView>()
    private var latestStatuses: List<SensorStatus> = emptyList()
    private var latestSnapshot = SensorSnapshot.EMPTY

    private lateinit var sensorStatus: TextView

    /** What to do once the Bluetooth permission prompt comes back. */
    private enum class PendingAction { ENABLE, SCAN }

    private var pendingAction: PendingAction? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val action = pendingAction
            pendingAction = null

            if (results.values.any { !it }) {
                // Leave the feature off rather than half-on: without Bluetooth permission the
                // sensor service cannot legally run at all.
                store.isEnabled = false
                sensorsSwitch.isChecked = false
                showSensorStatus(R.string.cycling_sensors_permission_denied)
                return@registerForActivityResult
            }

            when (action) {
                PendingAction.ENABLE -> enableSensors()
                PendingAction.SCAN -> startScan()
                null -> Unit
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
        wheelInputLayout = view.findViewById(R.id.cycling_wheel_input)
        wheelField = view.findViewById(R.id.cycling_wheel_circumference)
        maxHeartRateField = view.findViewById(R.id.cycling_max_hr)
        pairedList = view.findViewById(R.id.cycling_paired_list)
        pairedEmpty = view.findViewById(R.id.cycling_paired_empty)
        discoveredList = view.findViewById(R.id.cycling_discovered_list)
        scanButton = view.findViewById(R.id.cycling_scan_button)
        sensorStatus = view.findViewById(R.id.cycling_sensor_status)

        sensorsSwitch.isChecked = store.isEnabled
        wheelField.setText(store.wheelCircumferenceMm.toString())
        maxHeartRateField.setText(store.maxHeartRateBpm.toString())

        sensorsSwitch.setOnCheckedChangeListener { _, isChecked -> onSensorsToggled(isChecked) }
        scanButton.setOnClickListener { onScanClicked() }

        // Rides and Segments live inside Cycling rather than as siblings of it, so Settings has one
        // cycling entry rather than four scattered through the general list.
        view.findViewById<Button>(R.id.cycling_open_rides).setOnClickListener {
            (requireActivity() as app.organicmaps.settings.SettingsActivity)
                .stackFragment(RidesFragment::class.java, getString(R.string.cycling_rides_title), null)
        }
        view.findViewById<Button>(R.id.cycling_open_segments).setOnClickListener {
            (requireActivity() as app.organicmaps.settings.SettingsActivity)
                .stackFragment(SegmentsFragment::class.java, getString(R.string.cycling_segments_title), null)
        }

        hub.statuses.observe(viewLifecycleOwner) { statuses ->
            latestStatuses = statuses
            bindPaired(statuses)
        }
        hub.snapshot.observe(viewLifecycleOwner) { snapshot ->
            latestSnapshot = snapshot
            // Only the text changes, so rows are updated in place rather than rebuilt once a second
            // under the rider's finger.
            latestStatuses.forEach { status ->
                pairedStatusViews[status.sensor.address]?.text = describeStatus(status)
            }
        }
    }

    override fun onPause() {
        hub.stopScan()
        applyWheelCircumference()
        applyMaxHeartRate()
        super.onPause()
    }

    private fun onSensorsToggled(enabled: Boolean) {
        if (!enabled) {
            store.isEnabled = false
            SensorService.stop(requireContext())
            return
        }

        // Ask for Bluetooth access first. Enabling without it used to start a foreground service
        // the platform then refused, taking the whole app down with it.
        if (SensorPermissions.hasConnectPermissions(requireContext())) {
            enableSensors()
        } else {
            pendingAction = PendingAction.ENABLE
            permissionRequest.launch(SensorPermissions.requiredForScan)
        }
    }

    private fun enableSensors() {
        store.isEnabled = true
        sensorsSwitch.isChecked = true
        SensorService.start(requireContext())
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

    /** An out-of-range entry reverts rather than saving: a wrong max makes every zone wrong. */
    private fun applyMaxHeartRate() {
        val entered = maxHeartRateField.text?.toString()?.toIntOrNull()
        if (entered == null ||
            entered !in SensorStore.MIN_MAX_HEART_RATE_BPM..SensorStore.MAX_MAX_HEART_RATE_BPM
        ) {
            maxHeartRateField.setText(store.maxHeartRateBpm.toString())
            return
        }
        store.maxHeartRateBpm = entered
    }

    private fun onScanClicked() {
        if (SensorPermissions.hasScanPermissions(requireContext())) {
            startScan()
        } else {
            pendingAction = PendingAction.SCAN
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
        renderDiscovered()
    }

    /**
     * Strongest signal first.
     *
     * A scan in a café or at the start of a group ride turns up every strap in the room, all named
     * something like "CYCPLUS-H2". Discovery order is arbitrary; signal strength is not - the one
     * on your own bars is the closest thing to the phone.
     */
    private fun renderDiscovered() {
        discoveredList.removeAllViews()
        discovered.values.sortedByDescending { it.rssi }.forEach { sensor ->
            val row = layoutInflater.inflate(R.layout.item_cycling_sensor, discoveredList, false)
            row.findViewById<TextView>(R.id.sensor_name).text = sensor.name
            row.findViewById<TextView>(R.id.sensor_status).text =
                "${describeKinds(sensor.kinds)} · ${describeSignal(sensor.rssi)}"
            row.findViewById<ImageView>(R.id.sensor_action).visibility = View.GONE
            row.setOnClickListener {
                hub.pair(sensor)
                discovered.remove(sensor.address)
                renderDiscovered()
                if (!store.isEnabled) {
                    // Pairing a first sensor without enabling the feature would do nothing visible.
                    sensorsSwitch.isChecked = true
                }
            }
            discoveredList.addView(row)
        }
    }

    private fun bindPaired(statuses: List<SensorStatus>) {
        pairedList.removeAllViews()
        pairedStatusViews.clear()
        pairedEmpty.visibility = if (statuses.isEmpty()) View.VISIBLE else View.GONE

        statuses.forEach { status ->
            val row = layoutInflater.inflate(R.layout.item_cycling_sensor, pairedList, false)
            row.findViewById<TextView>(R.id.sensor_name).text = status.sensor.name
            val statusView: TextView = row.findViewById(R.id.sensor_status)
            statusView.text = describeStatus(status)
            pairedStatusViews[status.sensor.address] = statusView
            row.findViewById<ImageView>(R.id.sensor_action).setOnClickListener {
                hub.forget(status.sensor.address)
            }
            pairedList.addView(row)
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
        // A live figure is the only proof the sensor is actually reading: "Connected" is equally
        // true of a strap sitting in a jersey pocket. Until a value arrives the row names the kinds
        // instead, which is all that is honestly known.
        val readings = if (status.state == SensorConnectionState.CONNECTED) {
            liveReadings(status.sensor.kinds)
        } else {
            emptyList()
        }
        val what = readings.ifEmpty { listOf(describeKinds(status.sensor.kinds)) }
        return (listOf(state) + what + listOfNotNull(battery)).joinToString(" · ")
    }

    private fun liveReadings(kinds: Set<SensorKind>): List<String> = kinds.sorted().mapNotNull { kind ->
        when (kind) {
            SensorKind.HEART_RATE ->
                latestSnapshot.heartRateBpm?.let { "$it ${getString(R.string.cycling_unit_bpm)}" }
            SensorKind.CADENCE ->
                latestSnapshot.cadenceRpm?.let { "$it ${getString(R.string.cycling_unit_rpm)}" }
            SensorKind.SPEED -> latestSnapshot.speedMps?.let {
                "${CyclingFormatter.speedValue(it)} ${CyclingFormatter.speedUnit(requireContext())}"
            }
            SensorKind.POWER ->
                latestSnapshot.powerWatts?.let { "$it ${getString(R.string.cycling_unit_watts)}" }
        }
    }

    /** Words rather than dBm: -71 means nothing to a rider deciding which row is their own strap. */
    private fun describeSignal(rssi: Int): String = getString(
        when {
            rssi >= STRONG_SIGNAL_DBM -> R.string.cycling_signal_strong
            rssi >= GOOD_SIGNAL_DBM -> R.string.cycling_signal_good
            else -> R.string.cycling_signal_weak
        },
    )

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

    private companion object {
        // Roughly arm's length and roughly across a room, for a typical BLE sensor.
        const val STRONG_SIGNAL_DBM = -60
        const val GOOD_SIGNAL_DBM = -75
    }
}
