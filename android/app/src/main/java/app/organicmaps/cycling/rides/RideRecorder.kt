package app.organicmaps.cycling.rides

import android.content.Context
import android.location.Location
import androidx.annotation.MainThread
import app.organicmaps.MwmApplication
import app.organicmaps.cycling.RideMode
import app.organicmaps.cycling.sensors.SensorHub
import app.organicmaps.sdk.location.LocationListener
import app.organicmaps.sdk.util.log.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Records a ride: every GPS fix, with whatever the sensors were reporting at that moment.
 *
 * Samples are appended to a file as they arrive rather than held in memory. A four-hour ride is
 * thousands of samples, and a process killed mid-ride - which Android will happily do - must not
 * lose the whole thing. Worst case you lose the last fix.
 *
 * The recorder is a process-wide singleton because a ride outlives the map activity.
 */
class RideRecorder private constructor(context: Context) : LocationListener {

    private val appContext = context.applicationContext
    private val sensors = SensorHub.from(appContext)

    private var currentFile: File? = null
    private var sampleCount = 0
    private var lastWriteMs = 0L

    private var startedAtMs = 0L
    private var distanceMetres = 0.0
    private var lastPosition: Pair<Double, Double>? = null

    val isRecording: Boolean
        get() = currentFile != null

    /**
     * Elapsed milliseconds and metres covered so far, or null when nothing is being recorded.
     *
     * Accumulated here rather than in the map screen: the recorder sees every fix, while the map
     * only sees the ones that arrive while it is in the foreground, so a status line counting for
     * itself under-reported any ride where the rider pocketed the phone.
     */
    val liveProgress: Pair<Long, Double>?
        get() = if (isRecording) {
            (System.currentTimeMillis() - startedAtMs - pausedTotalMs - currentPauseMs()) to distanceMetres
        } else {
            null
        }

    /**
     * Whether recording is suspended.
     *
     * A café stop or a puncture is not riding, and without this it landed in the ride as an hour of
     * standing still - dragging the average down and leaving a knot of GPS noise on the trace where
     * the bike never moved.
     */
    var isPaused: Boolean = false
        private set

    private var pausedAtMs = 0L
    private var pausedTotalMs = 0L

    private fun currentPauseMs() = if (isPaused) System.currentTimeMillis() - pausedAtMs else 0L

    @MainThread
    fun setPaused(paused: Boolean) {
        if (!isRecording || paused == isPaused) {
            return
        }
        if (paused) {
            pausedAtMs = System.currentTimeMillis()
        } else {
            pausedTotalMs += System.currentTimeMillis() - pausedAtMs
            // Drop the last position: the gap since pausing is not distance the rider covered, and
            // counting it would add the length of a café to the ride.
            lastPosition = null
        }
        isPaused = paused
    }

    @MainThread
    fun start() {
        if (isRecording) {
            return
        }
        val directory = ridesDirectory().also { it.mkdirs() }
        val file = File(directory, "ride-${System.currentTimeMillis()}.jsonl")
        currentFile = file
        sampleCount = 0
        lastWriteMs = 0L
        startedAtMs = System.currentTimeMillis()
        distanceMetres = 0.0
        lastPosition = null
        isPaused = false
        pausedAtMs = 0L
        pausedTotalMs = 0L
        Logger.i(TAG, "Recording ride to ${file.name}")
        RideMode.setRiding(true)
        MwmApplication.from(appContext).getLocationHelper().addListener(this)
    }

    /** Stops recording and returns the finished ride, or null if too little was captured. */
    @MainThread
    fun stop(): RideSummary? {
        val file = currentFile ?: return null
        MwmApplication.from(appContext).getLocationHelper().removeListener(this)
        RideMode.setRiding(false)
        currentFile = null
        lastFinishedFileName = null

        // Read once. A four-hour ride is ~14,000 JSON lines, and parsing it twice at the moment the
        // rider taps Finish is the worst possible time to do the work twice.
        val samples = readSamples(file)
        val summary = RideStatistics.summarise(samples)
        if (summary == null) {
            // A ride with one fix is a false start, not a ride worth keeping.
            Logger.i(TAG, "Discarding ride with $sampleCount sample(s)")
            file.delete()
            return null
        }
        writeSummary(file, summary)
        recordSegmentBests(samples, file.nameWithoutExtension)
        lastFinishedFileName = file.name
        return summary
    }

    /**
     * The ride just finished, so the map can open its summary.
     *
     * Ending a ride used to drop the rider back on the map with a toast, leaving the one thing they
     * wanted - the numbers for the last three hours - four taps away behind Settings.
     */
    var lastFinishedFileName: String? = null
        private set

