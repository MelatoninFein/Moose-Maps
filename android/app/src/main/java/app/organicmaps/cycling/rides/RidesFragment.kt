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
        val recordsGrid: LinearLayout = view.findViewById(R.id.records_grid)
        val totalsTitle: TextView = view.findViewById(R.id.totals_title)
        val totalsGrid: LinearLayout = view.findViewById(R.id.totals_grid)
        list.removeAllViews()
        recordsGrid.removeAllViews()
        totalsGrid.removeAllViews()

        val recorder = RideRecorder.from(requireContext())
        val rides = recorder.listRides()

        val emptyAction: View = view.findViewById(R.id.rides_empty_action)
        if (rides.isEmpty()) {
            empty.visibility = View.VISIBLE
            recordsTitle.visibility = View.GONE
            totalsTitle.visibility = View.GONE
            // Recording starts on the map, so the empty state offers the way back to it rather
            // than leaving the rider to find it.
            emptyAction.visibility = View.VISIBLE
            emptyAction.setOnClickListener { requireActivity().finish() }
            return
        }
        empty.visibility = View.GONE
        emptyAction.visibility = View.GONE

        // Parsed once per ride and reused for the totals, the records, the rows and the thumbnails.
        // Reading each file four times would make a season's list slow to open for no gain.
        val parsed = rides.mapNotNull { file ->
            val samples = recorder.samplesOf(file)
            RideStatistics.summarise(samples)?.let { Triple(file, it, samples) }
        }

        buildTotals(totalsTitle, totalsGrid, parsed.map { it.second })
        buildRecords(recordsTitle, recordsGrid, parsed.map { it.third })

        var lastMonth = ""
        parsed.forEach { (file, summary, samples) ->
            val month = monthLabel(summary.startedAtMs)
            if (month != lastMonth) {
                lastMonth = month
                val monthRides = parsed.filter { monthLabel(it.second.startedAtMs) == month }
                val header = layoutInflater.inflate(R.layout.item_ride_month, list, false) as TextView
                header.text = "$month · " +
                    CyclingFormatter.distanceText(monthRides.sumOf { it.second.distanceMetres })
                list.addView(header)
            }
            list.addView(createRow(list, file, summary, samples))
        }
    }

    /**
     * This month at the top: distance, time and how many rides.
     *
     * "How much have I ridden this month" is the question a rider opens this screen with, and it
     * was the one thing the screen could not answer without adding up the rows by hand.
     */
    private fun buildTotals(title: TextView, grid: LinearLayout, summaries: List<RideSummary>) {
        val monthStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val thisMonth = summaries.filter { it.startedAtMs >= monthStart }
        if (thisMonth.isEmpty()) {
            title.visibility = View.GONE
            grid.visibility = View.GONE
            return
        }
        title.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE

        fillGrid(
            grid,
            listOf(
                CyclingFormatter.distanceText(thisMonth.sumOf { it.distanceMetres })
                    to getString(R.string.cycling_metric_distance),
                formatDuration(thisMonth.sumOf { it.movingMillis })
                    to getString(R.string.cycling_metric_moving),
                thisMonth.size.toString() to getString(R.string.cycling_metric_rides),
                CyclingFormatter.ascentText(thisMonth.sumOf { it.ascentMetres })
                    to getString(R.string.cycling_metric_ascent),
            ),
        )
    }

    private fun monthLabel(startedAtMs: Long): String =
        java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault())
            .format(Date(startedAtMs))

    private fun refresh() = view?.let { render(it) }

    /**
     * Fastest ever over each tracked distance, across every ride.
     *
     * Computed on open rather than stored: it has to be re-derived whenever a ride is added or
     * removed anyway, and a rider has tens of rides, not thousands.
     */
    private fun buildRecords(title: TextView, grid: LinearLayout, rides: List<List<RideSample>>) {
        val bests = PersonalRecords.TRACKED_DISTANCES.mapNotNull { distance ->
            rides.mapNotNull { PersonalRecords.fastestOverDistance(it, distance) }
                .minOrNull()
                ?.let { distance to it }
        }

        if (bests.isEmpty()) {
            // Nothing beaten yet is worth saying once; an empty panel would just look broken.
            title.visibility = View.GONE
            grid.visibility = View.GONE
            return
        }
        title.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        // The time is the achievement and the distance names it, so the time is the large figure.
        fillGrid(
            grid,
            bests.map { (distance, millis) ->
                formatTime(millis) to CyclingFormatter.distanceText(distance)
            },
        )
    }

    /** Lays value/label pairs out as the same tiles the ride screen uses. */
    private fun fillGrid(grid: LinearLayout, tiles: List<Pair<String, String>>) {
        tiles.chunked(TILES_PER_ROW).forEach { rowTiles ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { (value, label) ->
                val tile = layoutInflater.inflate(R.layout.item_ride_stat, row, false)
                tile.findViewById<TextView>(R.id.stat_value).text = value
                tile.findViewById<TextView>(R.id.stat_label).text = label
                row.addView(tile)
            }
            // Pad a short final row so its tiles keep the same width as the rows above.
            repeat(TILES_PER_ROW - rowTiles.size) {
                row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }
    }

    /** Records are minutes and seconds, not the rounded hours the ride rows use. */
    private fun formatTime(millis: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        return String.format(
            java.util.Locale.getDefault(),
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60,
        )
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
            summary.averageSpeedMps?.let {
                "${CyclingFormatter.speedValue(it)} ${CyclingFormatter.speedUnit(requireContext())}"
            },
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
        const val TILES_PER_ROW = 3
    }
}
