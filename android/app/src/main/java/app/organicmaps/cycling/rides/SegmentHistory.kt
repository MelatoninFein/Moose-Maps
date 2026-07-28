package app.organicmaps.cycling.rides

import android.content.Context
import app.organicmaps.sdk.util.log.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One recorded attempt at a segment, kept so a rider can see their times over months. */
data class SegmentRun(
    /** The ride this lap came from, so laps from one outing can be grouped. */
    val rideId: String,
    val startedAtMs: Long,
    val elapsedMillis: Long,
    val averageHeartRateBpm: Int?,
    val averagePowerWatts: Int?,
    /** True when this run beat everything before it. */
    val wasPersonalBest: Boolean,
)

/**
 * Every attempt at every segment, appended one line at a time.
 *
 * Storing only the best time makes racing yourself a single number that either moves or doesn't.
 * The interesting part is the trend - whether you are getting quicker across a season - so every
 * run is kept, newest first when read back.
 */
class SegmentHistoryStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "segment-runs")

    fun record(segmentId: String, run: SegmentRun) {
        directory.mkdirs()
        val json = JSONObject().apply {
            put("startedAtMs", run.startedAtMs)
            put("elapsedMillis", run.elapsedMillis)
            run.averageHeartRateBpm?.let { put("hr", it) }
            run.averagePowerWatts?.let { put("pwr", it) }
            put("pb", run.wasPersonalBest)
            put("ride", run.rideId)
        }
        try {
            File(directory, "$segmentId.jsonl").appendText(json.toString() + "\n")
        } catch (e: IOException) {
            Logger.w(TAG, "Could not record run for $segmentId: ${e.message}")
        }
    }

    /** Attempts newest first. */
    fun runs(segmentId: String): List<SegmentRun> {
        val file = File(directory, "$segmentId.jsonl")
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        SegmentRun(
                            startedAtMs = json.getLong("startedAtMs"),
                            elapsedMillis = json.getLong("elapsedMillis"),
                            averageHeartRateBpm = if (json.has("hr")) json.getInt("hr") else null,
                            averagePowerWatts = if (json.has("pwr")) json.getInt("pwr") else null,
                            wasPersonalBest = json.optBoolean("pb"),
                            rideId = json.optString("ride"),
                        )
                    }.getOrNull()
                }
                .sortedByDescending { it.startedAtMs }
        } catch (e: IOException) {
            Logger.w(TAG, "Could not read runs for $segmentId: ${e.message}")
            emptyList()
        }
    }

    /** The quickest [limit] attempts ever, fastest first. */
    fun topRuns(segmentId: String, limit: Int = TOP_COUNT): List<SegmentRun> =
        runs(segmentId).sortedBy { it.elapsedMillis }.take(limit)

    /**
     * The quickest [limit] laps from a single ride, fastest first.
     *
     * Grouped by ride id rather than by a time window: a window has to guess where one outing ends
     * and the next begins, and guesses wrongly for two rides in one evening.
     */
    fun topRunsInSession(segmentId: String, rideId: String, limit: Int = TOP_COUNT): List<SegmentRun> =
        runs(segmentId).filter { it.rideId == rideId }.sortedBy { it.elapsedMillis }.take(limit)

    /** The ride that most recently produced a lap here, for showing "this session". */
    fun latestRideId(segmentId: String): String? = runs(segmentId).firstOrNull()?.rideId

    fun delete(segmentId: String) {
        File(directory, "$segmentId.jsonl").delete()
    }

    private companion object {
        const val TAG = "SegmentHistoryStore"

        /** Ten is enough to see progress without turning a segment row into a wall of times. */
        const val TOP_COUNT = 10
    }
}
