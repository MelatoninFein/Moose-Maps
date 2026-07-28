package app.organicmaps.cycling.sensors

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.organicmaps.sdk.util.log.Logger
import java.util.UUID

/**
 * Owns every sensor connection and merges what they report into one [SensorSnapshot].
 *
 * A process-wide singleton rather than a ViewModel: connections must outlive the map activity so a
 * rider can lock the screen, take a call, or drop into picture-in-picture without the heart-rate
 * strap dropping too. [SensorService] keeps the process alive while a ride is in progress.
 *
 * Sensors report cumulative counters, not rates, so the derived values live in [RevolutionCounter]
 * instances kept per device and per data field.
 */
class SensorHub private constructor(context: Context) : SensorLink.Listener {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    val store = SensorStore(appContext)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val links = mutableMapOf<String, SensorLink>()
    private val contributions = mutableMapOf<String, Contribution>()
    private val counters = mutableMapOf<String, DeviceCounters>()

    /**
     * In-memory mirror of the stored pairing list. Sensor callbacks arrive several times a second
     * and each one can republish the status list; re-reading and re-parsing the JSON that often
     * would be pointless work on a Bluetooth callback thread.
     */
    @Volatile
    private var pairedSensors: List<PairedSensor> = store.loadPairedSensors()

    private val _snapshot = MutableLiveData(SensorSnapshot.EMPTY)
    val snapshot: LiveData<SensorSnapshot> = _snapshot

    private val _statuses = MutableLiveData<List<SensorStatus>>(emptyList())
    val statuses: LiveData<List<SensorStatus>> = _statuses

    private var scanner: SensorScanner? = null
    private var running = false

    private val expiryTick = object : Runnable {
        override fun run() {
            expireStaleReadings()
            handler.postDelayed(this, EXPIRY_TICK_MS)
        }
    }

    val isRunning: Boolean
        get() = running

    /** Opens connections to every paired sensor. Safe to call repeatedly. */
    @MainThread
    fun start() {
        if (running) {
            return
        }
        if (!SensorPermissions.hasConnectPermissions(appContext)) {
            Logger.w(TAG, "Not starting sensors: BLUETOOTH_CONNECT not granted")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Logger.i(TAG, "Not starting sensors: Bluetooth unavailable or off")
            return
        }

        running = true
        pairedSensors = store.loadPairedSensors()
        pairedSensors.forEach { connect(it) }
        publishStatuses()
        handler.postDelayed(expiryTick, EXPIRY_TICK_MS)
    }

    /** Closes every connection and clears the live readout. */
    @MainThread
    fun stop() {
        if (!running) {
            return
        }
        running = false
        handler.removeCallbacks(expiryTick)
        links.values.forEach { it.disconnect() }
        links.clear()
        synchronized(contributions) {
            contributions.clear()
            counters.clear()
        }
        _snapshot.postValue(SensorSnapshot.EMPTY)
        publishStatuses()
    }

    @MainThread
    fun pair(sensor: DiscoveredSensor) {
        val paired = PairedSensor(sensor.address, sensor.name, sensor.kinds)
        pairedSensors = pairedSensors.filterNot { it.address == paired.address } + paired
        store.savePairedSensors(pairedSensors)
        if (running) {
            connect(paired)
        }
        publishStatuses()
    }

    @MainThread
    fun forget(address: String) {
        pairedSensors = pairedSensors.filterNot { it.address == address }
        store.savePairedSensors(pairedSensors)
        links.remove(address)?.disconnect()
        synchronized(contributions) {
            contributions.remove(address)
            counters.remove(address)
        }
        recomputeSnapshot()
        publishStatuses()
    }

    /** Starts a discovery scan. Returns false when Bluetooth is off or permissions are missing. */
    @MainThread
    fun startScan(listener: SensorScanner.Listener, onFinished: () -> Unit): Boolean {
        if (!SensorPermissions.hasScanPermissions(appContext)) {
            return false
        }
        val adapter = bluetoothAdapter ?: return false
        val activeScanner = scanner ?: SensorScanner(adapter).also { scanner = it }
        return activeScanner.start(listener, onFinished)
    }

    @MainThread
    fun stopScan() {
        scanner?.stop()
    }

    fun isPaired(address: String): Boolean = pairedSensors.any { it.address == address }

