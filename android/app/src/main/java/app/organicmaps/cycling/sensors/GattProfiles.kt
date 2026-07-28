package app.organicmaps.cycling.sensors

import java.util.UUID

// Bluetooth SIG assigned numbers for the GATT services and characteristics used by cycling sensors.
// All 16-bit UUIDs expand into the Bluetooth Base UUID: 0000xxxx-0000-1000-8000-00805F9B34FB.
object GattProfiles {

    private const val BASE_UUID_FORMAT = "0000%04x-0000-1000-8000-00805f9b34fb"

    fun uuid16(assignedNumber: Int): UUID = UUID.fromString(BASE_UUID_FORMAT.format(assignedNumber))

    // Services.
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val CYCLING_SPEED_CADENCE_SERVICE: UUID = uuid16(0x1816)
    val CYCLING_POWER_SERVICE: UUID = uuid16(0x1818)
    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val DEVICE_INFORMATION_SERVICE: UUID = uuid16(0x180A)

    // Notifying measurement characteristics.
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val CSC_MEASUREMENT: UUID = uuid16(0x2A5B)
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16(0x2A63)

    // Readable characteristics.
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)
    val MANUFACTURER_NAME: UUID = uuid16(0x2A29)

    // Client Characteristic Configuration Descriptor - written to subscribe to notifications.
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid16(0x2902)

    // Every measurement characteristic this app subscribes to, keyed by the service that exposes it.
    val MEASUREMENTS: Map<UUID, UUID> = mapOf(
        HEART_RATE_SERVICE to HEART_RATE_MEASUREMENT,
        CYCLING_SPEED_CADENCE_SERVICE to CSC_MEASUREMENT,
        CYCLING_POWER_SERVICE to CYCLING_POWER_MEASUREMENT,
    )

    // Services worth connecting to. Advertised service UUIDs are matched against this set during scanning,
    // so a sensor is only offered to the user if we can actually read something from it.
    val SUPPORTED_SERVICES: Set<UUID> = MEASUREMENTS.keys
}
