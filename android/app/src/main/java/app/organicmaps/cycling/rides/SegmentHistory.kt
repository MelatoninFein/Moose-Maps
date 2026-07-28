package app.organicmaps.cycling.rides

import android.content.Context
import app.organicmaps.sdk.util.log.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One recorded attempt at a segment, kept so a rider can see their times over months. */
data class SegmentRun(
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
                        )
                    }.getOrNull()
                }
                .sortedByDescending { it.startedAtMs }
        } catch (e: IOException) {
            Logger.w(TAG, "Could not read runs for $segmentId: ${e.message}")
            emptyList()
        }
    }

    fun delete(segmentId: String) {
        File(directory, "$segmentId.jsonl").delete()
    }

    private companion object {
        const val TAG = "SegmentHistoryStore"
    }
}
