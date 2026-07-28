package app.organicmaps.cycling.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payloads here are written out byte by byte from the Bluetooth SIG characteristic definitions,
 * including the cases that break naive parsers: 16-bit heart rate, optional fields that shift every
 * following field, and truncated packets.
 */
class GattMeasurementsTest {

    // region Heart rate (0x2A37)

    @Test
    fun `heart rate with 8-bit value`() {
        // flags = 0x00 (uint8 value, no contact support, no energy, no RR), value = 0x48 = 72 bpm.
        val reading = GattMeasurements.parseHeartRate(bytes(0x00, 0x48))

        assertEquals(72, reading?.bpm)
        assertTrue(reading?.rrIntervals?.isEmpty() == true)
        assertNull(reading?.contactDetected)
    }

    @Test
    fun `heart rate with 16-bit value`() {
        // flags bit 0 set, so the value is a little-endian uint16: 0x012C = 300.
        val reading = GattMeasurements.parseHeartRate(bytes(0x01, 0x2C, 0x01))

        assertEquals(300, reading?.bpm)
    }

    @Test
    fun `heart rate reports contact loss only when the sensor supports it`() {
        // bit 2 (supported) set, bit 1 (detected) clear.
        assertEquals(false, GattMeasurements.parseHeartRate(bytes(0x04, 0x50))?.contactDetected)
        // bits 1 and 2 both set.
        assertEquals(true, GattMeasurements.parseHeartRate(bytes(0x06, 0x50))?.contactDetected)
        // Neither set: the feature is unsupported, which is not the same as "no contact".
        assertNull(GattMeasurements.parseHeartRate(bytes(0x00, 0x50))?.contactDetected)
    }

    @Test
    fun `heart rate skips energy expended before reading RR intervals`() {
        // flags 0x18 = energy expended (bit 3) + RR present (bit 4).
        // 72 bpm, 500 kJ, then two RR intervals of 1024 and 512 ticks (1/1024 s each).
        val reading = GattMeasurements.parseHeartRate(
            bytes(0x18, 0x48, 0xF4, 0x01, 0x00, 0x04, 0x00, 0x02),
        )

        assertEquals(72, reading?.bpm)
        assertEquals(listOf(1.0, 0.5), reading?.rrIntervals)
    }

    @Test
    fun `truncated heart rate packet is rejected`() {
        assertNull(GattMeasurements.parseHeartRate(bytes(0x00)))
        assertNull(GattMeasurements.parseHeartRate(ByteArray(0)))
        // Claims a 16-bit value but only supplies one byte of it.
        assertNull(GattMeasurements.parseHeartRate(bytes(0x01, 0x2C)))
    }

    // endregion

    // region Cycling speed and cadence (0x2A5B)

    @Test
    fun `csc with wheel data only`() {
        // flags 0x01, wheel revolutions = 0x000004D2 = 1234, event time = 0x0800 = 2048 ticks.
        val reading = GattMeasurements.parseCyclingSpeedCadence(
            bytes(0x01, 0xD2, 0x04, 0x00, 0x00, 0x00, 0x08),
        )

        assertEquals(1234L, reading?.wheelRevolutions)
        assertEquals(2048, reading?.lastWheelEventTime)
        assertNull(reading?.crankRevolutions)
    }

    @Test
    fun `csc with crank data only`() {
        // flags 0x02, crank revolutions = 0x0064 = 100, event time = 0x0400 = 1024 ticks.
        val reading = GattMeasurements.parseCyclingSpeedCadence(bytes(0x02, 0x64, 0x00, 0x00, 0x04))

        assertNull(reading?.wheelRevolutions)
        assertEquals(100, reading?.crankRevolutions)
        assertEquals(1024, reading?.lastCrankEventTime)
    }

