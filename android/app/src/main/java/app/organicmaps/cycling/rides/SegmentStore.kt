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

    /**
     * The stored representation, also used when sharing a segment with another rider.
     *
     * Two people comparing times needs no server if they can exchange the course itself: the
     * receiver rides the same start and end, so the times mean the same thing.
     */
    fun toJson(segment: Segment): String = buildJson(segment).toString()

    private fun buildJson(segment: Segment) = JSONObject().apply {
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

    /**
     * Reads a segment someone sent you. Returns null if the file is not one of ours.
     *
     * Sharing was one-way until now: a segment could be sent but never received, so two riders
     * could not actually end up racing the same stretch. The parse is deliberately forgiving about
     * everything except the points - a course with fewer than two of them describes nothing.
     */
    fun fromJson(text: String, fallbackName: String): Segment? = try {
        val json = JSONObject(text)
        val points = json.optJSONArray("points")
        val parsed = (0 until (points?.length() ?: 0)).mapNotNull { index ->
            points?.optJSONObject(index)?.let { SegmentPoint(it.getDouble("lat"), it.getDouble("lon")) }
        }
        if (parsed.size < 2) {
            null
        } else {
            Segment(
                id = json.optString("id").ifBlank { freshId() },
                name = json.optString("name").ifBlank { fallbackName },
                points = parsed,
            )
        }
    } catch (e: Exception) {
        // Anything unparseable is simply "not a segment". The caller tells the rider, which is a
        // better signal than a log line, and keeping this free of Android types makes it testable.
        null
    }

    /**
     * Saves an imported segment without ever overwriting one you already have.
     *
     * A shared file carries the sender's id, which can collide with a segment of your own. Silently
     * overwriting would take a season of recorded attempts with it, so a colliding import is stored
     * under a fresh id: a duplicate row is visible and deletable, lost history is not.
     */
    fun importSegment(segment: Segment): Segment {
        val taken = list().map { it.id }.toSet()
        val stored = if (segment.id in taken) segment.copy(id = freshId()) else segment
        save(stored)
        return stored
    }

    private fun freshId() = "imported-${System.currentTimeMillis()}"

    fun save(segment: Segment) {
        directory.mkdirs()
        val json = buildJson(segment)
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
