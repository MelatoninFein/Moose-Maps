package app.organicmaps.cycling.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The Bluetooth permission model changed twice in the range of Android versions this app supports:
 *
 *  - API 21-30: BLUETOOTH / BLUETOOTH_ADMIN are install-time, but scanning additionally requires a
 *    *location* runtime permission, because a BLE scan can reveal where you are.
 *  - API 31+:   BLUETOOTH_SCAN / BLUETOOTH_CONNECT are runtime permissions, and location is no
 *    longer needed as long as the scan is declared `neverForLocation` in the manifest.
 *
 * The app already holds fine location for the map, so on older devices scanning usually needs no
 * extra prompt at all.
 */
object SensorPermissions {

    /** Runtime permissions that must be granted before scanning for sensors on this device. */
    val requiredForScan: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Runtime permissions needed to keep an already-paired sensor connected. */
    val requiredForConnect: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun hasScanPermissions(context: Context): Boolean = requiredForScan.all { context.isGranted(it) }

    fun hasConnectPermissions(context: Context): Boolean = requiredForConnect.all { context.isGranted(it) }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
