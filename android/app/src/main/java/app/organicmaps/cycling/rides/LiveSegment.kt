package app.organicmaps.cycling.rides

import android.content.Context
import app.organicmaps.sdk.util.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Your best run at a segment, including how long it took to reach each point along the way.
 *
 * The splits are the whole point: a live "+4s" is only meaningful against where you were at the
 * *same place* last time. Comparing against the total alone would tell you nothing until the end,
 * and scaling the total by distance covered assumes an even pace nobody rides.
 */
data class SegmentBest(
    val segmentId: String,
    val totalMillis: Long,
    /** Elapsed milliseconds at each segment point, same order and length as the segment. */
    val splitsMillis: List<Long>,
    val achievedAtMs: Long,
    /**
     * Fastest each sector has ever been ridden, which is not the same as the sectors of the best
     * lap - a lap can contain one slow sector and still be the quickest overall. Kept separately so
     * a sector record survives being set during an otherwise mediocre ride.
     */
    val sectorRecordsMillis: List<Long> = emptyList(),
)

/** How far into a segment you currently are, and how that compares with your best. */
data class LiveSegmentProgress(
    val segmentName: String,
    val elapsedMillis: Long,
    /** Negative is ahead of your best, positive behind. Null when there is no best yet. */
    val deltaMillis: Long?,
    val finished: Boolean,
    /**
     * How far round the segment you are, 0 to 1.
     *
     * A delta alone does not say whether four seconds is worth chasing: with a kilometre left it is,
     * on the run-in it is not. Measured in points passed rather than metres, which is what the
     * tracker already knows and is close enough on evenly sampled recordings.
     */
    val fractionComplete: Float = 0f,
    /** Which of the three sectors you are in, 0-based. */
    val currentSector: Int = 0,
    /**
     * Grades for the sectors finished so far, in order.
     *
     * Empty until the first sector is crossed. A rider glances down after the climb and sees purple
     * or yellow without reading a number, which is the whole point of borrowing the convention.
     */
    val sectorGrades: List<SectorGrade> = emptyList(),
    /** Time for the sector just completed, set only on the fix that crosses its line. */
    val completedSectorMillis: Long? = null,
    /** Every sector time of this lap so far, for the classification shown at the finish. */
    val sectorMillis: List<Long> = emptyList(),
)

/**
 * Watches your position and reports progress round a segment as you ride it.
 *
 * Stateful and fed one fix at a time, because that is how location arrives. Kept free of Android
 * types so the state machine - which is the part that goes wrong - can be tested directly.
 */
class LiveSegmentTracker(
    private val segments: List<Segment>,
    private val bests: Map<String, SegmentBest>,
) {

    private var active: Segment? = null
    private var startedAtMs = 0L
    private var reachedIndex = -1

    private var sectorGrades: List<SectorGrade> = emptyList()
    private var sectorTimes: List<Long> = emptyList()
    private var sectorStartedAtMs = 0L

    /**
     * Times and grades a sector if this fix crossed its line, otherwise null.
     *
     * Compared against two different things on purpose: the same sector inside your best lap, and
     * the fastest that sector has ever been ridden. A lap can contain one slow sector and still be
     * the quickest overall, so beating the record is a stronger result than beating the best lap -
     * which is precisely the distinction purple and green carry on a timing screen.
     */
    private fun gradeCrossedSector(
        segment: Segment,
        index: Int,
        timestampMs: Long,
        atEnd: Boolean,
    ): Long? {
        val boundaries = Sectors.boundaries(segment.points.size)
        val crossed = sectorGrades.size
        if (crossed >= boundaries.size) {
            return null
        }
        val boundary = boundaries[crossed]
        if (index < boundary && !atEnd) {
            return null
        }

        val sectorMillis = timestampMs - sectorStartedAtMs
        sectorStartedAtMs = timestampMs

        val best = bests[segment.id]
        val bestLapSectors = best?.splitsMillis?.let { Sectors.sectorTimes(it) }.orEmpty()
        sectorGrades = sectorGrades + SectorGrade.of(
            actualMillis = sectorMillis,
            recordMillis = best?.sectorRecordsMillis?.getOrNull(crossed),
            bestLapMillis = bestLapSectors.getOrNull(crossed),
        )
        sectorTimes = sectorTimes + sectorMillis
        return sectorMillis
    }

    val activeSegmentName: String?
        get() = active?.name

    /**
     * Feeds a position and returns current progress, or null when you are not on a segment.
     *
     * Once finished, the progress is returned one final time with [LiveSegmentProgress.finished]
     * set, and the tracker resets ready for another lap.
     */
    fun onPosition(latitude: Double, longitude: Double, timestampMs: Long): LiveSegmentProgress? {
        val current = active
        if (current == null) {
            val entered = segments.firstOrNull { segment ->
                segment.points.isNotEmpty() &&
                    distanceTo(latitude, longitude, segment.points.first()) <= SegmentMatcher.GATE_RADIUS_M
            } ?: return null

            active = entered
            startedAtMs = timestampMs
            reachedIndex = 0
            sectorGrades = emptyList()
            sectorTimes = emptyList()
            sectorStartedAtMs = timestampMs
            return LiveSegmentProgress(entered.name, 0, deltaAt(entered, 0, 0), false, 0f)
        }

        // Advance through the segment's points as they are passed. Scanning forward rather than
        // finding the globally nearest point stops a loop that crosses itself from jumping ahead.
        var index = reachedIndex
        while (index + 1 < current.points.size &&
            distanceTo(latitude, longitude, current.points[index + 1]) <= SegmentMatcher.CORRIDOR_RADIUS_M
        ) {
            index++
        }
        reachedIndex = index

        val elapsed = timestampMs - startedAtMs
        val atEnd = index >= current.points.size - 1 &&
            distanceTo(latitude, longitude, current.points.last()) <= SegmentMatcher.GATE_RADIUS_M

        val lastIndex = (current.points.size - 1).coerceAtLeast(1)
        val fraction = if (atEnd) 1f else (index.toFloat() / lastIndex).coerceIn(0f, 1f)

        // A sector line is crossed when the point reached passes its boundary. Timing them here,
        // as they happen, is what lets the colour appear at the top of the climb rather than at the
        // finish - by which point knowing the climb was slow is no use to anyone.
        val completedSector = gradeCrossedSector(current, index, timestampMs, atEnd)
        val sector = Sectors.sectorOf(index, current.points.size)

        val progress = LiveSegmentProgress(
            segmentName = current.name,
            elapsedMillis = elapsed,
            deltaMillis = deltaAt(current, index, elapsed),
            finished = atEnd,
            fractionComplete = fraction,
            currentSector = sector,
            sectorGrades = sectorGrades,
            completedSectorMillis = completedSector,
            sectorMillis = sectorTimes,
        )

        if (atEnd) {
            sectorGrades = emptyList()
            sectorTimes = emptyList()
            sectorStartedAtMs = timestampMs
            if (current.isLoop) {
                // On a loop the finish line is the start line, so the next lap begins immediately
                // rather than waiting for the rider to leave and re-enter the gate. Riding four
                // laps should time four laps.
                startedAtMs = timestampMs
                reachedIndex = 0
            } else {
                active = null
                reachedIndex = -1
            }
        }
        return progress
    }

    /**
     * The nearest segment start within [radiusMetres], and how far away it is, when not already on
     * a segment. Lets a rider wind up rather than meeting the line by surprise.
     */
    fun approachingSegment(latitude: Double, longitude: Double, radiusMetres: Double): Pair<Segment, Int>? {
        if (active != null) {
            return null
        }
        return segments
            .filter { it.points.isNotEmpty() }
            .map { it to distanceTo(latitude, longitude, it.points.first()) }
            .filter { it.second in SegmentMatcher.GATE_RADIUS_M..radiusMetres }
            .minByOrNull { it.second }
            ?.let { (segment, distance) -> segment to distance.toInt() }
    }

    /** Abandons the current attempt, for when a ride ends part-way round. */
    fun reset() {
        active = null
        reachedIndex = -1
    }

    private fun deltaAt(segment: Segment, index: Int, elapsedMillis: Long): Long? {
        val best = bests[segment.id] ?: return null
        val split = best.splitsMillis.getOrNull(index) ?: return null
        return elapsedMillis - split
    }

    private fun distanceTo(latitude: Double, longitude: Double, point: SegmentPoint): Double =
        RideStatistics.haversineMetres(latitude, longitude, point.latitude, point.longitude)
}

