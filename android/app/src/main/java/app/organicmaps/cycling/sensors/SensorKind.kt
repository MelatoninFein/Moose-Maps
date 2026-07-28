package app.organicmaps.cycling.sensors

import java.util.UUID

/**
 * The kinds of measurement this app understands. A single physical sensor can report several of
 * them: a CYCPLUS C3 in dual mode advertises the CSC service and sends both wheel and crank data,
 * while a power meter reports power and usually cadence from the same characteristic.
 */
enum class SensorKind {
    HEART_RATE,
    CADENCE,
    SPEED,
    POWER,
    ;

    companion object {

        /** Measurement kinds a GATT service can produce, used to label a device before it is connected. */
        fun ofService(service: UUID): Set<SensorKind> = when (service) {
            GattProfiles.HEART_RATE_SERVICE -> setOf(HEART_RATE)
            GattProfiles.CYCLING_SPEED_CADENCE_SERVICE -> setOf(SPEED, CADENCE)
            GattProfiles.CYCLING_POWER_SERVICE -> setOf(POWER, CADENCE)
            else -> emptySet()
        }
    }
}
