package app.organicmaps.cycling.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import app.organicmaps.MwmApplication
import app.organicmaps.R
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.cycling.QuickSpot
import app.organicmaps.cycling.QuickSpots
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.routing.RoutingController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * "Navigate to" sheet: saved places ordered by distance, favourites pinned on top.
 *
 * Deliberately a flat list of plain rows rather than a searchable browser - the point is to leave
 * somewhere quickly, so the nearest place should be reachable in two taps without reading anything.
 * Tapping a row hands straight off to routing.
 */
class QuickSpotsFragment : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_quick_spots, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val list: LinearLayout = view.findViewById(R.id.quick_spots_list)
        val empty: TextView = view.findViewById(R.id.quick_spots_empty)

        val here = MwmApplication.from(requireContext()).getLocationHelper().savedLocation
        if (here == null) {
            // Without a fix there is no "near you" to sort by; saying so beats an arbitrary order.
            empty.setText(R.string.cycling_spots_no_location)
            empty.visibility = View.VISIBLE
            return
        }

        val spots = QuickSpots.nearest(here.latitude, here.longitude)
        if (spots.isEmpty()) {
            empty.setText(R.string.cycling_spots_empty)
            empty.visibility = View.VISIBLE
            return
        }

        empty.visibility = View.GONE
        fill(list, spots)

        // Only offered once the list is longer than can be taken in at a glance, so the common case
        // of "the cafe two streets away" stays a two-tap operation with nothing to read.
        val filter: EditText = view.findViewById(R.id.quick_spots_filter)
        if (spots.size > FILTER_THRESHOLD) {
            filter.visibility = View.VISIBLE
            filter.doAfterTextChanged { text ->
                val query = text?.toString()?.trim().orEmpty()
                val matching = if (query.isEmpty()) {
                    spots
                } else {
                    spots.filter { it.name.contains(query, ignoreCase = true) }
                }
                fill(list, matching)
                empty.visibility = if (matching.isEmpty()) View.VISIBLE else View.GONE
                empty.setText(R.string.cycling_spots_no_match)
            }
        }
    }

    private fun fill(list: LinearLayout, spots: List<QuickSpot>) {
        list.removeAllViews()
        spots.forEach { spot -> list.addView(createRow(inflater = layoutInflater, parent = list, spot = spot)) }
    }

    private fun createRow(inflater: LayoutInflater, parent: ViewGroup, spot: QuickSpot): View {
        val row = inflater.inflate(R.layout.item_quick_spot, parent, false)

        row.findViewById<TextView>(R.id.spot_name).text =
            spot.name.ifBlank { getString(R.string.cycling_spots_unnamed) }
        // Direction as well as distance: one says how far, the other says whether it is your way.
        row.findViewById<TextView>(R.id.spot_distance).text =
            "${CyclingFormatter.distanceText(spot.distanceMetres)} ${spot.cardinal}"
        row.findViewById<TextView>(R.id.spot_favourite).visibility =
            if (spot.isFavourite) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.spot_category).apply {
            text = spot.categoryName
            visibility = if (spot.categoryName.isBlank()) View.GONE else View.VISIBLE
        }

        row.setOnClickListener { navigateTo(spot) }
        return row
    }

    private fun navigateTo(spot: QuickSpot) {
        val destination = MapObject.createMapObject(
            MapObject.POI,
            spot.name.ifBlank { getString(R.string.cycling_spots_unnamed) },
            "",
            spot.latitude,
            spot.longitude,
        )
        // Null start means "from my position", which is what a rider always wants here.
        RoutingController.get().prepare(null, destination)
        dismiss()
    }

    companion object {
        const val TAG = "QuickSpotsFragment"

        /** Above this many rows, scrolling costs more than typing three letters. */
        private const val FILTER_THRESHOLD = 8
    }
}