    override fun onLocationUpdated(location: Location) {
        val file = currentFile ?: return
        if (isPaused) {
            // Nothing is written while paused, so a stationary bike leaves no drift on the trace.
            return
        }

        // One sample per second is plenty for a ride trace and keeps the file small; GPS can
        // deliver faster than that when navigating.
        val now = System.currentTimeMillis()
        if (now - lastWriteMs < MIN_SAMPLE_INTERVAL_MS) {
            return
        }
        lastWriteMs = now

        val snapshot = sensors.snapshot.value
        val sample = RideSample(
            timestampMs = now,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMetres = if (location.hasAltitude()) location.altitude else null,
            gpsSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            sensorSpeedMps = snapshot?.speedMps,
            heartRateBpm = snapshot?.heartRateBpm,
            cadenceRpm = snapshot?.cadenceRpm,
            powerWatts = snapshot?.powerWatts,
        )

        lastPosition?.let { (previousLat, previousLon) ->
            distanceMetres += RideStatistics.haversineMetres(
                previousLat,
                previousLon,
                location.latitude,
                location.longitude,
            )
        }
        lastPosition = location.latitude to location.longitude

        try {
            file.appendText(sample.toJson().toString() + "\n")
            sampleCount++
        } catch (e: java.io.IOException) {
            // Losing a sample is survivable; crashing mid-ride is not.
            Logger.w(TAG, "Could not append ride sample: ${e.message}")
        }
    }

    /**
     * Checks the finished ride against every saved segment and stores any personal best.
     *
     * Without this the live delta would read "first run" forever: something has to turn a completed
     * attempt into the splits that the next attempt is measured against.
     */
    /**
     * Segments beaten by the ride that just finished, so the app can say so instead of a personal
     * best landing silently in a file the rider has to go looking for.
     */
    var lastPersonalBests: List<Pair<String, Long>> = emptyList()
        private set

    private fun recordSegmentBests(samples: List<RideSample>, rideId: String) {
        lastPersonalBests = emptyList()
        if (samples.size < 2) {
            return
        }
        val bestStore = SegmentBestStore(appContext)
        val historyStore = SegmentHistoryStore(appContext)
        val personalBests = mutableListOf<Pair<String, Long>>()
        SegmentStore(appContext).list().forEach { segment ->
            val attempts = SegmentMatcher.allAttempts(segment, samples)
            if (attempts.isEmpty()) {
                return@forEach
            }
            val attempt = attempts.minByOrNull { it.elapsedMillis } ?: return@forEach
            val slice = samples.sortedBy { it.timestampMs }
                .filter { it.timestampMs in attempt.startedAtMs..(attempt.startedAtMs + attempt.elapsedMillis) }
            val best = SegmentBest(
                segmentId = segment.id,
                totalMillis = attempt.elapsedMillis,
                splitsMillis = SegmentMatcher.splitsFor(segment, slice),
                achievedAtMs = attempt.startedAtMs,
            )
            // Only the quickest lap keeps splits - the in-depth trace the live delta races against.
            // Every lap is also kept as a plain line: date, time, average heart rate and power.
            val isPersonalBest = bestStore.saveIfFaster(best)
            attempts.forEach { lap ->
                historyStore.record(
                    segment.id,
                    SegmentRun(
                        rideId = rideId,
                        startedAtMs = lap.startedAtMs,
                        elapsedMillis = lap.elapsedMillis,
                        averageHeartRateBpm = lap.averageHeartRateBpm,
                        averagePowerWatts = lap.averagePowerWatts,
                        wasPersonalBest = isPersonalBest && lap.startedAtMs == attempt.startedAtMs,
                    ),
                )
            }
            if (isPersonalBest) {
                Logger.i(TAG, "New best on ${segment.name}: ${attempt.elapsedMillis} ms")
                personalBests += segment.name to attempt.elapsedMillis
            }
        }
        lastPersonalBests = personalBests
    }

    /** Every finished ride, newest first. */
    fun listRides(): List<File> =
        ridesDirectory().listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Parsed samples for a finished ride, for drawing its trace. */
    fun samplesOf(file: File): List<RideSample> = readSamples(file)

    fun summaryOf(file: File): RideSummary? = RideStatistics.summarise(readSamples(file))

