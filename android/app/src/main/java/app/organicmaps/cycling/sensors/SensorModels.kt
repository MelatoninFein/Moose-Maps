package app.organicmaps.cycling.sensors

/** A sensor the user has paired. Persisted by [SensorStore] and reconnected automatically. */
data class PairedSensor(
    /** Bluetooth MAC address - the stable identity of the device. */
    val address: String,
    val name: String,
    /** Measurement kinds advertised when the sensor was paired, used to label it while disconnected. */
    val kinds: Set<SensorKind>,
)

/** A sensor seen during a scan but not yet paired. */
data class DiscoveredSensor(
    val address: String,
    val name: String,
    val kinds: Set<SensorKind>,
    val rssi: Int,
)

enum class SensorConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/** Per-device status shown in the sensor settings screen. */
data class SensorStatus(
    val sensor: PairedSensor,
    val state: SensorConnectionState,
    val batteryPercent: Int? = null,
)

/**
 * The current value of every metric, merged across all connected sensors. Values are null when no
 * connected sensor provides them.
 *
 * Speed is in metres per second; the UI converts it using the app's measurement-units setting.
 */
data class SensorSnapshot(
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    val speedMps: Double? = null,
    val powerWatts: Int? = null,
) {

    val hasAnyReading: Boolean
        get() = heartRateBpm != null || cadenceRpm != null || speedMps != null || powerWatts != null

    companion object {
        val EMPTY = SensorSnapshot()
    }
}
