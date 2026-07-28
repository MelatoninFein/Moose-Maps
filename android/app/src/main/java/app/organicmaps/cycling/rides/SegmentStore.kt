package app.organicmaps.cycling.rides

import android.content.Context
import app.organicmaps.sdk.util.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Saved segments, one JSON file each.
 *
 * Files rather than a database: a segment is a name and a list of points, there are a handful of
 * them, and a plain file is something a rider can copy off the device and hand to a friend - which
 * is the only way two people can compare times without a server standing between them.
 */
class SegmentStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "segments")

    fun save(segment: Segment) {
        directory.mkdirs()
        val json = JSONObject().apply {
            put("id", segment.id)
            put("name", segment.name)
            put(
                "points",
                JSONArray().apply {
                    segment.points.forEach { point ->
                        put(JSONObject().apply { put("lat", point.latitude); put("lon", point.longitude) })
                    }
                },
            )
        }
        try {
            File(directory, "${segment.id}.json").writeText(json.toString())
        } catch (e: IOException) {
            Logger.w(TAG, "Could not save segment ${segment.id}: ${e.message}")
        }
    }

    /** Removes a segment and its recorded best, so a mistake can be undone. */
    fun delete(segmentId: String) {
        File(directory, "$segmentId.json").delete()
        File(File(directory.parentFile, "segment-bests"), "$segmentId.json").delete()
    }

    fun list(): List<Segment> = (directory.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val points = json.optJSONArray("points") ?: return@mapNotNull null
                Segment(
                    id = json.optString("id", file.nameWithoutExtension),
                    name = json.optString("name", file.nameWithoutExtension),
                    points = (0 until points.length()).mapNotNull { index ->
                        points.optJSONObject(index)?.let { SegmentPoint(it.getDouble("lat"), it.getDouble("lon")) }
                    },
                )
            } catch (e: Exception) {
                // A corrupt segment file must not hide the rest.
                Logger.w(TAG, "Skipping unreadable segment ${file.name}: ${e.message}")
                null
            }
        }
        .sortedBy { it.name }

    private companion object {
        const val TAG = "SegmentStore"
    }
}