    /**
     * The summary written when the ride finished, without re-reading its samples.
     *
     * Every finished ride already has this on disk and nothing ever read it back, so opening the
     * list re-derived from scratch what had been computed once: a season of rides meant parsing
     * hundreds of thousands of JSON lines on the main thread to show figures that were sitting in
     * a file all along. Falls back to parsing for rides recorded before the summary existed.
     */
    fun storedSummary(file: File): RideSummary? {
        val summaryFile = File(file.parentFile, file.nameWithoutExtension + ".summary.json")
        if (!summaryFile.isFile) {
            return summaryOf(file)
        }
        return try {
            val json = JSONObject(summaryFile.readText())
            RideSummary(
                startedAtMs = json.getLong("startedAtMs"),
                endedAtMs = json.getLong("endedAtMs"),
                distanceMetres = json.getDouble("distanceMetres"),
                movingMillis = json.getLong("movingMillis"),
                averageSpeedMps = json.optDoubleOrNull("averageSpeedMps"),
                maxSpeedMps = json.optDoubleOrNull("maxSpeedMps"),
                averageHeartRateBpm = json.optIntOrNull("averageHeartRateBpm"),
                maxHeartRateBpm = json.optIntOrNull("maxHeartRateBpm"),
                averageCadenceRpm = json.optIntOrNull("averageCadenceRpm"),
                averagePowerWatts = json.optIntOrNull("averagePowerWatts"),
                ascentMetres = json.getDouble("ascentMetres"),
                sampleCount = json.getInt("sampleCount"),
            )
        } catch (e: org.json.JSONException) {
            // A truncated summary must not hide the ride; the samples are the source of truth.
            Logger.w(TAG, "Unreadable summary for ${file.name}: ${e.message}")
            summaryOf(file)
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    /**
     * Removes a ride and anything stored beside it.
     *
     * Recordings could be made but never removed, so a mistaken start - or an hour of the inside of
     * a pocket - stayed in the list for good and kept its share of the disk.
     */
    fun deleteRide(file: File): Boolean {
        titleFile(file).delete()
        return file.delete()
    }

    /**
     * The rider's own name for a ride, or null when it has none.
     *
     * A sidecar beside the recording rather than a field inside it: the ride file is append-only
     * while riding, and rewriting a finished one just to change its name risks the recording for
     * the sake of a label.
     */
    fun titleOf(file: File): String? = try {
        titleFile(file).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
    } catch (e: IOException) {
        null
    }

    fun setTitle(file: File, title: String) {
        try {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) titleFile(file).delete() else titleFile(file).writeText(trimmed)
        } catch (e: IOException) {
            Logger.w(TAG, "Could not name ride ${file.name}: ${e.message}")
        }
    }

    // listRides only looks at .jsonl, so a sidecar never appears as a ride of its own.
    private fun titleFile(file: File) = File(file.parentFile, "${file.name}.title")

    private fun readSamples(file: File): List<RideSample> = try {
        file.readLines().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { JSONObject(line).toSample() }.getOrNull()
        }
    } catch (e: java.io.IOException) {
        Logger.w(TAG, "Could not read ${file.name}: ${e.message}")
        emptyList()
    }

    /** Written next to the samples so a ride list doesn't have to re-parse every file. */
    private fun writeSummary(sampleFile: File, summary: RideSummary) {
        val json = JSONObject().apply {
            put("startedAtMs", summary.startedAtMs)
            put("endedAtMs", summary.endedAtMs)
            put("distanceMetres", summary.distanceMetres)
            put("movingMillis", summary.movingMillis)
            put("averageSpeedMps", summary.averageSpeedMps ?: JSONObject.NULL)
            put("maxSpeedMps", summary.maxSpeedMps ?: JSONObject.NULL)
            put("averageHeartRateBpm", summary.averageHeartRateBpm ?: JSONObject.NULL)
            put("maxHeartRateBpm", summary.maxHeartRateBpm ?: JSONObject.NULL)
            put("averageCadenceRpm", summary.averageCadenceRpm ?: JSONObject.NULL)
            put("averagePowerWatts", summary.averagePowerWatts ?: JSONObject.NULL)
            put("ascentMetres", summary.ascentMetres)
            put("sampleCount", summary.sampleCount)
        }
        try {
            File(sampleFile.parentFile, sampleFile.nameWithoutExtension + ".summary.json").writeText(json.toString())
        } catch (e: java.io.IOException) {
            Logger.w(TAG, "Could not write ride summary: ${e.message}")
        }
    }

    private fun ridesDirectory() = File(appContext.filesDir, "rides")

    private fun RideSample.toJson() = JSONObject().apply {
        put("t", timestampMs)
        put("lat", latitude)
        put("lon", longitude)
        altitudeMetres?.let { put("alt", it) }
        gpsSpeedMps?.let { put("gspd", it) }
        sensorSpeedMps?.let { put("sspd", it) }
        heartRateBpm?.let { put("hr", it) }
        cadenceRpm?.let { put("cad", it) }
        powerWatts?.let { put("pwr", it) }
    }

    private fun JSONObject.toSample() = RideSample(
        timestampMs = getLong("t"),
        latitude = getDouble("lat"),
        longitude = getDouble("lon"),
        altitudeMetres = if (has("alt")) getDouble("alt") else null,
        gpsSpeedMps = if (has("gspd")) getDouble("gspd") else null,
        sensorSpeedMps = if (has("sspd")) getDouble("sspd") else null,
        heartRateBpm = if (has("hr")) getInt("hr") else null,
        cadenceRpm = if (has("cad")) getInt("cad") else null,
        powerWatts = if (has("pwr")) getInt("pwr") else null,
    )

    companion object {
        private const val TAG = "RideRecorder"

        private const val MIN_SAMPLE_INTERVAL_MS = 1_000L

        @Volatile
        private var instance: RideRecorder? = null

        @JvmStatic
        fun from(context: Context): RideRecorder = instance ?: synchronized(this) {
            instance ?: RideRecorder(context).also { instance = it }
        }
    }
}
