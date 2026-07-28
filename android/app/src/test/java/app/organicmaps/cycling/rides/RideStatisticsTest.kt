package app.organicmaps.cycling.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic a rider would notice getting wrong: averages skewed by stops, ascent inflated by
 * GPS noise, and missing sensors averaging out to zero instead of being absent.
 */
class RideStatisticsTest {

    private companion object {
        const val START = 1_700_000_000_000L
        const val DELTA = 0.5
    }

    private fun sample(
        offsetSeconds: Long,
        lat: Double,
        lon: Double = 0.0,
        altitude: Double? = null,
        gpsSpeed: Double? = null,
        hr: Int? = null,
        cadence: Int? = null,
        power: Int? = null,
    ) = RideSample(
        timestampMs = START + offsetSeconds * 1000,
        latitude = lat,
        longitude = lon,
        altitudeMetres = altitude,
        gpsSpeedMps = gpsSpeed,
        sensorSpeedMps = null,
        heartRateBpm = hr,
        cadenceRpm = cadence,
        powerWatts = power,
    )

    @Test
    fun `a single sample is not a ride`() {
        assertNull(RideStatistics.summarise(listOf(sample(0, 0.0))))
        assertNull(RideStatistics.summarise(emptyList()))
    }

    @Test
    fun `distance follows the track`() {
        // 0.01 degrees of latitude is very close to 1111 m anywhere on Earth.
        val summary = RideStatistics.summarise(
            listOf(sample(0, 0.0, gpsSpeed = 5.0), sample(60, 0.01, gpsSpeed = 5.0)),
        )!!

        assertEquals(1111.0, summary.distanceMetres, 5.0)
        assertEquals(2, summary.sampleCount)
    }

    @Test
    fun `a stop does not drag the average speed down`() {
        // Moving for 60 s, then stationary for 300 s at the same place.
        val samples = listOf(
            sample(0, 0.0, gpsSpeed = 5.0),
            sample(60, 0.01, gpsSpeed = 5.0),
            sample(360, 0.01, gpsSpeed = 0.0),
        )

        val summary = RideStatistics.summarise(samples)!!

        // Elapsed is six minutes, but only one was spent moving.
        assertEquals(360_000L, summary.elapsedMillis)
        assertEquals(60_000L, summary.movingMillis)
        // ~1111 m over 60 s of moving time, not over the full 360 s.
        assertEquals(18.5, summary.averageSpeedMps!!, DELTA)
    }

    @Test
    fun `altitude noise does not become ascent`() {
        // Wobbling by a metre or two, as consumer GPS does while sitting still.
        val samples = listOf(
            sample(0, 0.0, altitude = 100.0),
            sample(10, 0.001, altitude = 101.5),
            sample(20, 0.002, altitude = 99.0),
            sample(30, 0.003, altitude = 101.0),
            sample(40, 0.004, altitude = 100.5),
        )

        val summary = RideStatistics.summarise(samples)!!

        assertEquals(0.0, summary.ascentMetres, 0.001)
    }

    @Test
    fun `a real climb is counted`() {
        val samples = listOf(
            sample(0, 0.0, altitude = 100.0),
            sample(60, 0.01, altitude = 150.0),
            sample(120, 0.02, altitude = 200.0),
        )

        val summary = RideStatistics.summarise(samples)!!

        assertEquals(100.0, summary.ascentMetres, 0.001)
    }

    @Test
    fun `a descent is not counted as ascent`() {
        val samples = listOf(
            sample(0, 0.0, altitude = 200.0),
            sample(60, 0.01, altitude = 100.0),
        )

        assertEquals(0.0, RideStatistics.summarise(samples)!!.ascentMetres, 0.001)
    }

    @Test
    fun `absent sensors stay absent rather than averaging to zero`() {
        val samples = listOf(sample(0, 0.0, gpsSpeed = 5.0), sample(60, 0.01, gpsSpeed = 5.0))

        val summary = RideStatistics.summarise(samples)!!

        assertNull(summary.averageHeartRateBpm)
        assertNull(summary.maxHeartRateBpm)
        assertNull(summary.averageCadenceRpm)
        assertNull(summary.averagePowerWatts)
    }

    @Test
    fun `sensor averages ignore the samples that had no reading`() {
        // A strap that only connected partway through must not drag the average toward zero.
        val samples = listOf(
            sample(0, 0.0, gpsSpeed = 5.0),
            sample(60, 0.01, gpsSpeed = 5.0, hr = 140, cadence = 80, power = 200),
            sample(120, 0.02, gpsSpeed = 5.0, hr = 160, cadence = 90, power = 220),
        )

        val summary = RideStatistics.summarise(samples)!!

        assertEquals(150, summary.averageHeartRateBpm)
        assertEquals(160, summary.maxHeartRateBpm)
        assertEquals(85, summary.averageCadenceRpm)
        assertEquals(210, summary.averagePowerWatts)
    }

    @Test
    fun `samples arriving out of order are sorted before summarising`() {
        val samples = listOf(sample(60, 0.01, gpsSpeed = 5.0), sample(0, 0.0, gpsSpeed = 5.0))

        val summary = RideStatistics.summarise(samples)!!

        assertEquals(START, summary.startedAtMs)
        assertEquals(START + 60_000, summary.endedAtMs)
    }
}
