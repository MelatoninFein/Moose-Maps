package app.organicmaps.cycling.rides

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * One segment in full: the course, the record, and every attempt ranked.
 *
 * The attempt list used to expand inside its row as a block of text - two ranked tables crammed
 * into a single label, with no picture of the course they belonged to. A segment ridden all season
 * is the thing this fork exists for, and it was the least developed screen in the app.
 */
class SegmentDetailFragment : BaseMwmFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_segment_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))

        val segmentId = arguments?.getString(EXTRA_SEGMENT_ID) ?: return
        val segment = SegmentStore(requireContext()).list().firstOrNull { it.id == segmentId } ?: return

        view.findViewById<RideTraceView>(R.id.segment_course).coursePoints = segment.points

        val history = SegmentHistoryStore(requireContext())
        val runs = history.runs(segmentId)
        val best = SegmentBestStore(requireContext()).load(segmentId)

        buildStats(view.findViewById(R.id.segment_stats_grid), segment, runs, best)

        showSectors(view, best)

        view.findViewById<SparklineView>(R.id.segment_detail_sparkline).times = runs.map { it.elapsedMillis }

        val allTimeTitle: TextView = view.findViewById(R.id.segment_all_time_title)
        val allTimeList: LinearLayout = view.findViewById(R.id.segment_all_time_list)
        val sessionTitle: TextView = view.findViewById(R.id.segment_session_title)
        val sessionList: LinearLayout = view.findViewById(R.id.segment_session_list)
        val empty: TextView = view.findViewById(R.id.segment_detail_empty)

        if (runs.isEmpty()) {
            // A saved-but-unridden segment is a normal state, not a broken screen.
            allTimeTitle.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }

        fillRuns(allTimeList, history.topRuns(segmentId))

        // "Which lap was my good one" is the question while the ride is still fresh; it only has an
        // answer when the most recent outing actually contained more than one attempt.
        val session = history.latestRideId(segmentId)
            ?.let { history.topRunsInSession(segmentId, it) }
            .orEmpty()
        if (session.size > 1) {
            sessionTitle.visibility = View.VISIBLE
            fillRuns(sessionList, session)
        }
    }

    /**
     * The best lap's sectors, graded against the outright record for each.
     *
     * Same colours as the live banner and the finish card on purpose: purple, green and yellow have
     * to mean one thing across the app or they mean nothing. Here they answer where the remaining
     * time is - a yellow middle sector in your best lap is the bit still worth attacking.
     */
    private fun showSectors(view: View, best: SegmentBest?) {
        val sectors = best?.splitsMillis?.let { Sectors.sectorTimes(it) }.orEmpty()
        val title: TextView = view.findViewById(R.id.segment_sector_title)
        val lights: SectorLightsView = view.findViewById(R.id.segment_sector_lights)
        val times: TextView = view.findViewById(R.id.segment_sector_times)
        if (sectors.isEmpty()) {
            return
        }

        title.visibility = View.VISIBLE
        lights.visibility = View.VISIBLE
        times.visibility = View.VISIBLE
        lights.currentSector = -1
        lights.grades = sectors.mapIndexed { index, millis ->
            SectorGrade.of(
                actualMillis = millis,
                recordMillis = best?.sectorRecordsMillis?.getOrNull(index),
                // Compared against the record only: this *is* the best lap, so grading it against
                // itself would paint every sector green and say nothing.
                bestLapMillis = null,
            )
        }
        times.text = sectors.joinToString("   ") { formatDuration(it) }
    }

    private fun buildStats(
        grid: LinearLayout,
        segment: Segment,
        runs: List<SegmentRun>,
        best: SegmentBest?,
    ) {
        val tiles = buildList {
            add(CyclingFormatter.distanceText(segment.lengthMetres) to getString(R.string.cycling_metric_distance))
            add(runs.size.toString() to getString(R.string.cycling_metric_attempts))
            best?.let { add(formatDuration(it.totalMillis) to getString(R.string.cycling_metric_best)) }
            if (runs.isNotEmpty()) {
                add(
                    formatDuration(runs.sumOf { it.elapsedMillis } / runs.size)
                        to getString(R.string.cycling_metric_average),
                )
            }
        }

        tiles.chunked(TILES_PER_ROW).forEach { rowTiles ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { (value, label) ->
                val tile = layoutInflater.inflate(R.layout.item_ride_stat, row, false)
                tile.findViewById<TextView>(R.id.stat_value).text = value
                tile.findViewById<TextView>(R.id.stat_label).text = label
                row.addView(tile)
            }
            repeat(TILES_PER_ROW - rowTiles.size) {
                row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
            }
            grid.addView(row)
        }
    }

    private fun fillRuns(list: LinearLayout, runs: List<SegmentRun>) {
        list.removeAllViews()
        runs.forEachIndexed { index, run ->
            val row = layoutInflater.inflate(R.layout.item_segment_run, list, false)
            row.findViewById<TextView>(R.id.run_rank).text = "${index + 1}"
            row.findViewById<TextView>(R.id.run_time).text = formatDuration(run.elapsedMillis)
            row.findViewById<TextView>(R.id.run_detail).text = listOfNotNull(
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(run.startedAtMs)),
                run.averageHeartRateBpm?.let { "$it ${getString(R.string.cycling_unit_bpm)}" },
                run.averagePowerWatts?.let { "$it ${getString(R.string.cycling_unit_watts)}" },
            ).joinToString(" · ")
            row.findViewById<TextView>(R.id.run_badge).apply {
                visibility = if (run.wasPersonalBest) View.VISIBLE else View.GONE
                text = getString(R.string.cycling_segment_pb_mark)
            }
            list.addView(row)
        }
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return "${minutes}m ${seconds}s"
    }

    companion object {
        const val EXTRA_SEGMENT_ID = "segment_id"
        private const val TILES_PER_ROW = 3
    }
}