    private fun connect(sensor: PairedSensor) {
        if (links.containsKey(sensor.address)) {
            return
        }
        val device = try {
            bluetoothAdapter?.getRemoteDevice(sensor.address)
        } catch (e: IllegalArgumentException) {
            // A stored address can become invalid if the preferences file was edited or restored.
            Logger.e(TAG, "Invalid sensor address ${sensor.address}", e)
            null
        } ?: return

        val link = SensorLink(appContext, device, this)
        links[sensor.address] = link
        synchronized(contributions) {
            contributions[sensor.address] = Contribution()
            counters[sensor.address] = DeviceCounters()
        }
        link.connect()
    }

    // region SensorLink.Listener - callbacks arrive on a Bluetooth binder thread.

    override fun onStateChanged(address: String, state: SensorConnectionState) {
        synchronized(contributions) {
            contributions[address]?.let { contribution ->
                contribution.state = state
                if (state != SensorConnectionState.CONNECTED) {
                    // Stop reporting values from a sensor that is no longer there, and drop the
                    // counter baselines so a reconnect doesn't differentiate against stale counts.
                    contribution.clearReadings()
                    counters[address]?.reset()
                }
            }
        }
        recomputeSnapshot()
        publishStatuses()
    }

    override fun onMeasurement(address: String, characteristic: UUID, data: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        synchronized(contributions) {
            val contribution = contributions[address] ?: return
            val deviceCounters = counters[address] ?: return
            when (characteristic) {
                GattProfiles.HEART_RATE_MEASUREMENT -> applyHeartRate(contribution, data, now)
                GattProfiles.CSC_MEASUREMENT -> applyCsc(contribution, deviceCounters, data, now)
                GattProfiles.CYCLING_POWER_MEASUREMENT -> applyPower(contribution, deviceCounters, data, now)
                else -> return
            }
        }
        recomputeSnapshot()
    }

    override fun onBatteryLevel(address: String, percent: Int) {
        synchronized(contributions) { contributions[address]?.batteryPercent = percent }
        publishStatuses()
    }

    // endregion

    private fun applyHeartRate(contribution: Contribution, data: ByteArray, now: Long) {
        val reading = GattMeasurements.parseHeartRate(data) ?: return
        // A strap reporting explicit loss of skin contact publishes garbage; drop it rather than
        // showing the rider a number that isn't their heart rate.
        if (reading.contactDetected == false) {
            contribution.heartRateBpm = null
            return
        }
        contribution.heartRateBpm = reading.bpm
        contribution.heartRateAtMs = now
    }

    private fun applyCsc(contribution: Contribution, deviceCounters: DeviceCounters, data: ByteArray, now: Long) {
        val reading = GattMeasurements.parseCyclingSpeedCadence(data) ?: return

        val wheelRevolutions = reading.wheelRevolutions
        val wheelEventTime = reading.lastWheelEventTime
        if (wheelRevolutions != null && wheelEventTime != null) {
            deviceCounters.cscWheel.update(wheelRevolutions, wheelEventTime, now)?.let { revolutionsPerSecond ->
                contribution.speedMps = revolutionsPerSecond * store.wheelCircumferenceMm / MM_PER_METRE
                contribution.speedAtMs = now
                contribution.speedRank = RANK_CSC
            }
        }

        val crankRevolutions = reading.crankRevolutions
        val crankEventTime = reading.lastCrankEventTime
        if (crankRevolutions != null && crankEventTime != null) {
            deviceCounters.cscCrank.update(crankRevolutions.toLong(), crankEventTime, now)?.let { rps ->
                contribution.cadenceRpm = (rps * SECONDS_PER_MINUTE).toInt()
                contribution.cadenceAtMs = now
                contribution.cadenceRank = RANK_CSC
            }
        }
    }

    private fun applyPower(contribution: Contribution, deviceCounters: DeviceCounters, data: ByteArray, now: Long) {
        val reading = GattMeasurements.parseCyclingPower(data) ?: return

        contribution.powerWatts = reading.watts
        contribution.powerAtMs = now

        val wheelRevolutions = reading.wheelRevolutions
        val wheelEventTime = reading.lastWheelEventTime
        if (wheelRevolutions != null && wheelEventTime != null) {
            deviceCounters.powerWheel.update(wheelRevolutions, wheelEventTime, now)?.let { revolutionsPerSecond ->
                contribution.speedMps = revolutionsPerSecond * store.wheelCircumferenceMm / MM_PER_METRE
                contribution.speedAtMs = now
                contribution.speedRank = RANK_POWER
            }
        }

        val crankRevolutions = reading.crankRevolutions
        val crankEventTime = reading.lastCrankEventTime
        if (crankRevolutions != null && crankEventTime != null) {
            deviceCounters.powerCrank.update(crankRevolutions.toLong(), crankEventTime, now)?.let { rps ->
                contribution.cadenceRpm = (rps * SECONDS_PER_MINUTE).toInt()
                contribution.cadenceAtMs = now
                contribution.cadenceRank = RANK_POWER
            }
        }
    }

