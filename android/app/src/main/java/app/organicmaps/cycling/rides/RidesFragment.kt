package app.organicmaps.cycling.rides

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.settings.SettingsActivity
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import java.util.Date
import java.util.concurrent.TimeUnit

/** Your recorded rides, newest first. Tapping one opens its trace and statistics. */
class RidesFragment : BaseMwmFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_rides, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))

        val list: LinearLayout = view.findViewById(R.id.rides_list)
        val empty: TextView = view.findViewById(R.id.rides_empty)

        val recorder = RideRecorder.from(requireContext())
        val rides = recorder.listRides()

        if (rides.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE

        rides.forEach { file ->
            // Summaries are cheap to recompute and always correct, even if a ride was cut short by
            // the process dying mid-recording and never got its summary file written.
            val summary = recorder.summaryOf(file) ?: return@forEach
            list.addView(createRow(list, file.name, summary))
        }
    }

    private fun createRow(parent: ViewGroup, fileName: String, summary: RideSummary): View {
        val row = layoutInflater.inflate(R.layout.item_ride, parent, false)

        val date = Date(summary.startedAtMs)
        row.findViewById<TextView>(R.id.ride_date).text =
            "${DateFormat.getMediumDateFormat(requireContext()).format(date)} " +
                DateFormat.getTimeFormat(requireContext()).format(date)

        row.findViewById<TextView>(R.id.ride_stats).text = listOfNotNull(
            CyclingFormatter.distanceText(summary.distanceMetres),
            formatDuration(summary.movingMillis),
            summary.averageSpeedMps?.let { "${CyclingFormatter.speedValue(it)} ${CyclingFormatter.speedUnit(requireContext())}" },
            summary.averageHeartRateBpm?.let { "$it ${getString(R.string.cycling_unit_bpm)}" },
        ).joinToString(" · ")

        row.setOnClickListener {
            val args = Bundle().apply { putString(RideDetailFragment.EXTRA_FILE_NAME, fileName) }
            (requireActivity() as SettingsActivity)
                .stackFragment(RideDetailFragment::class.java, getString(R.string.cycling_rides_detail_title), args)
        }
        return row
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
