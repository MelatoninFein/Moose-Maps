package app.organicmaps.cycling.sensors

/**
 * Pure parsers for the GATT measurement payloads. They take the raw characteristic value and return
 * the fields we care about, or null when the packet is too short to be trusted.
 *
 * Everything here is deliberately free of Android types so it can be unit tested on the JVM.
 * All multi-byte fields are little-endian, as mandated by the Bluetooth Core Specification.
 */
object GattMeasurements {

    /** Instantaneous heart rate plus the optional beat-to-beat intervals, from characteristic 0x2A37. */
    data class HeartRate(
        val bpm: Int,
        /** Beat-to-beat intervals in seconds, oldest first. Empty when the sensor doesn't report them. */
        val rrIntervals: List<Double> = emptyList(),
        /** False when the strap reports it has lost skin contact; null when the sensor doesn't support it. */
        val contactDetected: Boolean? = null,
    )

    /**
     * Cumulative counters from characteristic 0x2A5B. The sensor never reports speed or cadence
     * directly - it reports revolution counts and the time of the last revolution, and the consumer
     * differentiates them (see [RideMetrics]).
     */
    data class CyclingSpeedCadence(
        val wheelRevolutions: Long? = null,
        /** Time of the last wheel event, in 1/1024 s units, wrapping at 2^16. */
        val lastWheelEventTime: Int? = null,
        val crankRevolutions: Int? = null,
        /** Time of the last crank event, in 1/1024 s units, wrapping at 2^16. */
        val lastCrankEventTime: Int? = null,
    )

    /**
     * Power, and optionally revolution counters, from characteristic 0x2A63. The optional fields are
     * variable-length and ordered by flag bit, so reaching the crank data means walking every
     * preceding field.
     *
     * Note the wheel event time resolution here is 1/2048 s - twice that of the CSC profile.
     */
    data class CyclingPower(
        val watts: Int,
        val wheelRevolutions: Long? = null,
        /** Time of the last wheel event, in 1/2048 s units, wrapping at 2^16. */
        val lastWheelEventTime: Int? = null,
        val crankRevolutions: Int? = null,
        /** Time of the last crank event, in 1/1024 s units, wrapping at 2^16. */
        val lastCrankEventTime: Int? = null,
    )

    fun parseHeartRate(data: ByteArray): HeartRate? {
        val reader = ByteReader(data)
        val flags = reader.uint8() ?: return null
        val isUint16 = flags and 0x01 != 0
        val bpm = (if (isUint16) reader.uint16() else reader.uint8()) ?: return null

        // Bits 1-2 form a two-bit enum: 0b00 and 0b01 mean the feature is unsupported.
        val contactSupported = flags and 0x04 != 0
        val contactDetected = if (contactSupported) flags and 0x02 != 0 else null

        if (flags and 0x08 != 0 && reader.uint16() == null) {
            return null // Energy expended field claimed but truncated.
        }

        val rrIntervals = mutableListOf<Double>()
        if (flags and 0x10 != 0) {
            // The RR field is a list that runs to the end of the packet.
            while (true) {
                val raw = reader.uint16() ?: break
                rrIntervals += raw / 1024.0
            }
        }

        return HeartRate(bpm, rrIntervals, contactDetected)
    }

    fun parseCyclingSpeedCadence(data: ByteArray): CyclingSpeedCadence? {
        val reader = ByteReader(data)
        val flags = reader.uint8() ?: return null

        var wheelRevolutions: Long? = null
        var lastWheelEventTime: Int? = null
        if (flags and 0x01 != 0) {
            wheelRevolutions = reader.uint32() ?: return null
            lastWheelEventTime = reader.uint16() ?: return null
        }

        var crankRevolutions: Int? = null
        var lastCrankEventTime: Int? = null
        if (flags and 0x02 != 0) {
            crankRevolutions = reader.uint16() ?: return null
            lastCrankEventTime = reader.uint16() ?: return null
        }

        // A packet with neither flag set carries no information.
        if (wheelRevolutions == null && crankRevolutions == null) {
            return null
        }
        return CyclingSpeedCadence(wheelRevolutions, lastWheelEventTime, crankRevolutions, lastCrankEventTime)
    }

    fun parseCyclingPower(data: ByteArray): CyclingPower? {
        val reader = ByteReader(data)
        val flags = reader.uint16() ?: return null
        val watts = reader.sint16() ?: return null

        // Optional fields appear in flag-bit order; skip the ones ahead of the revolution data.
        if (flags and (1 shl 0) != 0 && reader.skip(1) == null) return null // Pedal power balance.
        if (flags and (1 shl 2) != 0 && reader.skip(2) == null) return null // Accumulated torque.

        var wheelRevolutions: Long? = null
        var lastWheelEventTime: Int? = null
        if (flags and (1 shl 4) != 0) {
            wheelRevolutions = reader.uint32() ?: return null
            lastWheelEventTime = reader.uint16() ?: return null
        }

        var crankRevolutions: Int? = null
        var lastCrankEventTime: Int? = null
        if (flags and (1 shl 5) != 0) {
            crankRevolutions = reader.uint16() ?: return null
            lastCrankEventTime = reader.uint16() ?: return null
        }

        // Trailing optional fields are ignored, but power alone is already usable.
        return CyclingPower(watts, wheelRevolutions, lastWheelEventTime, crankRevolutions, lastCrankEventTime)
    }

    /** Battery level from characteristic 0x2A19, as a percentage. */
    fun parseBatteryLevel(data: ByteArray): Int? = ByteReader(data).uint8()?.takeIf { it in 0..100 }
}

/**
 * Little-endian cursor over a characteristic payload. Every read returns null instead of throwing
 * when the buffer runs out, so a malformed or truncated packet from a sensor degrades into a
 * dropped reading rather than a crash on the Bluetooth callback thread.
 */
private class ByteReader(private val data: ByteArray) {

    private var offset = 0

    fun skip(count: Int): Unit? {
        if (offset + count > data.size) return null
        offset += count
        return Unit
    }

    fun uint8(): Int? {
        if (offset + 1 > data.size) return null
        return data[offset++].toInt() and 0xFF
    }

    fun uint16(): Int? {
        if (offset + 2 > data.size) return null
        val value = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        return value
    }

    fun sint16(): Int? = uint16()?.let { if (it >= 0x8000) it - 0x10000 else it }

    fun uint32(): Long? {
        if (offset + 4 > data.size) return null
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        offset += 4
        return value
    }
}
