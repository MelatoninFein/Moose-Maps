package app.organicmaps.cycling.rides

/**
 * A segment split into three timed sectors, as a circuit is.
 *
 * A single number at the finish says whether the lap was good; it never says *where* it was good.
 * Motorsport solved this decades ago by cutting the lap into sectors and colouring each one, and
 * the same information is what a rider actually wants: the climb was quick, the descent was not.
 *
 * Three because that is the convention and because a segment short enough to have fewer meaningful
 * divisions is short enough to read as one number anyway.
 */
object Sectors {

    const val COUNT = 3

    /**
     * The point index each sector ends at, for a segment of [pointCount] points.
     *
     * Divided by points rather than distance: the tracker counts points, and a recording sampled
     * at a steady rate spaces them evenly enough that the difference is not visible.
     */
    fun boundaries(pointCount: Int): List<Int> {
        if (pointCount < COUNT + 1) {
            // Too short to divide meaningfully - one sector covering the whole thing.
            return listOf(pointCount - 1)
        }
        val last = pointCount - 1
        return (1..COUNT).map { sector -> last * sector / COUNT }
    }

    /** Which sector index [pointIndex] falls in, 0-based. */
    fun sectorOf(pointIndex: Int, pointCount: Int): Int {
        val bounds = boundaries(pointCount)
        val index = bounds.indexOfFirst { pointIndex <= it }
        return if (index < 0) bounds.lastIndex else index
    }

    /**
     * Sector times from a run's per-point splits.
     *
     * Returns the elapsed time spent inside each sector, not the cumulative time at its end, so a
     * slow sector reads as a large number rather than as a large number that every later sector
     * inherits.
     */
    fun sectorTimes(splitsMillis: List<Long>): List<Long> {
        if (splitsMillis.size < 2) {
            return emptyList()
        }
        var previous = 0L
        return boundaries(splitsMillis.size).mapNotNull { boundary ->
            val at = splitsMillis.getOrNull(boundary) ?: return@mapNotNull null
            val duration = at - previous
            previous = at
            duration
        }
    }
}

/**
 * How a sector time compares, using the colours every timing screen in motorsport uses.
 *
 * Purple for a sector nobody - meaning no previous ride of yours - has beaten, green for one that
 * beat the best lap's sector without being an outright record, yellow for slower. A rider who has
 * ever watched a race reads these without being told, which is the entire reason to borrow them.
 */
enum class SectorGrade {
    /** Fastest that sector has ever been ridden. */
    RECORD,

    /** Faster than the same sector in your best lap. */
    IMPROVED,

    /** Slower. */
    SLOWER,
    ;

    companion object {

        /**
         * Grades a completed sector.
         *
         * [recordMillis] is the fastest that sector has ever been ridden, [bestLapMillis] the same
         * sector within your best complete lap. They differ: a lap can contain a slow sector and
         * still be the fastest overall, which is exactly what purple is for.
         */
        fun of(actualMillis: Long, recordMillis: Long?, bestLapMillis: Long?): SectorGrade = when {
            recordMillis != null && actualMillis < recordMillis -> RECORD
            bestLapMillis != null && actualMillis < bestLapMillis -> IMPROVED
            // With nothing to compare against, the first ride of a sector sets the record.
            recordMillis == null && bestLapMillis == null -> RECORD
            else -> SLOWER
        }
    }
}
