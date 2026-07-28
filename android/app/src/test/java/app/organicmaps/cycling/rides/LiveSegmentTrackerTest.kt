package app.organicmaps.cycling.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine that runs while you are actually racing a segment.
 *
 * It had no tests despite being deliberately kept free of Android types so it could have them, and
 * it is the piece where a mistake is least recoverable: a lap timed wrongly cannot be re-ridden.
 */
class LiveSegmentTrackerTest {

    // A straight line of eleven points about 50 m apart. Wider than the 40 m corridor on purpose:
    // the rider then advances exactly one point per fix, so the expected fraction is unambiguous.
    private val points = (0..10).map { SegmentPoint(55.400 + it * 0.00045, 13.000) }

    /** Feeds fixes the way a 1 Hz recording does - every point in turn, none skipped. */
    private fun LiveSegmentTracker.rideThrough(
        range: IntProgression,
        millisPerPoint: Long,
        startMillis: Long = 0,
    ): LiveSegmentProgress? {
        var last: LiveSegmentProgress? = null
        range.forEach { index ->
            last = onPosition(
                points[index].latitude,
                points[index].longitude,
                startMillis + index * millisPerPoint,
            )
        }
        return last
    }
    private val straight = Segment(id = "s", name = "Sea road", points = points)
    private val loop = Segment(
        id = "l",
        name = "Höllviken runt",
        // First and last coincide, which is what makes it a loop.
        points = points + listOf(points.first()),
    )

    private fun tracker(segment: Segment, bests: Map<String, SegmentBest> = emptyMap()) =
        LiveSegmentTracker(listOf(segment), bests)

    @Test
    fun `nothing happens away from a segment`() {
        val tracker = tracker(straight)
        assertNull(tracker.onPosition(55.500, 13.500, 0))
    }

    @Test
    fun `crossing the start gate begins timing`() {
        val tracker = tracker(straight)
        val progress = tracker.onPosition(points.first().latitude, points.first().longitude, 1_000)

        assertNotNull(progress)
        assertEquals("Sea road", progress?.segmentName)
        assertEquals(0L, progress?.elapsedMillis)
        assertEquals(0f, progress?.fractionComplete)
        assertFalse(progress?.finished ?: true)
    }

    @Test
    fun `progress advances with the rider and completes at the line`() {
        val tracker = tracker(straight)

        val halfway = tracker.rideThrough(0..5, millisPerPoint = 6_000)
        assertEquals(0.5f, halfway?.fractionComplete)
        assertEquals(30_000L, halfway?.elapsedMillis)
        assertFalse(halfway?.finished ?: true)

        val end = tracker.rideThrough(6..10, millisPerPoint = 6_000)
        assertEquals(1f, end?.fractionComplete)
        assertTrue(end?.finished ?: false)
    }

    @Test
    fun `a segment ridden to the end releases the tracker`() {
        val tracker = tracker(straight)
        tracker.rideThrough(0..10, millisPerPoint = 6_000)

        // Away from the course again, so nothing is being timed.
        assertNull(tracker.onPosition(55.500, 13.500, 70_000))
    }

    @Test
    fun `turning back partway through never finishes the segment`() {
        val tracker = tracker(straight)
        tracker.rideThrough(0..5, millisPerPoint = 6_000)

        // Retracing your steps must not be mistaken for reaching the far end.
        val backAtStart = tracker.rideThrough(5 downTo 0, millisPerPoint = 6_000, startMillis = 60_000)
        assertFalse(backAtStart?.finished ?: true)
    }

    @Test
    fun `four laps of a loop are timed as four laps`() {
        val tracker = tracker(loop)
        val lapTimes = mutableListOf<Long>()

        var clock = 0L
        tracker.onPosition(points.first().latitude, points.first().longitude, clock)
        repeat(4) { lap ->
            // Ride round: through the middle, then back over the line.
            points.forEach { point ->
                clock += 5_000
                tracker.onPosition(point.latitude, point.longitude, clock)
            }
            clock += 5_000
            val crossing = tracker.onPosition(points.first().latitude, points.first().longitude, clock)
            assertTrue("lap ${lap + 1} should finish at the line", crossing?.finished ?: false)
            lapTimes += crossing?.elapsedMillis ?: 0L
        }

        assertEquals(4, lapTimes.size)
        // Each lap is timed from the previous crossing, not from the first one: without the reset
        // the times would grow lap on lap.
        assertTrue("laps should not accumulate: $lapTimes", lapTimes.max() - lapTimes.min() < 5_000)
    }

    @Test
    fun `the gap to your best is reported once there is a best`() {
        // Best lap reached the halfway point at 20 s; this rider takes 30 s, so is 10 s down.
        val best = SegmentBest(
            segmentId = "s",
            totalMillis = 40_000,
            splitsMillis = points.indices.map { it * 4_000L },
            achievedAtMs = 0,
        )
        val tracker = tracker(straight, mapOf("s" to best))

        val halfway = tracker.rideThrough(0..5, millisPerPoint = 6_000)

        assertEquals(10_000L, halfway?.deltaMillis)
    }

    @Test
    fun `with no recorded best there is no gap to report`() {
        val tracker = tracker(straight)

        assertNull(tracker.rideThrough(0..5, millisPerPoint = 6_000)?.deltaMillis)
    }
}
