package app.organicmaps.cycling.rides

/** The classic five-zone model, as a fraction of maximum heart rate. */
enum class HeartRateZone(val lowerFraction: Double, val upperFraction: Double) {
    Z1(0.50, 0.60),
    Z2(0.60, 0.70),
    Z3(0.70, 0.80),
    Z4(0.80, 0.90),
    Z5(0.90, 1.10),
    ;

    fun lowerBpm(maxBpm: Int): Int = Math.round(maxBpm * lowerFraction).toInt()

    fun upperBpm(maxBpm: Int): Int = Math.round(maxBpm * upperFraction).toInt()
}

/**
 * Time spent in each heart-rate zone.
 *
 * Time is attributed to the interval *between* samples rather than counting samples, because GPS
 * fixes do not arrive at a fixed rate - a dropped fix or a tunnel would otherwise silently
 * under-count a zone.
 */
object HeartRateZones {

    /** Anything longer than this between fixes is a gap, not riding, and is not attributed. */
    private const val MAX_GAP_MS = 30_000L

    fun timeInZones(samples: List<RideSample>, maxHeartRateBpm: Int): Map<HeartRateZone, Long> {
        val result = HeartRateZone.entries.associateWith { 0L }.toMutableMap()
        if (samples.size < 2 || maxHeartRateBpm <= 0) {
            return result
        }

        val ordered = samples.sortedBy { it.timestampMs }
        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val heartRate = previous.heartRateBpm ?: continue
            val delta = ordered[i].timestampMs - previous.timestampMs
            if (delta <= 0 || delta > MAX_GAP_MS) {
                continue
            }
            val zone = zoneFor(heartRate, maxHeartRateBpm) ?: continue
            result[zone] = (result[zone] ?: 0L) + delta
        }
        return result
    }

    /** Null below zone 1 - warming up or freewheeling is not training. */
    fun zoneFor(heartRateBpm: Int, maxHeartRateBpm: Int): HeartRateZone? {
        if (maxHeartRateBpm <= 0) {
            return null
        }
        val fraction = heartRateBpm.toDouble() / maxHeartRateBpm
        return HeartRateZone.entries.lastOrNull { fraction >= it.lowerFraction }
    }
}

/** A best-ever figure, and which ride produced it. */
data class PersonalRecord(
    val label: String,
    val value: Double,
    val rideFileName: String,
    val achievedAtMs: Long,
)

/**
 * Bests across every recorded ride.
 *
 * The distance records are the interesting ones: the fastest you have ever covered 10 km is not
 * the same as your fastest 10 km *ride*, because the best stretch can sit anywhere inside a longer
 * outing. That needs a sliding window, which is what [fastestOverDistance] does.
 */
object PersonalRecords {

    /** Distances worth tracking, in metres. */
    val TRACKED_DISTANCES = listOf(5_000.0, 10_000.0, 20_000.0, 40_000.0)

    /**
     * Shortest time in which [targetMetres] was ever covered within this ride, or null if the ride
     * never reached that distance.
     *
     * Two pointers over the samples: extend the window until it spans the distance, then pull the
     * tail forward while it still does. Linear rather than quadratic, which matters on a four-hour
     * ride of ~14,000 samples.
     */
    fun fastestOverDistance(samples: List<RideSample>, targetMetres: Double): Long? {
        if (samples.size < 2) {
            return null
        }
        val ordered = samples.sortedBy { it.timestampMs }

        // Cumulative distance at each sample, so any window length is a subtraction.
        val cumulative = DoubleArray(ordered.size)
        for (i in 1 until ordered.size) {
            cumulative[i] = cumulative[i - 1] + RideStatistics.haversineMetres(
                ordered[i - 1].latitude,
                ordered[i - 1].longitude,
                ordered[i].latitude,
                ordered[i].longitude,
            )
        }
        if (cumulative.last() < targetMetres) {
            return null
        }

        var best: Long? = null
        var tail = 0
        for (head in 1 until ordered.size) {
            // Pull the tail forward as long as the window still covers the distance.
            while (cumulative[head] - cumulative[tail + 1] >= targetMetres && tail + 1 < head) {
                tail++
            }
            if (cumulative[head] - cumulative[tail] >= targetMetres) {
                val elapsed = ordered[head].timestampMs - ordered[tail].timestampMs
                if (elapsed > 0 && (best == null || elapsed < best)) {
                    best = elapsed
                }
            }
        }
        return best
    }
}
