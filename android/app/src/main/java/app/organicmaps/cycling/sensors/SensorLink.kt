package app.organicmaps.cycling.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import app.organicmaps.sdk.util.log.Logger
import java.util.UUID

/**
 * One GATT connection to one sensor.
 *
 * Android's GATT stack allows a single outstanding operation per connection, so subscribing to
 * several characteristics has to be serialised through a queue - firing the descriptor writes back
 * to back silently drops all but the first, which is the classic reason a dual-mode sensor only
 * ever reports one of its metrics.
 *
 * Callers are responsible for holding BLUETOOTH_CONNECT before calling [connect]; see
 * [SensorPermissions]. The permission lint is suppressed here rather than at every call site.
 */
@SuppressLint("MissingPermission")
class SensorLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val listener: Listener,
) {

    interface Listener {
        fun onStateChanged(address: String, state: SensorConnectionState)

        /** Raw payload of a measurement characteristic; parsing happens in [SensorHub]. */
        fun onMeasurement(address: String, characteristic: UUID, data: ByteArray)

        fun onBatteryLevel(address: String, percent: Int)
    }

    val address: String = device.address

    private var gatt: BluetoothGatt? = null

    private val operations = ArrayDeque<() -> Unit>()
    private var operationInFlight = false

    fun connect() {
        if (gatt != null) {
            return
        }
        listener.onStateChanged(address, SensorConnectionState.CONNECTING)
        // autoConnect = true lets the stack reconnect on its own when the sensor wakes up, which is
        // what a cadence sensor does every time the bike starts moving again.
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Pinning the transport avoids the stack guessing BR/EDR for a dual-mode sensor.
            device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, callback)
        }
    }

    fun disconnect() {
        synchronized(operations) {
            operations.clear()
            operationInFlight = false
        }
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        listener.onStateChanged(address, SensorConnectionState.DISCONNECTED)
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onStateChanged(address, SensorConnectionState.CONNECTED)
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(operations) {
                        operations.clear()
                        operationInFlight = false
                    }
                    listener.onStateChanged(address, SensorConnectionState.DISCONNECTED)
                    // The GATT object is kept: with autoConnect the stack retries by itself.
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.w(TAG, "Service discovery failed for $address, status $status")
                return
            }

            for ((serviceUuid, measurementUuid) in GattProfiles.MEASUREMENTS) {
                val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(measurementUuid) ?: continue
                enqueue { subscribe(gatt, characteristic) }
            }

            val battery = gatt.getService(GattProfiles.BATTERY_SERVICE)
                ?.getCharacteristic(GattProfiles.BATTERY_LEVEL)
            if (battery != null) {
                enqueue { gatt.readCharacteristic(battery) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.w(TAG, "Subscribe failed on $address for ${descriptor.characteristic.uuid}, status $status")
            }
            completeOperation()
        }

        // Deprecated on API 33+, but still the only callback delivered below it, so both overloads
        // are needed. The SDK-level check prevents handling the same packet twice on API 33+, where
        // the platform calls the value-carrying overload instead.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { listener.onMeasurement(address, characteristic.uuid, it) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener.onMeasurement(address, characteristic.uuid, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    characteristic.value?.let { handleRead(characteristic.uuid, it) }
                }
                completeOperation()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleRead(characteristic.uuid, value)
            }
            completeOperation()
        }
    }

    private fun handleRead(characteristic: UUID, value: ByteArray) {
        if (characteristic == GattProfiles.BATTERY_LEVEL) {
            GattMeasurements.parseBatteryLevel(value)?.let { listener.onBatteryLevel(address, it) }
        }
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Logger.w(TAG, "Cannot enable notifications on ${characteristic.uuid} for $address")
            completeOperation()
            return
        }

        val cccd = characteristic.getDescriptor(GattProfiles.CLIENT_CHARACTERISTIC_CONFIG)
        if (cccd == null) {
            // Without a CCCD the peer cannot be told to start notifying; nothing more to do.
            completeOperation()
            return
        }

        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, enable) == BLUETOOTH_STATUS_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = enable
                gatt.writeDescriptor(cccd)
            }
        }
        // onDescriptorWrite only arrives if the write was actually started.
        if (!started) {
            completeOperation()
        }
    }

    private fun enqueue(operation: () -> Unit) {
        synchronized(operations) { operations.addLast(operation) }
        dispatchNext()
    }

    private fun completeOperation() {
        synchronized(operations) { operationInFlight = false }
        dispatchNext()
    }

    private fun dispatchNext() {
        val next = synchronized(operations) {
            if (operationInFlight) return
            val operation = operations.removeFirstOrNull() ?: return
            operationInFlight = true
            operation
        }
        next()
    }

    companion object {
        private const val TAG = "SensorLink"

        // BluetoothStatusCodes.SUCCESS, inlined to avoid an API 33 class reference on older devices.
        private const val BLUETOOTH_STATUS_SUCCESS = 0
    }
}
