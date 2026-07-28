package app.organicmaps.cycling.sensors

/**
 * Turns the cumulative revolution counters reported by CSC and power sensors into a rate.
 *
 * Sensors report "revolutions so far" and "when the last revolution happened", both of which wrap:
 * the event time is a 16-bit counter, and the revolution counter is 16- or 32-bit depending on the
 * profile. Differentiating them therefore has to be done modulo the field width, which is why this
 * is a stateful object rather than a function.
 *
 * A stopped wheel keeps re-sending the same counters, so the last known rate is held for
 * [stallTimeoutMs] and then decays to zero - otherwise the UI would freeze at the speed the rider
 * had when they stopped.
 *
 * This class is pure: [nowMs] is supplied by the caller so the stall behaviour can be unit tested.
 */
class RevolutionCounter(
    /** Ticks per second of the event-time field: 1024 for CSC and crank data, 2048 for CP wheel data. */
    private val timeResolutionHz: Int,
    /** Exclusive upper bound of the revolution counter: 2^16 for crank data, 2^32 for wheel data. */
    private val revolutionsModulo: Long,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
) {

    private var lastRevolutions: Long? = null
    private var lastEventTime: Int = 0
    private var lastMovementMs: Long = 0
    private var lastRate: Double = 0.0

    /**
     * Feeds a new sample and returns the current rate in revolutions per second, or null while the
     * first sample is still being used to establish a baseline.
     */
    fun update(revolutions: Long, eventTime: Int, nowMs: Long): Double? {
        val previousRevolutions = lastRevolutions
        val previousEventTime = lastEventTime
        lastRevolutions = revolutions
        lastEventTime = eventTime

        if (previousRevolutions == null) {
            // First sample only establishes the baseline - there is nothing to differentiate against.
            lastMovementMs = nowMs
            return null
        }

        val deltaRevolutions = floorMod(revolutions - previousRevolutions, revolutionsModulo)
        val deltaTicks = floorMod((eventTime - previousEventTime).toLong(), EVENT_TIME_MODULO)

        // No new revolution: hold the last rate briefly, then treat the rider as stopped. A sensor
        // that reports a revolution but no elapsed time is malformed and handled the same way.
        if (deltaRevolutions == 0L || deltaTicks == 0L) {
            if (nowMs - lastMovementMs > stallTimeoutMs) {
                lastRate = 0.0
            }
            return lastRate
        }

        lastMovementMs = nowMs
        lastRate = deltaRevolutions.toDouble() * timeResolutionHz / deltaTicks
        return lastRate
    }

    /** Drops the baseline so a reconnected sensor doesn't differentiate against a stale counter. */
    fun reset() {
        lastRevolutions = null
        lastEventTime = 0
        lastRate = 0.0
        lastMovementMs = 0
    }

    private fun floorMod(value: Long, modulo: Long): Long = ((value % modulo) + modulo) % modulo

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 3_000L

        private const val EVENT_TIME_MODULO = 1L shl 16

        const val CRANK_TIME_RESOLUTION_HZ = 1024
        const val CSC_WHEEL_TIME_RESOLUTION_HZ = 1024
        const val POWER_WHEEL_TIME_RESOLUTION_HZ = 2048

        const val CRANK_REVOLUTIONS_MODULO = 1L shl 16
        const val WHEEL_REVOLUTIONS_MODULO = 1L shl 32

        fun forCrank(
            timeResolutionHz: Int = CRANK_TIME_RESOLUTION_HZ,
            stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
        ) = RevolutionCounter(timeResolutionHz, CRANK_REVOLUTIONS_MODULO, stallTimeoutMs)

        fun forWheel(
            timeResolutionHz: Int = CSC_WHEEL_TIME_RESOLUTION_HZ,
            stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
        ) = RevolutionCounter(timeResolutionHz, WHEEL_REVOLUTIONS_MODULO, stallTimeoutMs)
    }
}
