package app.organicmaps.cycling.rides

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        render(view)
    }

    /** Re-read on every change, so a deleted ride takes its contribution to the records with it. */
    private fun render(view: View) {
        val list: LinearLayout = view.findViewById(R.id.rides_list)
        val empty: TextView = view.findViewById(R.id.rides_empty)
        val recordsTitle: TextView = view.findViewById(R.id.records_title)
        val recordsBody: TextView = view.findViewById(R.id.records_body)
        list.removeAllViews()

        val recorder = RideRecorder.from(requireContext())
        val rides = recorder.listRides()

        if (rides.isEmpty()) {
            empty.visibility = View.VISIBLE
            recordsTitle.visibility = View.GONE
            recordsBody.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE

        recordsTitle.visibility = View.VISIBLE
        recordsBody.visibility = View.VISIBLE
        recordsBody.text = buildRecords(recorder, rides)

        rides.forEach { file ->
            // Samples are read once and used for both the summary and the thumbnail: parsing the
            // file twice per row would double the cost of opening a long list.
            val samples = recorder.samplesOf(file)
            val summary = RideStatistics.summarise(samples) ?: return@forEach
            list.addView(createRow(list, file, summary, samples))
        }
    }

    private fun refresh() = view?.let { render(it) }

    /**
     * Fastest ever over each tracked distance, across every ride.
     *
     * Computed on open rather than stored: it has to be re-derived whenever a ride is added or
     * removed anyway, and a rider has tens of rides, not thousands.
     */
    private fun buildRecords(recorder: RideRecorder, rides: List<java.io.File>): String {
        val bests = PersonalRecords.TRACKED_DISTANCES.associateWith { distance ->
            rides.mapNotNull { PersonalRecords.fastestOverDistance(recorder.samplesOf(it), distance) }.minOrNull()
        }.filterValues { it != null }

        if (bests.isEmpty()) {
            return getString(R.string.cycling_records_none)
        }
        return bests.entries.joinToString("\n") { (distance, millis) ->
            "${CyclingFormatter.distanceText(distance)}   ${formatDuration(millis!!)}"
        }
    }

    private fun createRow(
        parent: ViewGroup,
        file: java.io.File,
        summary: RideSummary,
        samples: List<RideSample>,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_ride, parent, false)

        // Speed-graded, same renderer as the full-size trace - a fast ride reads warm even at 64dp.
        row.findViewById<RideTraceView>(R.id.ride_thumbnail).samples = samples

        val date = Date(summary.startedAtMs)
        val stamp = "${DateFormat.getMediumDateFormat(requireContext()).format(date)} " +
            DateFormat.getTimeFormat(requireContext()).format(date)
        val title = RideRecorder.from(requireContext()).titleOf(file)
        row.findViewById<TextView>(R.id.ride_date).text = title ?: stamp

        row.findViewById<TextView>(R.id.ride_stats).text = listOfNotNull(
            // A named ride still needs its date; it moves down here rather than disappearing.
            if (title != null) stamp else null,
            CyclingFormatter.distanceText(summary.distanceMetres),
            formatDuration(summary.movingMillis),
            summary.averageSpeedMps?.let { "${CyclingFormatter.speedValue(it)} ${CyclingFormatter.speedUnit(requireContext())}" },
            summary.averageHeartRateBpm?.let { "$it ${getString(R.string.cycling_unit_bpm)}" },
        ).joinToString(" · ")

        row.setOnClickListener {
            val args = Bundle().apply { putString(RideDetailFragment.EXTRA_FILE_NAME, file.name) }
            (requireActivity() as SettingsActivity)
                .stackFragment(RideDetailFragment::class.java, getString(R.string.cycling_rides_detail_title), args)
        }
        row.findViewById<View>(R.id.ride_row_menu).setOnClickListener { anchor -> showRowMenu(anchor, file) }
        return row
    }

    private fun showRowMenu(anchor: View, file: java.io.File) {
        androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.cycling_ride_rename)
            menu.add(0, MENU_DELETE, 1, R.string.cycling_ride_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> renameRide(file)
                    MENU_DELETE -> confirmDelete(file)
                }
                true
            }
        }.show()
    }

    /**
     * "Tuesday" means nothing a month later, and the route shape only helps for places you know.
     * A name is the one thing that makes a long list searchable by memory.
     */
    private fun renameRide(file: java.io.File) {
        val recorder = RideRecorder.from(requireContext())
        val input = EditText(requireContext()).apply {
            setSingleLine()
            hint = getString(R.string.cycling_ride_name_hint)
            setText(recorder.titleOf(file).orEmpty())
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycling_ride_name_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                recorder.setTitle(file, input.text.toString())
                refresh()
            }
            .show()
    }

    /** A recording is not reproducible, so removing one asks first. */
    private fun confirmDelete(file: java.io.File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycling_ride_delete_title)
            .setMessage(R.string.cycling_ride_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                RideRecorder.from(requireContext()).deleteRide(file)
                refresh()
            }
            .show()
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_DELETE = 2
    }
}