/**
 * Best times on disk, one JSON file per segment.
 *
 * A new time only replaces the stored one when it is actually faster, so a slow lap never
 * overwrites a personal best.
 */
class SegmentBestStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "segment-bests")

    fun load(segmentId: String): SegmentBest? {
        val file = File(directory, "$segmentId.json")
        if (!file.exists()) {
            return null
        }
        return try {
            val json = JSONObject(file.readText())
            val splits = json.optJSONArray("splits") ?: JSONArray()
            SegmentBest(
                segmentId = json.optString("segmentId", segmentId),
                totalMillis = json.getLong("totalMillis"),
                splitsMillis = (0 until splits.length()).map { splits.getLong(it) },
                achievedAtMs = json.optLong("achievedAtMs"),
                sectorRecordsMillis = json.optJSONArray("sectorRecords")
                    ?.let { records -> (0 until records.length()).map { records.getLong(it) } }
                    .orEmpty(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Unreadable best for $segmentId: ${e.message}")
            null
        }
    }

    fun loadAll(segmentIds: Collection<String>): Map<String, SegmentBest> =
        segmentIds.mapNotNull { id -> load(id)?.let { id to it } }.toMap()

    /**
     * Records an attempt. Returns true when its total beat the stored best.
     *
     * The splits are replaced only by a faster lap, but sector records are merged from every
     * attempt: a rider can set the quickest climb of their life during a lap they ruined on the
     * descent, and a purple sector that vanished because the lap as a whole was slow would be a
     * timing screen that lies.
     */
    fun saveIfFaster(best: SegmentBest): Boolean {
        val existing = load(best.segmentId)
        val isPersonalBest = existing == null || best.totalMillis < existing.totalMillis

        val attemptSectors = Sectors.sectorTimes(best.splitsMillis)
        val mergedRecords = (0 until maxOf(attemptSectors.size, existing?.sectorRecordsMillis?.size ?: 0)).map { i ->
            listOfNotNull(attemptSectors.getOrNull(i), existing?.sectorRecordsMillis?.getOrNull(i)).min()
        }

        val kept = if (isPersonalBest) best else existing ?: best
        directory.mkdirs()
        val json = JSONObject().apply {
            put("segmentId", kept.segmentId)
            put("totalMillis", kept.totalMillis)
            put("achievedAtMs", kept.achievedAtMs)
            put("splits", JSONArray().apply { kept.splitsMillis.forEach { put(it) } })
            put("sectorRecords", JSONArray().apply { mergedRecords.forEach { put(it) } })
        }
        return try {
            File(directory, "${best.segmentId}.json").writeText(json.toString())
            isPersonalBest
        } catch (e: IOException) {
            Logger.w(TAG, "Could not save best for ${best.segmentId}: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "SegmentBestStore"
    }
}
