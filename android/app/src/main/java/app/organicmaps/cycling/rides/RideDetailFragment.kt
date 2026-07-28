package app.organicmaps.cycling.rides

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.cycling.CyclingFormatter
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One ride: its route drawn and colour-graded, its numbers, and the option to turn it into a
 * segment you can race yourself around later.
 */
class RideDetailFragment : BaseMwmFragment() {

    private lateinit var trace: RideTraceView
    private var samples: List<RideSample> = emptyList()

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
        stats.text = buildStats(summary)

        view.findViewById<Button>(R.id.ride_metric_speed).setOnClickListener {
            trace.metric = RideTraceView.Metric.SPEED
        }
        view.findViewById<Button>(R.id.ride_metric_heart_rate).setOnClickListener {
            trace.metric = RideTraceView.Metric.HEART_RATE
        }
        view.findViewById<Button>(R.id.ride_save_segment).setOnClickListener { saveAsSegment(file) }
    }

    /** Re-derives samples through the recorder so the file format stays in one place. */
    private fun readSamples(recorder: RideRecorder, file: File): List<RideSample> =
        recorder.samplesOf(file)

    private fun buildStats(summary: RideSummary): String {
        val speedUnit = CyclingFormatter.speedUnit(requireContext())
        return listOfNotNull(
            getString(R.string.cycling_rides_distance, CyclingFormatter.distanceText(summary.distanceMetres)),
            getString(R.string.cycling_rides_moving, formatDuration(summary.movingMillis)),
            getString(R.string.cycling_rides_elapsed, formatDuration(summary.elapsedMillis)),
            summary.averageSpeedMps?.let {
                getString(R.string.cycling_rides_avg_speed, "${CyclingFormatter.speedValue(it)} $speedUnit")
            },
            summary.maxSpeedMps?.let {
                getString(R.string.cycling_rides_max_speed, "${CyclingFormatter.speedValue(it)} $speedUnit")
            },
            summary.averageHeartRateBpm?.let { getString(R.string.cycling_rides_avg_hr, it) },
            summary.maxHeartRateBpm?.let { getString(R.string.cycling_rides_max_hr, it) },
            summary.averageCadenceRpm?.let { getString(R.string.cycling_rides_avg_cadence, it) },
            summary.averagePowerWatts?.let { getString(R.string.cycling_rides_avg_power, it) },
            getString(R.string.cycling_rides_ascent, CyclingFormatter.distanceText(summary.ascentMetres)),
        ).joinToString("\n")
    }

    private fun saveAsSegment(rideFile: File) {
        if (samples.size < 2) {
            return
        }
        val name = getString(R.string.cycling_segment_default_name, rideFile.nameWithoutExtension)
        val segment = SegmentMatcher.fromRide(rideFile.nameWithoutExtension, name, samples)
        SegmentStore(requireContext()).save(segment)

        val best = SegmentMatcher.bestAttempt(segment, samples)
        val message = if (best == null) {
            getString(R.string.cycling_segment_saved, name)
        } else {
            getString(R.string.cycling_segment_saved_with_time, name, formatDuration(best.elapsedMillis))
        }
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
    }

    companion object {
        const val EXTRA_FILE_NAME = "ride_file_name"
    }
}
