package app.organicmaps.cycling.rides

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import java.util.concurrent.TimeUnit

/**
 * Your saved segments and the best time on each.
 *
 * Until now segments could be created and raced but never seen, which made the whole feature
 * invisible - there was no way to tell what existed, let alone remove one created by mistake.
 */
class SegmentsFragment : BaseMwmFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_segments, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))
        render(view)
    }

    private fun render(root: View) {
        val list: LinearLayout = root.findViewById(R.id.segments_list)
        val empty: TextView = root.findViewById(R.id.segments_empty)
        list.removeAllViews()

        val segments = SegmentStore(requireContext()).list()
        if (segments.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE

        val bestStore = SegmentBestStore(requireContext())
        segments.forEach { segment ->
            val row = layoutInflater.inflate(R.layout.item_segment, list, false)
            row.findViewById<TextView>(R.id.segment_row_name).text = segment.name

            val best = bestStore.load(segment.id)
            row.findViewById<TextView>(R.id.segment_row_detail).text = listOfNotNull(
                CyclingFormatter.distanceText(segment.lengthMetres),
                best?.let { getString(R.string.cycling_segment_best, formatDuration(it.totalMillis)) }
                    ?: getString(R.string.cycling_segment_no_time),
            ).joinToString(" · ")

            row.findViewById<ImageView>(R.id.segment_row_delete).setOnClickListener {
                SegmentStore(requireContext()).delete(segment.id)
                // Re-render rather than removing the row, so the empty state appears when the last
                // segment goes.
                render(root)
            }
            list.addView(row)
        }
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return "${minutes}m ${seconds}s"
    }
}
