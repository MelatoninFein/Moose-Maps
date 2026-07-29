package app.organicmaps.cycling.rides

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import app.organicmaps.BuildConfig
import androidx.core.view.ViewCompat
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One ride: its route drawn and colour-graded, its numbers, and the option to turn it into a
 * segment you can race yourself around later.
 */
class RideDetailFragment : BaseMwmFragment() {

    private lateinit var trace: RideTraceView
    private var samples: List<RideSample> = emptyList()

    /** Fractions of the ride the segment should span, as set by the trim slider. */
    private var trimStart = 0f
    private var trimEnd = 100f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_ride_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))

        trace = view.findViewById(R.id.ride_trace)
        val stats: TextView = view.findViewById(R.id.ride_detail_stats)
        val empty: TextView = view.findViewById(R.id.ride_detail_empty)

        val fileName = arguments?.getString(EXTRA_FILE_NAME)
        val recorder = RideRecorder.from(requireContext())
        val file = fileName?.let { name -> recorder.listRides().firstOrNull { it.name == name } }

        if (file == null) {
            empty.visibility = View.VISIBLE
            return
        }

        samples = readSamples(recorder, file)
        val summary = RideStatistics.summarise(samples)
        if (summary == null) {
            empty.visibility = View.VISIBLE
            return
        }

        empty.visibility = View.GONE
        trace.samples = samples
        // Headline figures as tiles; the long tail stays as text below the chart.
        buildStatGrid(view.findViewById(R.id.ride_stats_grid), summary)
        // Zones as a proportional bar: the shape of a session reads before any number does.
        val maxHr = SensorHub.from(requireContext()).store.maxHeartRateBpm
        val zones = HeartRateZones.timeInZones(samples, maxHr)
        val zoneBar: ZoneBarView = view.findViewById(R.id.ride_zone_bar)
        zoneBar.timeInZones = zones
        view.findViewById<TextView>(R.id.ride_zones_title).visibility =
            if (zones.values.sum() > 0) View.VISIBLE else View.GONE
        stats.visibility = View.GONE

        wireChart(view)

        view.findViewById<Button>(R.id.ride_export_gpx).setOnClickListener { exportGpx(file) }

        val trim: com.google.android.material.slider.RangeSlider = view.findViewById(R.id.ride_trim)
        val trimLabel: TextView = view.findViewById(R.id.ride_trim_label)
        trim.values = listOf(0f, 100f)
        updateTrimLabel(trimLabel)
        trim.addOnChangeListener { slider, _, _ ->
            trimStart = slider.values.first()
            trimEnd = slider.values.last()
            // Redraw the trace so the chosen stretch is visible rather than guessed at.
            trace.samples = trimmedSamples()
            updateTrimLabel(trimLabel)
        }

        // First tap reveals the stretch picker, second saves what was picked. Asking for the whole
        // ride is the common case, so the picker starts covering all of it and can simply be
        // confirmed - the two taps cost nothing to someone who does not want to trim.
        val saveButton: Button = view.findViewById(R.id.ride_save_segment)
        saveButton.setOnClickListener {
            if (trim.visibility == View.VISIBLE) {
                saveAsSegment(file)
            } else {
                trim.visibility = View.VISIBLE
                trimLabel.visibility = View.VISIBLE
                saveButton.setText(R.string.cycling_segment_save_confirm)
            }
        }
    }

    /** Re-derives samples through the recorder so the file format stays in one place. */
    private fun readSamples(recorder: RideRecorder, file: File): List<RideSample> =
        recorder.samplesOf(file)

    /**
     * The figures a rider looks for first, as tiles in rows of three.
     *
     * Only what is actually present: a ride with no power meter should not show an empty watts
     * tile, and the grid closes up around what is missing.
     */
    /** The chart, the chips that drive it, and which of them this ride has any data for. */
    private fun wireChart(view: View) {
        val chart: RideChartView = view.findViewById(R.id.ride_chart)
        chart.samples = samples
        // Drag the chart, mark the road: "where was I when my heart rate hit that" had no answer.
        chart.onScrub = { index -> trace.highlightIndex = index }
        view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_hr)
            .setOnCheckedChangeListener { _, checked -> chart.showHeartRate = checked }
        view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_speed)
            .setOnCheckedChangeListener { _, checked -> chart.showSpeed = checked }
        view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_cadence)
            .setOnCheckedChangeListener { _, checked -> chart.showCadence = checked }
        view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_power)
            .setOnCheckedChangeListener { _, checked -> chart.showPower = checked }

        // A chip for a metric this ride never recorded is dead UI - toggling it changes nothing and
        // implies the data is there to be found. Same rule the live tiles follow: a rider with no
        // strap sees no strap controls.
        val hasHeartRate = samples.any { it.heartRateBpm != null }
        showChipIf(view, R.id.chip_hr, hasHeartRate)
        showChipIf(view, R.id.chip_cadence, samples.any { it.cadenceRpm != null })
        showChipIf(view, R.id.chip_power, samples.any { it.powerWatts != null })
        showChipIf(view, R.id.chip_trace_heart_rate, hasHeartRate)

        // Single selection, so the chosen chip shows which metric the trace is coloured by.
        view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.ride_trace_metric)
            .setOnCheckedStateChangeListener { _, checked ->
                trace.metric = if (checked.firstOrNull() == R.id.chip_trace_heart_rate) {
                    RideTraceView.Metric.HEART_RATE
                } else {
                    RideTraceView.Metric.SPEED
                }
            }

        // With every series absent the chart itself has nothing left to draw.
        val hasSpeed = samples.any { it.gpsSpeedMps != null || it.sensorSpeedMps != null }
        chart.visibility = if (hasSpeed || hasHeartRate) View.VISIBLE else View.GONE
    }

    private fun showChipIf(root: View, chipId: Int, present: Boolean) {
        root.findViewById<com.google.android.material.chip.Chip>(chipId).visibility =
            if (present) View.VISIBLE else View.GONE
    }

    private fun buildStatGrid(grid: LinearLayout, summary: RideSummary) {
        grid.removeAllViews()
        val speedUnit = CyclingFormatter.speedUnit(requireContext())
        val tiles = buildList {
            add(CyclingFormatter.distanceText(summary.distanceMetres) to getString(R.string.cycling_metric_distance))
            add(formatDuration(summary.movingMillis) to getString(R.string.cycling_metric_moving))
            summary.averageSpeedMps?.let { add(CyclingFormatter.speedValue(it) to speedUnit) }
            summary.averageHeartRateBpm?.let { add("$it" to getString(R.string.cycling_unit_bpm)) }
            summary.averageCadenceRpm?.let { add("$it" to getString(R.string.cycling_unit_rpm)) }
            summary.averagePowerWatts?.let { add("$it" to getString(R.string.cycling_unit_watts)) }
            add(CyclingFormatter.ascentText(summary.ascentMetres) to getString(R.string.cycling_metric_ascent))
        }

        tiles.chunked(TILES_PER_ROW).forEach { rowTiles ->
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
            }
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

    /**
     * Writes the ride as GPX and hands it to whatever the user wants to send it with.
     *
     * The file goes to the cache directory, which is already declared in the FileProvider paths,
     * and is shared by content URI rather than file path - a file:// URI has been illegal to share
     * since Android 7.
     */
    private fun exportGpx(rideFile: File) {
        if (samples.isEmpty()) {
            return
        }
        try {
            val exportDir = File(requireContext().cacheDir, "exports").also { it.mkdirs() }
            val name = rideFile.nameWithoutExtension
            val gpxFile = File(exportDir, "$name.gpx")
            gpxFile.writeText(GpxExporter.export(name, samples))

            val uri = FileProvider.getUriForFile(requireContext(), BuildConfig.FILE_PROVIDER_AUTHORITY, gpxFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.cycling_rides_export)))
        } catch (e: IOException) {
            Toast.makeText(requireContext(), R.string.cycling_rides_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Asks what to call the segment before saving it.
     *
     * A segment you race for months deserves a name you chose - an auto-generated one built from a
     * file timestamp is unreadable in a list and impossible to recognise on the map.
     */
    private fun saveAsSegment(rideFile: File) {
        if (samples.size < 2) {
            return
        }
        val input = EditText(requireContext()).apply {
            setSingleLine()
            hint = getString(R.string.cycling_segment_name_hint)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycling_segment_name_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val typed = input.text?.toString()?.trim().orEmpty()
                saveSegmentNamed(rideFile, typed.ifBlank { rideFile.nameWithoutExtension })
            }
            .show()
    }

    /**
     * The stretch of the ride the segment covers.
     *
     * Sliced by sample index rather than by distance: samples are one second apart, so index is a
     * good proxy for progress and avoids recomputing cumulative distance on every slider tick.
     */
    private fun trimmedSamples(): List<RideSample> {
        if (samples.isEmpty()) {
            return samples
        }
        val from = (samples.size * trimStart / 100f).toInt().coerceIn(0, samples.lastIndex)
        val to = (samples.size * trimEnd / 100f).toInt().coerceIn(from + 1, samples.size)
        return samples.subList(from, to)
    }

    private fun updateTrimLabel(label: TextView) {
        val slice = trimmedSamples()
        val distance = RideStatistics.summarise(slice)?.distanceMetres ?: 0.0
        label.text = getString(R.string.cycling_segment_trim, CyclingFormatter.distanceText(distance))
    }

    private fun saveSegmentNamed(rideFile: File, name: String) {
        val segment = SegmentMatcher.fromRide(rideFile.nameWithoutExtension, name, trimmedSamples())
        SegmentStore(requireContext()).save(segment)

        val best = SegmentMatcher.bestAttempt(segment, trimmedSamples())
        val message = if (best == null) {
            getString(R.string.cycling_segment_saved, name)
        } else {
            getString(R.string.cycling_segment_saved_with_time, name, formatDuration(best.elapsedMillis))
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    companion object {
        const val EXTRA_FILE_NAME = "ride_file_name"

        /** Three tiles read comfortably on a phone; four starts truncating the numbers. */
        private const val TILES_PER_ROW = 3
    }
}
