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

    /** One drawable series, already reduced to plain numbers. */
    private data class Series(val label: String, val colour: Int, val values: List<Double?>)

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

    var samples: List<RideSample> = emptyList()
        set(value) {
            field = value.sortedBy { it.timestampMs }
            invalidate()
        }

    /** Which series to draw. Toggled from the ride screen so the chart doesn't become soup. */
    var showHeartRate = true
        set(value) { field = value; invalidate() }
    var showSpeed = true
        set(value) { field = value; invalidate() }
    var showCadence = false
        set(value) { field = value; invalidate() }
    var showPower = false
        set(value) { field = value; invalidate() }

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
                highlightIndex = (fraction * (samples.size - 1)).toInt()
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
        val bottom = height - dp(18f)
        if (right <= left || bottom <= top) {
            return
        }

        // Baseline and midline, so a reader has something to judge height against.
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, (top + bottom) / 2, right, (top + bottom) / 2, axisPaint)

        val series = buildList {
            if (showHeartRate) add(Series("HR", HR_COLOUR, points.map { it.heartRateBpm?.toDouble() }))
            if (showSpeed) add(Series(SPEED_LABEL, SPEED_COLOUR, points.map { it.gpsSpeedMps ?: it.sensorSpeedMps }))
            if (showCadence) add(Series("Cad", CADENCE_COLOUR, points.map { it.cadenceRpm?.toDouble() }))
            if (showPower) add(Series("Pwr", POWER_COLOUR, points.map { it.powerWatts?.toDouble() }))
        }

        var labelX = left
        series.forEach { entry ->
            // Each series is scaled to its own range: heart rate and speed share no units, and a
            // common axis would flatten one of them into the baseline.
            val present = entry.values.filterNotNull()
            if (present.isEmpty()) {
                return@forEach
            }
            val minimum = present.min()
            val maximum = max(present.max(), minimum + 1e-6)

            linePaint.color = entry.colour
            val path = Path()
            var started = false
            entry.values.forEachIndexed { index, value ->
                if (value == null) {
                    // A gap in the data is a gap in the line, not a straight line across it.
                    started = false
                    return@forEachIndexed
                }
                val x = left + (right - left) * index / (points.size - 1).toFloat()
                val y = bottom - ((value - minimum) / (maximum - minimum)).toFloat() * (bottom - top)
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
            canvas.drawPath(path, linePaint)

            labelPaint.color = entry.colour
            canvas.drawText(entry.label, labelX, height - dp(4f), labelPaint)
            labelX += labelPaint.measureText(entry.label) + dp(12f)
        }

        drawHighlight(canvas, series, points, Plot(left, right, top, bottom))
    }

    /** The scrub line and the figures at that moment, so the mark is readable, not just placed. */
    private fun drawHighlight(canvas: Canvas, series: List<Series>, points: List<RideSample>, plot: Plot) {
        val index = highlightIndex?.coerceIn(0, points.size - 1) ?: return
        val x = plot.left + (plot.right - plot.left) * index / (points.size - 1).toFloat()
        canvas.drawLine(x, plot.top, x, plot.bottom, highlightPaint)

        var readoutX = plot.left
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
            canvas.drawText(text, readoutX, plot.top + dp(10f), readoutPaint)
            readoutX += readoutPaint.measureText(text) + dp(10f)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val SPEED_LABEL = "Speed"
        val HR_COLOUR = Color.rgb(0xE5, 0x39, 0x35)
        val SPEED_COLOUR = Color.rgb(0x21, 0x96, 0xF3)
        val CADENCE_COLOUR = Color.rgb(0x43, 0xA0, 0x47)
        val POWER_COLOUR = Color.rgb(0xFB, 0x8C, 0x00)
    }
}
