package app.organicmaps.cycling.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import app.organicmaps.sdk.util.log.Logger

/**
 * Scans for cycling sensors, filtered to the GATT services this app can actually read.
 *
 * Filtering by service UUID rather than by name is what makes a CYCPLUS C3 and a Wahoo cadence
 * sensor equally discoverable: the profile is standard even when the branding isn't.
 *
 * The scan stops itself after [SCAN_DURATION_MS] - an unbounded BLE scan is a real battery drain
 * and Android throttles apps that leave one running.
 */
@SuppressLint("MissingPermission")
class SensorScanner(private val adapter: BluetoothAdapter) {

    fun interface Listener {
        fun onSensorFound(sensor: DiscoveredSensor)
    }

    private val handler = Handler(Looper.getMainLooper())

    // Held as a field, not a method reference: `::stop` allocates a fresh instance on every use, so
    // removeCallbacks would never match what postDelayed scheduled.
    private val stopRunnable = Runnable { stop() }

    private var listener: Listener? = null
    private var onFinished: (() -> Unit)? = null
    private var scanning = false

    val isScanning: Boolean
        get() = scanning

    /** Starts a time-boxed scan. Returns false when Bluetooth is off or the scanner is unavailable. */
    fun start(listener: Listener, onFinished: () -> Unit): Boolean {
        if (scanning) {
            return true
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null || !adapter.isEnabled) {
            return false
        }

        this.listener = listener
        this.onFinished = onFinished
        scanning = true

        val filters = GattProfiles.SUPPORTED_SERVICES.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            scanner.startScan(filters, settings, scanCallback)
            handler.postDelayed(stopRunnable, SCAN_DURATION_MS)
            true
        } catch (e: IllegalStateException) {
            // Bluetooth can be turned off between the isEnabled check and the call.
            Logger.w(TAG, "Cannot start scan: ${e.message}")
            scanning = false
            false
        }
    }

    fun stop() {
        if (!scanning) {
            return
        }
        scanning = false
        handler.removeCallbacks(stopRunnable)
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Cannot stop scan: ${e.message}")
        }
        onFinished?.invoke()
        listener = null
        onFinished = null
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val advertised = record?.serviceUuids.orEmpty().map { it.uuid }
            val kinds = advertised.filter { it in GattProfiles.SUPPORTED_SERVICES }
                .flatMap { SensorKind.ofService(it) }
                .toSet()
            if (kinds.isEmpty()) {
                return
            }

            val name = record?.deviceName?.takeIf { it.isNotBlank() } ?: result.device.address
            handler.post {
                listener?.onSensorFound(DiscoveredSensor(result.device.address, name, kinds, result.rssi))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.e(TAG, "BLE scan failed with error $errorCode")
            handler.post { stop() }
        }
    }

    companion object {
        private const val TAG = "SensorScanner"

        const val SCAN_DURATION_MS = 15_000L
    }
}
