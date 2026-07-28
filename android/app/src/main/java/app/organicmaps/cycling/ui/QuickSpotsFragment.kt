package app.organicmaps.cycling.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
        spots.forEach { spot -> list.addView(createRow(inflater = layoutInflater, parent = list, spot = spot)) }
    }

    private fun createRow(inflater: LayoutInflater, parent: ViewGroup, spot: QuickSpot): View {
        val row = inflater.inflate(R.layout.item_quick_spot, parent, false)

        row.findViewById<TextView>(R.id.spot_name).text =
            spot.name.ifBlank { getString(R.string.cycling_spots_unnamed) }
        row.findViewById<TextView>(R.id.spot_distance).text =
            CyclingFormatter.distanceText(spot.distanceMetres)
        row.findViewById<TextView>(R.id.spot_favourite).visibility =
            if (spot.isFavourite) View.VISIBLE else View.GONE

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
    }
}
