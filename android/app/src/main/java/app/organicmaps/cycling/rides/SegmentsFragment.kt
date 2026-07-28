package app.organicmaps.cycling.rides

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import app.organicmaps.BuildConfig
import java.io.File
import java.io.IOException
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.TimeUnit

/**
 * Your saved segments and the best time on each.
 *
 * Until now segments could be created and raced but never seen, which made the whole feature
 * invisible - there was no way to tell what existed, let alone remove one created by mistake.
 */
class SegmentsFragment : BaseMwmFragment() {

    /**
     * Registered as a field so it exists before the fragment starts, which the result API requires.
     *
     * Any file type is offered rather than JSON only: a segment arrives through mail, chat or a
     * download and picks up whatever type that app assigned, so filtering hides the very file the
     * rider is trying to open. The content is validated instead.
     */
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFrom(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_segments, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))
        view.findViewById<View>(R.id.segments_import).setOnClickListener {
            importPicker.launch(arrayOf("*/*"))
        }
        render(view)
    }

    /** Reads a shared segment file and adds it to the list. */
    private fun importFrom(uri: android.net.Uri) {
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                // A segment is a few kilobytes; the cap stops a wrong pick from loading a video
                // into memory.
                String(stream.readBytes().take(MAX_IMPORT_BYTES).toByteArray())
            }
        } catch (e: Exception) {
            null
        }

        val store = SegmentStore(requireContext())
        val parsed = text?.let { store.fromJson(it, getString(R.string.cycling_segment_imported_name)) }
        if (parsed == null) {
            Toast.makeText(requireContext(), R.string.cycling_segment_import_failed, Toast.LENGTH_LONG).show()
            return
        }

        val stored = store.importSegment(parsed)
        Toast.makeText(
            requireContext(),
            getString(R.string.cycling_segment_imported, stored.name),
            Toast.LENGTH_LONG,
        ).show()
        render(requireView())
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
        val historyStore = SegmentHistoryStore(requireContext())
        segments.forEach { segment ->
            val row = layoutInflater.inflate(R.layout.item_segment, list, false)
            row.findViewById<TextView>(R.id.segment_row_name).text = segment.name

            val best = bestStore.load(segment.id)
            row.findViewById<TextView>(R.id.segment_row_detail).text = listOfNotNull(
                CyclingFormatter.distanceText(segment.lengthMetres),
                best?.let { getString(R.string.cycling_segment_best, formatDuration(it.totalMillis)) }
                    ?: getString(R.string.cycling_segment_no_time),
            ).joinToString(" · ")

            val runs = historyStore.runs(segment.id)
            val topAllTime = historyStore.topRuns(segment.id)
            val latestRide = historyStore.latestRideId(segment.id)
            val topThisSession = latestRide?.let { historyStore.topRunsInSession(segment.id, it) }.orEmpty()
            // Tapping expands the attempt list in place - a segment you race has a history, and
            // pushing another screen for a handful of times would be heavier than it deserves.
            // Sloping down is progress, readable before any time is.
            row.findViewById<SparklineView>(R.id.segment_row_sparkline).times = runs.map { it.elapsedMillis }

            val runsView: TextView = row.findViewById(R.id.segment_row_runs)
            row.setOnClickListener {
                runsView.visibility = if (runsView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                runsView.text = if (runs.isEmpty()) {
                    getString(R.string.cycling_segment_runs_none)
                } else {
                    buildTables(topAllTime, topThisSession)
                }
            }

            row.findViewById<ImageView>(R.id.segment_row_share).setOnClickListener { shareSegment(segment) }

            row.findViewById<ImageView>(R.id.segment_row_delete).setOnClickListener {
                confirmDelete(segment, runs.size, root)
            }
            list.addView(row)
        }
    }

    /**
     * Two ranked tables: your quickest ever, and your quickest from the most recent outing.
     *
     * All-time answers "am I getting faster"; this session answers "which lap was my good one",
     * which is the question while the ride is still fresh.
     */
    private fun buildTables(allTime: List<SegmentRun>, session: List<SegmentRun>): String {
        val builder = StringBuilder()
        builder.append(getString(R.string.cycling_segment_top_all_time)).append("\n")
        builder.append(rank(allTime))
        if (session.isNotEmpty()) {
            builder.append("\n\n").append(getString(R.string.cycling_segment_top_session)).append("\n")
            builder.append(rank(session))
        }
        return builder.toString()
    }

    private fun rank(runs: List<SegmentRun>): String = runs.mapIndexed { index, run ->
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
            .format(java.util.Date(run.startedAtMs))
        val pb = if (run.wasPersonalBest) "  ${getString(R.string.cycling_segment_pb_mark)}" else ""
        val heartRate = run.averageHeartRateBpm?.let { "  $it ${getString(R.string.cycling_unit_bpm)}" }.orEmpty()
        "${index + 1}.  ${formatDuration(run.elapsedMillis)}   $date$heartRate$pb"
    }.joinToString("\n")

    /**
     * Sends the segment as a file so someone else can ride it.
     *
     * Two riders comparing times needs no server if they can exchange the course itself: the
     * receiver imports it, rides it, and their times are measured against the same start and end.
     * Plain JSON in the app's own format, which is also what the app reads back.
     */
    private fun shareSegment(segment: Segment) {
        try {
            val exportDir = File(requireContext().cacheDir, "exports").also { it.mkdirs() }
            val file = File(exportDir, "${segment.name.replace(Regex("[^A-Za-z0-9-_]"), "-")}.segment.json")
            file.writeText(SegmentStore(requireContext()).toJson(segment))

            val uri = FileProvider.getUriForFile(requireContext(), BuildConfig.FILE_PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, segment.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.cycling_segment_share)))
        } catch (e: IOException) {
            Toast.makeText(requireContext(), R.string.cycling_segment_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Deleting takes every recorded attempt with it, so it asks first.
     *
     * A segment ridden all season is not recoverable from anywhere else, and the delete control
     * sits a thumb-width from the row you tap to expand it. Losing that to a mis-tap is the worst
     * thing this screen could do.
     */
    private fun confirmDelete(segment: Segment, runCount: Int, root: View) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.cycling_segment_delete_title, segment.name))
            .setMessage(
                if (runCount > 0) {
                    getString(R.string.cycling_segment_delete_message, runCount)
                } else {
                    getString(R.string.cycling_segment_delete_message_empty)
                },
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                SegmentHistoryStore(requireContext()).delete(segment.id)
                SegmentStore(requireContext()).delete(segment.id)
                // Re-render rather than removing the row, so the empty state appears when the last
                // segment goes.
                render(root)
            }
            .show()
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return "${minutes}m ${seconds}s"
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    }
}
