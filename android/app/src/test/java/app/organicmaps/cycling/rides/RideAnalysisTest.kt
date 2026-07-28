package app.organicmaps.cycling.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateZonesTest {

    private companion object {
        const val START = 1_700_000_000_000L
        const val MAX_HR = 190
    }

    private fun sample(offsetSeconds: Long, hr: Int?) = RideSample(
        timestampMs = START + offsetSeconds * 1000,
        latitude = 55.6,
        longitude = 13.0,
        altitudeMetres = null,
        gpsSpeedMps = 5.0,
        sensorSpeedMps = null,
        heartRateBpm = hr,
        cadenceRpm = null,
        powerWatts = null,
    )

    @Test
    fun `zone boundaries follow the fraction of max`() {
        // 190 max: Z1 from 95, Z2 from 114, Z3 from 133, Z4 from 152, Z5 from 171.
        assertNull(HeartRateZones.zoneFor(80, MAX_HR))
        assertEquals(HeartRateZone.Z1, HeartRateZones.zoneFor(100, MAX_HR))
        assertEquals(HeartRateZone.Z2, HeartRateZones.zoneFor(120, MAX_HR))
        assertEquals(HeartRateZone.Z3, HeartRateZones.zoneFor(140, MAX_HR))
        assertEquals(HeartRateZone.Z4, HeartRateZones.zoneFor(160, MAX_HR))
        assertEquals(HeartRateZone.Z5, HeartRateZones.zoneFor(180, MAX_HR))
    }

    @Test
    fun `time is attributed to the interval, not the sample count`() {
        // Realistic 10 s spacing: six intervals in Z2 (60 s), then three in Z4 (30 s). The point is
        // that six samples and three samples do not mean 2:1 - the intervals decide.
        val samples = (0..5).map { sample(it * 10L, 120) } + (6..9).map { sample(it * 10L, 160) }

        val zones = HeartRateZones.timeInZones(samples, MAX_HR)

        assertEquals(60_000L, zones[HeartRateZone.Z2])
        assertEquals(30_000L, zones[HeartRateZone.Z4])
        assertEquals(0L, zones[HeartRateZone.Z1])
    }

    @Test
    fun `a long gap between fixes is not attributed to any zone`() {
        // Ten minutes without a fix - a tunnel, or the phone asleep. Counting it would invent
        // ten minutes of training that never happened.
        val samples = listOf(sample(0, 160), sample(600, 160), sample(610, 160))

        val zones = HeartRateZones.timeInZones(samples, MAX_HR)

        assertEquals(10_000L, zones[HeartRateZone.Z4])
    }

    @Test
    fun `samples with no heart rate contribute nothing`() {
        val samples = listOf(sample(0, null), sample(10, null))

        assertTrue(HeartRateZones.timeInZones(samples, MAX_HR).values.all { it == 0L })
    }
}

class PersonalRecordsTest {

    private companion object {
        const val START = 1_700_000_000_000L
        /** 0.001 degrees of latitude is about 111 m. */
        const val STEP_DEGREES = 0.001
        const val STEP_METRES = 111.0
    }

    /** A ride heading due north, one sample per [secondsPerStep], each step ~111 m. */
    private fun ride(steps: Int, secondsPerStep: Long) = (0..steps).map { index ->
        RideSample(
            timestampMs = START + index * secondsPerStep * 1000,
            latitude = 55.0 + index * STEP_DEGREES,
            longitude = 13.0,
            altitudeMetres = null,
            gpsSpeedMps = null,
            sensorSpeedMps = null,
            heartRateBpm = null,
            cadenceRpm = null,
            powerWatts = null,
        )
    }

    @Test
    fun `a ride shorter than the target has no record`() {
        // 10 steps is only ~1.1 km.
        assertNull(PersonalRecords.fastestOverDistance(ride(10, 10), 5_000.0))
    }

    @Test
    fun `covers the target distance in the expected time`() {
        // 45 steps of ~111 m is ~5 km; at 10 s per step that is ~450 s.
        val best = PersonalRecords.fastestOverDistance(ride(50, 10), 5_000.0)!!

        assertEquals(450_000.0, best.toDouble(), 20_000.0)
    }

    @Test
    fun `finds the quick stretch inside a slow ride`() {
        // Slow for 30 steps, then a fast burst, then slow again. The record must come from the
        // burst, not from the ride's overall pace - this is the whole point of a sliding window.
        val slowStart = (0..30).map { index ->
            RideSample(
                START + index * 20_000L, 55.0 + index * STEP_DEGREES, 13.0,
                null, null, null, null, null, null,
            )
        }
        val burstStartTime = slowStart.last().timestampMs
        val burstStartLat = slowStart.last().latitude
        val burst = (1..20).map { index ->
            RideSample(
                burstStartTime + index * 5_000L, burstStartLat + index * STEP_DEGREES, 13.0,
                null, null, null, null, null, null,
            )
        }

        val best = PersonalRecords.fastestOverDistance(slowStart + burst, 2_000.0)!!

        // ~18 steps at 5 s each covers 2 km, so about 90 s. The slow section would give ~360 s.
        assertTrue("expected the burst, got ${best / 1000}s", best < 150_000L)
    }

    @Test
    fun `tracked distances are sensible and ascending`() {
        assertEquals(listOf(5_000.0, 10_000.0, 20_000.0, 40_000.0), PersonalRecords.TRACKED_DISTANCES)
    }
}
