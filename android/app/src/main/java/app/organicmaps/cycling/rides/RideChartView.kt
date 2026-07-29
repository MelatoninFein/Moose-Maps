package app.organicmaps.cycling.rides

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.organicmaps.R
import app.organicmaps.cycling.CyclingFormatter
import kotlin.math.max

/**
 * Heart rate, speed, cadence and power drawn against distance for a finished ride.
 *
 * Numbers in a list tell you your average; a line tells you where you buried yourself and where you
 * sat up, which is the reason to record sensors at all. Several series are drawn together so they
 * can be read against each other - heart rate lagging a climb is only visible next to the gradient
 * of the effort that caused it.
 *
 * Drawn directly rather than pulled from a charting library: four polylines over a shared x axis is
 * less code than configuring one, and it avoids a dependency for a single screen.
 */
class RideChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** One drawable series, already reduced to plain numbers with its own scale. */
    private data class Series(
        val label: String,
        val colour: Int,
        val values: List<Double?>,
        val minimum: Double,
        val maximum: Double,
    )

    /** The drawable area, once the axis labels have taken their margin. */
    private data class Plot(val left: Float, val right: Float, val top: Float, val bottom: Float)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11f) }

    // The terrain the other lines are read against, so it is quiet: a filled band and a thin edge
    // rather than a fifth competing colour.
    private val elevationFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val elevationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeJoin = Paint.Join.ROUND
    }

    var samples: List<RideSample> = emptyList()
        set(value) {
            field = value.sortedBy { it.timestampMs }
            rebuildSeries()
        }

    /** Which series to draw. Toggled from the ride screen so the chart doesn't become soup. */
    var showHeartRate = true
        set(value) { field = value; rebuildSeries() }
    var showSpeed = true
        set(value) { field = value; rebuildSeries() }
    var showCadence = false
        set(value) { field = value; rebuildSeries() }
    var showPower = false
        set(value) { field = value; rebuildSeries() }

    /**
     * The drawable series, rebuilt only when the data or the chosen series change.
     *
     * They were previously assembled inside onDraw, which allocated four lists and re-derived each
     * series' minimum and maximum on every frame. Scrubbing redraws on every touch move, so that
     * was several allocations and a full pass over the ride per finger movement - exactly the
     * pattern that produces visible stutter.
     */
    private var series: List<Series> = emptyList()

    /**
     * Cumulative distance at each sample, and the altitude profile.
     *
     * The elevation profile is the chart a cyclist actually reads - it is how a ride is recognised
     * and how every other line is interpreted, since heart rate means one thing on a climb and
     * another on a descent. The altitude was being recorded all along and never drawn.
     *
     * It is deliberately not one of the toggleable series: it is the backdrop the others are read
     * against, not a competing line, which is why it is drawn filled and behind them.
     */
    private var cumulativeMetres = DoubleArray(0)
    private var altitudes: List<Double?> = emptyList()
    private var altitudeMin = 0.0
    private var altitudeMax = 1.0

    private fun rebuildProfile() {
        val points = samples
        if (points.size < 2) {
            cumulativeMetres = DoubleArray(0)
            altitudes = emptyList()
            return
        }
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + RideStatistics.haversineMetres(
                points[i - 1].latitude,
                points[i - 1].longitude,
                points[i].latitude,
                points[i].longitude,
            )
        }
        cumulativeMetres = cumulative

        altitudes = points.map { it.altitudeMetres }
        val present = altitudes.filterNotNull()
        altitudeMin = present.minOrNull() ?: 0.0
        // A flat ride must not be magnified into a mountain range by its own noise.
        altitudeMax = max(present.maxOrNull() ?: 1.0, altitudeMin + MIN_ALTITUDE_SPAN_M)
    }

    private fun rebuildSeries() {
        val points = samples
        rebuildProfile()
        series = buildList {
            if (showHeartRate) add(build("HR", HR_COLOUR, points.map { it.heartRateBpm?.toDouble() }))
            if (showSpeed) add(build(SPEED_LABEL, SPEED_COLOUR, points.map { it.gpsSpeedMps ?: it.sensorSpeedMps }))
            if (showCadence) add(build("Cad", CADENCE_COLOUR, points.map { it.cadenceRpm?.toDouble() }))
            if (showPower) add(build("Pwr", POWER_COLOUR, points.map { it.powerWatts?.toDouble() }))
        }
        invalidate()
    }

    /**
     * Each series is scaled to its own range: heart rate and speed share no units, and a common
     * axis would flatten one of them into the baseline.
     */
    private fun build(label: String, colour: Int, values: List<Double?>): Series {
        val present = values.filterNotNull()
        val minimum = present.minOrNull() ?: 0.0
        val maximum = max(present.maxOrNull() ?: 1.0, minimum + 1e-6)
        return Series(label, colour, values, minimum, maximum)
    }

    /**
     * The sample being inspected, or null when nothing is.
     *
     * A chart says a rider hit 168 bpm somewhere; it never said where. Dragging along it marks the
     * same moment on the route above, which is the question anyone actually has about a hard patch.
     */
    var highlightIndex: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Notified as the rider drags, so the trace can mark the same sample. */
    var onScrub: ((Int?) -> Unit)? = null

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        isFakeBoldText = true
    }

    init {
        axisPaint.color = ContextCompat.getColor(context, R.color.divider)
        labelPaint.color = ContextCompat.getColor(context, R.color.text_dark_subtitle)
        highlightPaint.color = ContextCompat.getColor(context, R.color.text_dark_subtitle)
        elevationFillPaint.color = ContextCompat.getColor(context, R.color.chart_elevation_fill)
        elevationLinePaint.color = ContextCompat.getColor(context, R.color.chart_elevation_line)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (samples.size < 2) {
            return false
        }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                // The chart lives in a ScrollView, so the drag has to be claimed or the page
                // scrolls away underneath the finger instead of scrubbing.
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = dp(4f)
                val right = width - dp(4f)
                val fraction = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                highlightIndex = indexAtFraction(fraction)
                onScrub?.invoke(highlightIndex)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // The mark stays after the finger lifts: it is there to be read, not held.
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val points = samples
        if (points.size < 2) {
            return
        }

        val left = dp(4f)
        val right = width - dp(4f)
        val top = dp(8f)
        val bottom = height - dp(30f)
        if (right <= left || bottom <= top) {
            return
        }
        val plot = Plot(left, right, top, bottom)

        // One point per pixel column at most. A four-hour ride holds ~14,000 samples and the chart
        // is a thousand pixels wide, so drawing every one of them spends fourteen line segments to
        // decide the colour of a single column - and stacks them into a smeared, jagged line.
        val stride = max(1, points.size / max(1, (right - left).toInt()))

        drawElevation(canvas, plot, stride)

        // Baseline and midline, so a reader has something to judge height against.
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, (top + bottom) / 2, right, (top + bottom) / 2, axisPaint)

        drawDistanceAxis(canvas, plot)

        var labelX = left
        series.forEach { entry ->
            if (entry.values.all { it == null }) {
                return@forEach
            }
            val minimum = entry.minimum
            val maximum = entry.maximum

            linePaint.color = entry.colour
            val path = Path()
            var started = false
            var index = 0
            while (index < points.size) {
                val value = entry.values[index]
                if (value == null) {
                    // A gap in the data is a gap in the line, not a straight line across it.
                    started = false
                } else {
                    val x = xFor(index, plot)
                    val y = bottom - ((value - minimum) / (maximum - minimum)).toFloat() * (bottom - top)
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                }
                // The last sample is always drawn, so the line reaches the end of the ride.
                index = nextIndex(index, stride, points.size)
            }
            canvas.drawPath(path, linePaint)

            labelPaint.color = entry.colour
            // Each series names its own range, so the shape means something without scrubbing it.
            val text = "${entry.label} ${minimum.toInt()}-${maximum.toInt()}"
            canvas.drawText(text, labelX, height - dp(16f), labelPaint)
            labelX += labelPaint.measureText(text) + dp(10f)
        }

        drawHighlight(canvas, series, points, plot)
    }

    /**
     * The sample at a fraction across the plot, by distance rather than by sample number.
     *
     * The inverse of [xFor], and it has to be: the chart places samples by how far along the road
     * they are, so scrubbing by sample number would put the marker somewhere else entirely wherever
     * the rider had been stationary - a five-minute wait at a level crossing occupies no width but
     * hundreds of samples.
     */
    private fun indexAtFraction(fraction: Float): Int {
        val total = cumulativeMetres.lastOrNull() ?: 0.0
        if (total <= 0) {
            return (fraction * (samples.size - 1)).toInt()
        }
        val target = total * fraction
        val found = cumulativeMetres.toTypedArray().binarySearch(target)
        // binarySearch returns the insertion point negated when there is no exact match.
        return (if (found >= 0) found else -found - 1).coerceIn(0, samples.size - 1)
    }

    /** Steps by the stride but never past the last sample, so every line reaches the ride's end. */
    private fun nextIndex(index: Int, stride: Int, size: Int): Int =
        if (index + stride >= size && index != size - 1) size - 1 else index + stride

    private fun xFor(index: Int, plot: Plot): Float {
        val total = cumulativeMetres.lastOrNull() ?: 0.0
        // Placed by distance covered rather than by sample number, so a chart read against the
        // elevation profile lines up with the road: time spent stationary would otherwise stretch
        // a junction into a wide band of nothing.
        val fraction = if (total > 0) (cumulativeMetres[index] / total).toFloat() else {
            index / (samples.size - 1).toFloat()
        }
        return plot.left + (plot.right - plot.left) * fraction
    }

    /** The profile, filled. Drawn first so every other line reads on top of the terrain. */
    private fun drawElevation(canvas: Canvas, plot: Plot, stride: Int) {
        if (altitudes.none { it != null }) {
            return
        }
        val path = Path()
        var started = false
        var lastX = plot.left
        var index = 0
        while (index < samples.size) {
            val altitude = altitudes[index]
            if (altitude != null) {
                val x = xFor(index, plot)
                val y = plot.bottom -
                    ((altitude - altitudeMin) / (altitudeMax - altitudeMin)).toFloat() * (plot.bottom - plot.top)
                if (started) path.lineTo(x, y) else { path.moveTo(x, plot.bottom); path.lineTo(x, y) }
                started = true
                lastX = x
            }
            index = nextIndex(index, stride, samples.size)
        }
        if (!started) {
            return
        }
        canvas.drawPath(Path(path).apply { lineTo(lastX, plot.bottom); close() }, elevationFillPaint)
        canvas.drawPath(path, elevationLinePaint)
    }

    /**
     * Distance ticks along the bottom.
     *
     * Without them the x axis is an unlabelled span and a reader cannot say where in the ride a
     * spike happened - which is most of what a chart is for.
     */
    private fun drawDistanceAxis(canvas: Canvas, plot: Plot) {
        val total = cumulativeMetres.lastOrNull() ?: return
        if (total <= 0) {
            return
        }
        labelPaint.color = ContextCompat.getColor(context, R.color.text_dark_subtitle)
        for (tick in 0..AXIS_TICKS) {
            val fraction = tick / AXIS_TICKS.toFloat()
            val x = plot.left + (plot.right - plot.left) * fraction
            val text = CyclingFormatter.distanceText(total * fraction)
            val textWidth = labelPaint.measureText(text)
            // The end labels are pulled inside the plot rather than hanging off it.
            val textX = (x - textWidth / 2).coerceIn(plot.left, plot.right - textWidth)
            canvas.drawText(text, textX, height - dp(4f), labelPaint)
            if (tick > 0 && tick < AXIS_TICKS) {
                canvas.drawLine(x, plot.bottom, x, plot.bottom + dp(3f), axisPaint)
            }
        }
    }

    /** The scrub line and the figures at that moment, so the mark is readable, not just placed. */
    private fun drawHighlight(canvas: Canvas, series: List<Series>, points: List<RideSample>, plot: Plot) {
        val index = highlightIndex?.coerceIn(0, points.size - 1) ?: return
        val x = xFor(index, plot)
        canvas.drawLine(x, plot.top, x, plot.bottom, highlightPaint)

        // Where you are comes first: a reading without a place on the road is half an answer.
        val place = listOfNotNull(
            cumulativeMetres.getOrNull(index)?.let { CyclingFormatter.distanceText(it) },
            altitudes.getOrNull(index)?.let { CyclingFormatter.ascentText(it) },
        ).joinToString(" · ")
        if (place.isNotEmpty()) {
            labelPaint.color = ContextCompat.getColor(context, R.color.text_dark_subtitle)
            canvas.drawText(place, plot.left, plot.top + dp(10f), labelPaint)
        }

        var readoutX = plot.left
        val readoutY = plot.top + dp(23f)
        series.forEach { entry ->
            val value = entry.values.getOrNull(index) ?: return@forEach
            // Speed is stored in metres per second; everything else is already in its own unit.
            val shown = if (entry.label == SPEED_LABEL) {
                CyclingFormatter.speedValue(value)
            } else {
                value.toInt().toString()
            }
            val text = "${entry.label} $shown"
            readoutPaint.color = entry.colour
            canvas.drawText(text, readoutX, readoutY, readoutPaint)
            readoutX += readoutPaint.measureText(text) + dp(10f)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val SPEED_LABEL = "Speed"

        /** Four gaps gives five labels, which fits a phone without the ends colliding. */
        const val AXIS_TICKS = 4

        /** A flat ride must not be magnified into a mountain range by GPS altitude noise. */
        const val MIN_ALTITUDE_SPAN_M = 20.0
        val HR_COLOUR = Color.rgb(0xE5, 0x39, 0x35)
        val SPEED_COLOUR = Color.rgb(0x21, 0x96, 0xF3)
        val CADENCE_COLOUR = Color.rgb(0x43, 0xA0, 0x47)
        val POWER_COLOUR = Color.rgb(0xFB, 0x8C, 0x00)
    }
}