    private fun expireStaleReadings() {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        synchronized(contributions) {
            contributions.values.forEach { contribution ->
                changed = contribution.expire(now) || changed
            }
        }
        if (changed) {
            recomputeSnapshot()
        }
    }

    /**
     * Merges the per-device contributions. Where two sensors report the same metric - a power meter
     * and a dedicated cadence sensor both send crank data - the dedicated sensor wins, because it
     * measures the crank directly instead of inferring it.
     */
    private fun recomputeSnapshot() {
        val merged = synchronized(contributions) {
            val values = contributions.values.filter { it.state == SensorConnectionState.CONNECTED }
            SensorSnapshot(
                heartRateBpm = values.firstNotNullOfOrNull { it.heartRateBpm },
                cadenceRpm = values.filter { it.cadenceRpm != null }
                    .minByOrNull { it.cadenceRank }?.cadenceRpm,
                speedMps = values.filter { it.speedMps != null }
                    .minByOrNull { it.speedRank }?.speedMps,
                powerWatts = values.firstNotNullOfOrNull { it.powerWatts },
            )
        }
        _snapshot.postValue(merged)
    }

    private fun publishStatuses() {
        val statusList = synchronized(contributions) {
            pairedSensors.map { sensor ->
                val contribution = contributions[sensor.address]
                SensorStatus(
                    sensor = sensor,
                    state = contribution?.state ?: SensorConnectionState.DISCONNECTED,
                    batteryPercent = contribution?.batteryPercent,
                )
            }
        }
        _statuses.postValue(statusList)
    }

    /** Live values from a single device, plus when each arrived so it can be expired. */
    private class Contribution {
        var state: SensorConnectionState = SensorConnectionState.CONNECTING

        var batteryPercent: Int? = null

        var heartRateBpm: Int? = null
        var heartRateAtMs: Long = 0

        var cadenceRpm: Int? = null
        var cadenceAtMs: Long = 0
        var cadenceRank: Int = RANK_POWER

        var speedMps: Double? = null
        var speedAtMs: Long = 0
        var speedRank: Int = RANK_POWER

        var powerWatts: Int? = null
        var powerAtMs: Long = 0

        fun clearReadings() {
            heartRateBpm = null
            cadenceRpm = null
            speedMps = null
            powerWatts = null
        }

        /** Drops readings that stopped arriving. Returns true when anything was cleared. */
        fun expire(now: Long): Boolean {
            var changed = false
            if (heartRateBpm != null && now - heartRateAtMs > READING_TIMEOUT_MS) {
                heartRateBpm = null
                changed = true
            }
            if (cadenceRpm != null && now - cadenceAtMs > READING_TIMEOUT_MS) {
                cadenceRpm = null
                changed = true
            }
            if (speedMps != null && now - speedAtMs > READING_TIMEOUT_MS) {
                speedMps = null
                changed = true
            }
            if (powerWatts != null && now - powerAtMs > READING_TIMEOUT_MS) {
                powerWatts = null
                changed = true
            }
            return changed
        }
    }

    /** One counter per (device, field): the two profiles use different time resolutions. */
    private class DeviceCounters {
        val cscWheel = RevolutionCounter.forWheel(RevolutionCounter.CSC_WHEEL_TIME_RESOLUTION_HZ)
        val cscCrank = RevolutionCounter.forCrank()
        val powerWheel = RevolutionCounter.forWheel(RevolutionCounter.POWER_WHEEL_TIME_RESOLUTION_HZ)
        val powerCrank = RevolutionCounter.forCrank()

        fun reset() {
            cscWheel.reset()
            cscCrank.reset()
            powerWheel.reset()
            powerCrank.reset()
        }
    }

    companion object {
        private const val TAG = "SensorHub"

        private const val MM_PER_METRE = 1000.0
        private const val SECONDS_PER_MINUTE = 60.0

        /** A reading with no update for this long is dropped rather than shown as current. */
        private const val READING_TIMEOUT_MS = 10_000L
        private const val EXPIRY_TICK_MS = 1_000L

        // Lower rank wins when two sensors report the same metric.
        private const val RANK_CSC = 0
        private const val RANK_POWER = 1

        @Volatile
        private var instance: SensorHub? = null

        @JvmStatic
        fun from(context: Context): SensorHub = instance ?: synchronized(this) {
            instance ?: SensorHub(context).also { instance = it }
        }
    }
}