    @Test
    fun `csc with both wheel and crank data`() {
        // A dual-mode sensor such as the CYCPLUS C3 sets both flags in one packet.
        val reading = GattMeasurements.parseCyclingSpeedCadence(
            bytes(0x03, 0xD2, 0x04, 0x00, 0x00, 0x00, 0x08, 0x64, 0x00, 0x00, 0x04),
        )

        assertEquals(1234L, reading?.wheelRevolutions)
        assertEquals(2048, reading?.lastWheelEventTime)
        assertEquals(100, reading?.crankRevolutions)
        assertEquals(1024, reading?.lastCrankEventTime)
    }

    @Test
    fun `csc packet carrying no data is rejected`() {
        assertNull(GattMeasurements.parseCyclingSpeedCadence(bytes(0x00)))
        // Wheel flag set but the payload stops short.
        assertNull(GattMeasurements.parseCyclingSpeedCadence(bytes(0x01, 0xD2, 0x04)))
    }

    // endregion

    // region Cycling power (0x2A63)

    @Test
    fun `power with no optional fields`() {
        // flags = 0x0000, instantaneous power = 0x00FA = 250 W.
        val reading = GattMeasurements.parseCyclingPower(bytes(0x00, 0x00, 0xFA, 0x00))

        assertEquals(250, reading?.watts)
        assertNull(reading?.crankRevolutions)
    }

    @Test
    fun `negative power is read as signed`() {
        // 0xFFF6 as sint16 is -10 W, which a trainer can report while coasting.
        val reading = GattMeasurements.parseCyclingPower(bytes(0x00, 0x00, 0xF6, 0xFF))

        assertEquals(-10, reading?.watts)
    }

    @Test
    fun `power skips preceding optional fields to reach crank data`() {
        // flags 0x0031 = pedal power balance (bit 0) + wheel data (bit 4) + crank data (bit 5).
        // This is the case that catches an implementation which assumes a fixed offset: the balance
        // byte shifts everything after it by one.
        val reading = GattMeasurements.parseCyclingPower(
            bytes(
                0x31, 0x00, // flags
                0xFA, 0x00, // 250 W
                0x32, // pedal power balance
                0xD2, 0x04, 0x00, 0x00, // wheel revolutions = 1234
                0x00, 0x08, // wheel event time = 2048 (1/2048 s units)
                0x64, 0x00, // crank revolutions = 100
                0x00, 0x04, // crank event time = 1024
            ),
        )

        assertEquals(250, reading?.watts)
        assertEquals(1234L, reading?.wheelRevolutions)
        assertEquals(2048, reading?.lastWheelEventTime)
        assertEquals(100, reading?.crankRevolutions)
        assertEquals(1024, reading?.lastCrankEventTime)
    }

    @Test
    fun `power skips accumulated torque before revolution data`() {
        // flags 0x0014 = accumulated torque (bit 2) + wheel data (bit 4).
        val reading = GattMeasurements.parseCyclingPower(
            bytes(0x14, 0x00, 0xFA, 0x00, 0x11, 0x22, 0xD2, 0x04, 0x00, 0x00, 0x00, 0x08),
        )

        assertEquals(250, reading?.watts)
        assertEquals(1234L, reading?.wheelRevolutions)
    }

    @Test
    fun `truncated power packet is rejected`() {
        assertNull(GattMeasurements.parseCyclingPower(bytes(0x00, 0x00)))
        // Crank flag set with nothing following.
        assertNull(GattMeasurements.parseCyclingPower(bytes(0x20, 0x00, 0xFA, 0x00)))
    }

    // endregion

    @Test
    fun `battery level accepts only a valid percentage`() {
        assertEquals(82, GattMeasurements.parseBatteryLevel(bytes(0x52)))
        assertEquals(0, GattMeasurements.parseBatteryLevel(bytes(0x00)))
        assertEquals(100, GattMeasurements.parseBatteryLevel(bytes(0x64)))
        // 0xFF = 255, outside the defined range.
        assertNull(GattMeasurements.parseBatteryLevel(bytes(0xFF)))
        assertNull(GattMeasurements.parseBatteryLevel(ByteArray(0)))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
