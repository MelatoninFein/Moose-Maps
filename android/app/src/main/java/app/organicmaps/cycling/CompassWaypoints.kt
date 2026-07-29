package app.organicmaps.cycling

import app.organicmaps.cycling.ui.CompassView
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.util.log.Logger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns saved bookmarks into rim dots for the compass.
 *
 * Only the nearest few are shown: a rim crowded with dots tells a rider nothing, and the ones that
 * matter while riding are the ones close enough to reach. Bookmarks in the first category are
 * treated as favourites and drawn in a different colour.
 */
object CompassWaypoints {

    /** How many dots the rim can carry before it stops being readable. */
    private const val MAX_WAYPOINTS = 8

    /** Beyond this there is no point showing a direction; the rider is nowhere near it. */
    private const val MAX_DISTANCE_METRES = 50_000.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    private const val TAG = "CompassWaypoints"

    /** Long enough to cost nothing at 1 Hz, short enough that a place saved mid-ride shows up. */
    private const val CACHE_TTL_MS = 60_000L

    /**
     * Reads bookmarks and returns the nearest ones as compass waypoints.
     *
     * Runs on whatever thread calls it and touches JNI, so callers should keep the cadence low -
     * once per location update is plenty, since bookmarks don't move.
     */
    fun nearest(latitude: Double, longitude: Double): List<CompassView.Waypoint> = positions()
        .map { position ->
            Triple(
                distanceMetres(latitude, longitude, position.latitude, position.longitude),
                bearingDegrees(latitude, longitude, position.latitude, position.longitude),
                position.isFavourite,
            )
        }
        .filter { it.first <= MAX_DISTANCE_METRES }
        .sortedBy { it.first }
        .take(MAX_WAYPOINTS)
        .map { CompassView.Waypoint(it.second, it.third) }

    private class Position(val latitude: Double, val longitude: Double, val isFavourite: Boolean)

    private var cached: List<Position> = emptyList()
    private var cachedAtMs = 0L

    /**
     * Bookmark positions, re-read from JNI at most once a minute.
     *
     * This is called on every location fix, and the read costs one JNI round trip *per bookmark* -
     * so a rider with a few hundred saved places was crossing into native code hundreds of times a
     * second, on the main thread, for an answer that only changes when they save a new place. The
     * maths that follows is cheap; the crossing was not.
     */
    private fun positions(): List<Position> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cachedAtMs != 0L && now - cachedAtMs < CACHE_TTL_MS) {
            return cached
        }
        cachedAtMs = now
        cached = try {
            val categories = BookmarkManager.INSTANCE.categories
            val favouriteCategoryId = categories.firstOrNull()?.id
            categories.flatMap { category ->
                (0 until category.bookmarksCount).mapNotNull { index ->
                    val id = category.getBookmarkIdByPosition(index)
                    val info = BookmarkManager.INSTANCE.getBookmarkInfo(id) ?: return@mapNotNull null
                    Position(info.lat, info.lon, category.id == favouriteCategoryId)
                }
            }
        } catch (e: RuntimeException) {
            // Bookmarks live behind JNI; a failure here must not take the map down.
            Logger.w(TAG, "Could not read bookmarks for the compass: ${e.message}")
            emptyList()
        }
        return cached
    }

    /** Forces the next read to go to JNI, for when the map knows bookmarks have changed. */
    fun invalidate() {
        cachedAtMs = 0L
    }

    /** Great-circle distance, good enough at the scale a compass dot is meaningful. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Initial true bearing from one point to another, normalised to 0..360. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
