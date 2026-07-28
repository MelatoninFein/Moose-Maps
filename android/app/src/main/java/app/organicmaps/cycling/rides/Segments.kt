package app.organicmaps.cycling.rides

/**
 * A named stretch of road you race yourself around - "Höllviken runt" and the like.
 *
 * Stored as the sequence of points it was defined from, usually lifted straight out of a ride you
 * have already done.
 */
data class Segment(
    val id: String,
    val name: String,
    val points: List<SegmentPoint>,
) {
    val lengthMetres: Double
        get() = points.zipWithNext().sumOf { (a, b) ->
            RideStatistics.haversineMetres(a.latitude, a.longitude, b.latitude, b.longitude)
        }
}

data class SegmentPoint(val latitude: Double, val longitude: Double)

/** One timed run around a segment. */
data class SegmentAttempt(
    val segmentId: String,
    val rideFileName: String,
    val startedAtMs: Long,
    val elapsedMillis: Long,
    val averageHeartRateBpm: Int?,
    val averagePowerWatts: Int?,
)

/**
 * Decides whether a ride went round a segment, and how long it took.
 *
 * The matching is deliberately forgiving: nobody rides the identical GPS line twice, and consumer
 * GPS wanders by several metres regardless. A ride counts as an attempt when it passes near the
 * start, then near the end, in that order, having stayed roughly on course in between.
 *
 * All pure arithmetic, so the rules can be tested without a device or a real ride.
 */
object SegmentMatcher {

    /** How close counts as "at" the start or end marker. */
    const val GATE_RADIUS_M = 25.0

    /**
     * A ride must come within this of most of the segment's shape to count. Loose enough for a
     * different lane or a wider line through a corner, tight enough to reject a parallel street.
     */
    const val CORRIDOR_RADIUS_M = 40.0

    /** Fraction of segment points the ride has to come near before it counts as the same route. */
    const val REQUIRED_COVERAGE = 0.8

    /**
     * Finds the fastest run around [segment] within [samples], or null if the ride never did it.
     *
     * Only the quickest qualifying pass is returned: riding a loop twice in one outing should
     * produce your best lap, not an arbitrary one.
     */
    fun bestAttempt(segment: Segment, samples: List<RideSample>): SegmentAttempt? {
        if (segment.points.size < 2 || samples.size < 2) {
            return null
        }
        val ordered = samples.sortedBy { it.timestampMs }
        val start = segment.points.first()
        val end = segment.points.last()

        // A 25 m gate swallows several consecutive samples, so group them into visits and take the
        // closest sample from each. Using the first sample inside the end gate instead would stop
        // the clock early and flatter every time.
        val startIndices = gateVisits(ordered, start)
        val endIndices = gateVisits(ordered, end)
        if (startIndices.isEmpty() || endIndices.isEmpty()) {
            return null
        }

        var best: SegmentAttempt? = null
        for (startIndex in startIndices) {
            val endIndex = endIndices.firstOrNull { it > startIndex } ?: continue
            val slice = ordered.subList(startIndex, endIndex + 1)
            if (!coversSegment(segment, slice)) {
                continue
            }
            val elapsed = slice.last().timestampMs - slice.first().timestampMs
            if (elapsed <= 0) {
                continue
            }
            if (best == null || elapsed < best.elapsedMillis) {
                val heartRates = slice.mapNotNull { it.heartRateBpm }
                val powers = slice.mapNotNull { it.powerWatts }
                best = SegmentAttempt(
                    segmentId = segment.id,
                    rideFileName = "",
                    startedAtMs = slice.first().timestampMs,
                    elapsedMillis = elapsed,
                    averageHeartRateBpm = if (heartRates.isEmpty()) null else heartRates.average().toInt(),
                    averagePowerWatts = if (powers.isEmpty()) null else powers.average().toInt(),
                )
            }
        }
        return best
    }

    /** True when the ride passed near enough of the segment's points to be the same route. */
    fun coversSegment(segment: Segment, slice: List<RideSample>): Boolean {
        if (segment.points.isEmpty()) {
            return false
        }
        val covered = segment.points.count { point ->
            slice.any { near(it, point, CORRIDOR_RADIUS_M) }
        }
        return covered.toDouble() / segment.points.size >= REQUIRED_COVERAGE
    }

    private fun near(sample: RideSample, point: SegmentPoint, radiusMetres: Double): Boolean =
        distanceTo(sample, point) <= radiusMetres

    private fun distanceTo(sample: RideSample, point: SegmentPoint): Double =
        RideStatistics.haversineMetres(sample.latitude, sample.longitude, point.latitude, point.longitude)

    /**
     * One index per pass through the gate: the sample closest to the marker in each run of
     * consecutive samples inside the radius.
     *
     * Riding a loop twice yields two entries, which is what makes lap-by-lap timing work.
     */
    private fun gateVisits(samples: List<RideSample>, gate: SegmentPoint): List<Int> {
        val visits = mutableListOf<Int>()
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE

        samples.indices.forEach { index ->
            val distance = distanceTo(samples[index], gate)
            if (distance <= GATE_RADIUS_M) {
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            } else if (bestIndex >= 0) {
                // Left the gate: that pass is finished.
                visits += bestIndex
                bestIndex = -1
                bestDistance = Double.MAX_VALUE
            }
        }
        if (bestIndex >= 0) {
            visits += bestIndex
        }
        return visits
    }

    /** Builds a segment from part of a ride - the usual way one gets created. */
    fun fromRide(id: String, name: String, samples: List<RideSample>): Segment =
        Segment(id, name, samples.map { SegmentPoint(it.latitude, it.longitude) })
}
