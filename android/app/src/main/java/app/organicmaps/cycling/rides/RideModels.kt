package app.organicmaps.cycling.rides

/**
 * One moment of a ride: where you were and what your body and bike were doing.
 *
 * Sensor fields are null when no sensor was reporting, which is deliberately different from zero -
 * a ride with no heart-rate strap must not average out to 0 bpm.
 */
data class RideSample(
    /** Wall-clock time, milliseconds since the epoch. */
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double?,
    /** Speed from GPS, metres per second. */
    val gpsSpeedMps: Double?,
    /** Speed from a wheel sensor, metres per second. */
    val sensorSpeedMps: Double?,
    val heartRateBpm: Int?,
    val cadenceRpm: Int?,
    val powerWatts: Int?,
)

/** Everything worth showing about a finished ride, derived from its samples. */
data class RideSummary(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceMetres: Double,
    /** Time actually moving, excluding stops. */
    val movingMillis: Long,
    val averageSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val averageCadenceRpm: Int?,
    val averagePowerWatts: Int?,
    val ascentMetres: Double,
    val sampleCount: Int,
) {
    val elapsedMillis: Long
        get() = endedAtMs - startedAtMs
}

/**
 * Derives a [RideSummary] from raw samples.
 *
 * Kept free of Android types so the arithmetic - which is where the mistakes hide - can be unit
 * tested on the JVM.
 */
object RideStatistics {

    /** Below this a rider is stopped, not crawling; GPS noise alone produces small non-zero speeds. */
    private const val MOVING_THRESHOLD_MPS = 0.5

    /**
     * Ignore altitude wobble smaller than this when totalling ascent. Consumer GPS altitude is
     * noisy by several metres, and summing every jitter turns a flat ride into a mountain stage.
     */
    private const val ASCENT_NOISE_FLOOR_M = 3.0

    fun summarise(samples: List<RideSample>): RideSummary? {
        if (samples.size < 2) {
            return null
        }

        val ordered = samples.sortedBy { it.timestampMs }

        var distance = 0.0
        var moving = 0L
        var ascent = 0.0
        var lastAscentReference: Double? = ordered.first().altitudeMetres

        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val current = ordered[i]

            val step = haversineMetres(previous.latitude, previous.longitude, current.latitude, current.longitude)
            distance += step

            val deltaMs = current.timestampMs - previous.timestampMs
            // A speed of null means no reading, so fall back to the distance actually covered.
            val speed = current.gpsSpeedMps ?: current.sensorSpeedMps
                ?: if (deltaMs > 0) step / (deltaMs / 1000.0) else 0.0
            if (speed >= MOVING_THRESHOLD_MPS) {
                moving += deltaMs
            }

            val altitude = current.altitudeMetres
            val reference = lastAscentReference
            if (altitude != null) {
                if (reference == null) {
                    lastAscentReference = altitude
                } else if (altitude - reference >= ASCENT_NOISE_FLOOR_M) {
                    ascent += altitude - reference
                    lastAscentReference = altitude
                } else if (reference - altitude >= ASCENT_NOISE_FLOOR_M) {
                    lastAscentReference = altitude
                }
            }
        }

        val speeds = ordered.mapNotNull { it.gpsSpeedMps ?: it.sensorSpeedMps }
        val heartRates = ordered.mapNotNull { it.heartRateBpm }
        val cadences = ordered.mapNotNull { it.cadenceRpm }
        val powers = ordered.mapNotNull { it.powerWatts }

        return RideSummary(
            startedAtMs = ordered.first().timestampMs,
            endedAtMs = ordered.last().timestampMs,
            distanceMetres = distance,
            movingMillis = moving,
            // Average over moving time, not elapsed - a coffee stop shouldn't halve your average.
            averageSpeedMps = if (moving > 0) distance / (moving / 1000.0) else null,
            maxSpeedMps = speeds.maxOrNull(),
            averageHeartRateBpm = heartRates.averageOrNull()?.toInt(),
            maxHeartRateBpm = heartRates.maxOrNull(),
            averageCadenceRpm = cadences.averageOrNull()?.toInt(),
            averagePowerWatts = powers.averageOrNull()?.toInt(),
            ascentMetres = ascent,
            sampleCount = ordered.size,
        )
    }

    private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()

    /** Great-circle distance in metres. */
    fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * earthRadius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
