package app.organicmaps.cycling

import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.util.log.Logger

/** A saved place offered in the quick-navigate list. */
data class QuickSpot(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMetres: Double,
    val bearingDegrees: Double,
    val isFavourite: Boolean,
    /** The bookmark list it came from, so the ordering can be explained rather than assumed. */
    val categoryName: String,
) {

    /**
     * The bearing as one of eight points of the compass.
     *
     * "2.4 km" does not say whether somewhere is on your way or back the way you came, and the
     * bearing was already being computed for the compass dial and simply never shown.
     */
    val cardinal: String
        get() {
            val index = (((bearingDegrees % 360 + 360) % 360) / 45.0).toInt() % COMPASS_POINTS.size
            return COMPASS_POINTS[index]
        }

    private companion object {
        val COMPASS_POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    }
}

/**
 * Builds the quick-navigate list: every saved place, ordered by how far away it is.
 *
 * Favourites - anything in the first bookmark category - are kept at the top regardless of
 * distance, because they are the places a rider heads for deliberately. Everything else falls into
 * simple distance order, so the nearest thing is always one tap away.
 */
object QuickSpots {

    private const val TAG = "QuickSpots"

    /** Enough to be useful, few enough to scan while stopped at a junction. */
    private const val MAX_SPOTS = 60

    fun nearest(latitude: Double, longitude: Double): List<QuickSpot> = try {
        val categories = BookmarkManager.INSTANCE.categories
        val favouriteCategoryId = categories.firstOrNull()?.id

        categories
            .flatMap { category ->
                (0 until category.bookmarksCount).mapNotNull { index ->
                    val id = category.getBookmarkIdByPosition(index)
                    val info = BookmarkManager.INSTANCE.getBookmarkInfo(id) ?: return@mapNotNull null
                    QuickSpot(
                        name = info.name.orEmpty().ifBlank { info.featureType.orEmpty() },
                        latitude = info.lat,
                        longitude = info.lon,
                        distanceMetres = CompassWaypoints.distanceMetres(latitude, longitude, info.lat, info.lon),
                        bearingDegrees = CompassWaypoints.bearingDegrees(latitude, longitude, info.lat, info.lon),
                        isFavourite = category.id == favouriteCategoryId,
                        categoryName = category.name.orEmpty(),
                    )
                }
            }
            // Favourites first, then by distance within each group.
            .sortedWith(compareByDescending<QuickSpot> { it.isFavourite }.thenBy { it.distanceMetres })
            .take(MAX_SPOTS)
    } catch (e: RuntimeException) {
        // Bookmarks live behind JNI; a failure must not take the map down.
        Logger.w(TAG, "Could not read bookmarks: ${e.message}")
        emptyList()
    }
}
