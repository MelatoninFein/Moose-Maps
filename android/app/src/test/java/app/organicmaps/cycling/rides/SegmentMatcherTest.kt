package app.organicmaps.cycling.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segment matching has to be forgiving enough that a normal repeat ride counts, and strict enough
 * that a parallel street doesn't. These tests pin both edges.
 */
class SegmentMatcherTest {

    private companion object {
        const val START = 1_700_000_000_000L
        /** ~1.1 m of latitude - a convenient nudge for building near-miss cases. */
        const val TINY = 0.00001
    }

    /** A straight run of 11 points heading north, about 111 m end to end. */
    private fun straightSegment(id: String = "seg", name: String = "Höllviken runt") =
        Segment(id, name, (0..10).map { SegmentPoint(55.4000 + it * 0.0001, 13.0000) })

    private fun ride(
        latitudes: List<Double>,
        longitude: Double = 13.0000,
        secondsApart: Long = 10,
        hr: Int? = null,
        power: Int? = null,
        startOffsetSeconds: Long = 0,
    ) = latitudes.mapIndexed { index, lat ->
        RideSample(
            timestampMs = START + (startOffsetSeconds + index * secondsApart) * 1000,
            latitude = lat,
            longitude = longitude,
            altitudeMetres = null,
            gpsSpeedMps = 8.0,
            sensorSpeedMps = null,
            heartRateBpm = hr,
            cadenceRpm = null,
            powerWatts = power,
        )
    }

    @Test
    fun `riding the segment produces an attempt with the elapsed time`() {
        val segment = straightSegment()
        val samples = ride((0..10).map { 55.4000 + it * 0.0001 }, hr = 150, power = 210)

        val attempt = SegmentMatcher.bestAttempt(segment, samples)

        assertNotNull(attempt)
        // 10 gaps of 10 s.
        assertEquals(100_000L, attempt!!.elapsedMillis)
        assertEquals(150, attempt.averageHeartRateBpm)
        assertEquals(210, attempt.averagePowerWatts)
    }

    @Test
    fun `a parallel street is not the segment`() {
        val segment = straightSegment()
        // Same shape, ~110 m east - well outside the corridor.
        val samples = ride((0..10).map { 55.4000 + it * 0.0001 }, longitude = 13.0018)

        assertNull(SegmentMatcher.bestAttempt(segment, samples))
    }

    @Test
    fun `a slightly different line still counts`() {
        val segment = straightSegment()
        // Wandering a few metres either side, as any real repeat ride does.
        val samples = (0..10).mapIndexed { index, _ ->
            RideSample(
                timestampMs = START + index * 10_000L,
                latitude = 55.4000 + index * 0.0001 + if (index % 2 == 0) TINY else -TINY,
                longitude = 13.0000 + if (index % 3 == 0) TINY else 0.0,
                altitudeMetres = null,
                gpsSpeedMps = 8.0,
                sensorSpeedMps = null,
                heartRateBpm = null,
                cadenceRpm = null,
                powerWatts = null,
            )
        }

        assertNotNull(SegmentMatcher.bestAttempt(segment, samples))
    }

    @Test
    fun `a ride that stops halfway does not count`() {
        val segment = straightSegment()
        // Turns back at the midpoint, so it never reaches the end gate.
        val samples = ride((0..5).map { 55.4000 + it * 0.0001 })

        assertNull(SegmentMatcher.bestAttempt(segment, samples))
    }

    @Test
    fun `two laps return the faster one`() {
        val segment = straightSegment()
        val slowLap = ride((0..10).map { 55.4000 + it * 0.0001 }, secondsApart = 20)
        val fastLap = ride(
            (0..10).map { 55.4000 + it * 0.0001 },
            secondsApart = 5,
            startOffsetSeconds = 1000,
        )

        val attempt = SegmentMatcher.bestAttempt(segment, slowLap + fastLap)

        assertNotNull(attempt)
        // The quick lap: 10 gaps of 5 s.
        assertEquals(50_000L, attempt!!.elapsedMillis)
    }

    @Test
    fun `riding it backwards does not count`() {
        val segment = straightSegment()
        val samples = ride((0..10).map { 55.4000 + (10 - it) * 0.0001 })

        // Start and end gates are hit in the wrong order, so there is no valid window.
        assertNull(SegmentMatcher.bestAttempt(segment, samples))
    }

    @Test
    fun `segment length is measured along its points`() {
        val segment = straightSegment()

        // 10 steps of 0.0001 degrees latitude, ~11.1 m each.
        assertEquals(111.0, segment.lengthMetres, 3.0)
    }

    @Test
    fun `partial coverage is rejected`() {
        val segment = straightSegment()
        // Hits both gates but cuts the middle out entirely.
        val samples = listOf(
            ride(listOf(55.4000)).first(),
            ride(listOf(55.4010), startOffsetSeconds = 60).first(),
        )

        assertTrue(!SegmentMatcher.coversSegment(segment, samples))
        assertNull(SegmentMatcher.bestAttempt(segment, samples))
    }
}
