package app.organicmaps.cycling.rides

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Time in heart-rate zones as one proportional bar.
 *
 * Five rows of "Z3  133-152 bpm  18m 42s" is something you read; a single bar is something you
 * take in. The shape of a session - mostly blue, or mostly red - is the whole point, and it is
 * legible before any of the numbers are.
 *
 * Cool to warm, easy zones through hard, which is the convention every training tool uses.
 */
class ZoneBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val rect = RectF()

    /** Milliseconds per zone. Zones with no time are skipped rather than drawn as slivers. */
    var timeInZones: Map<HeartRateZone, Long> = emptyMap()
        set(value) {
            field = value
            visibility = if (value.values.sum() > 0) VISIBLE else GONE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val total = timeInZones.values.sum()
        if (total <= 0) {
            return
        }

        val radius = dp(4f)
        labelPaint.textSize = height * 0.42f

        var x = 0f
        HeartRateZone.entries.forEach { zone ->
            val millis = timeInZones[zone] ?: 0L
            if (millis <= 0) {
                return@forEach
            }
            val width = (millis.toDouble() / total * this.width).toFloat()
            paint.color = colourFor(zone)
            rect.set(x, 0f, x + width, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)

            // Only label a band wide enough to hold the text, otherwise it overflows its own colour.
            val label = zone.name
            if (width > labelPaint.measureText(label) * 1.8f) {
                val baseline = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(label, x + width / 2f, baseline, labelPaint)
            }
            x += width
        }
    }

    private fun colourFor(zone: HeartRateZone): Int = when (zone) {
        HeartRateZone.Z1 -> Color.rgb(0x42, 0xA5, 0xF5)
        HeartRateZone.Z2 -> Color.rgb(0x26, 0xA6, 0x9A)
        HeartRateZone.Z3 -> Color.rgb(0x9C, 0xCC, 0x65)
        HeartRateZone.Z4 -> Color.rgb(0xFF, 0xA7, 0x26)
        HeartRateZone.Z5 -> Color.rgb(0xE5, 0x39, 0x35)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

/**
 * A segment's times drawn as a tiny trend line.
 *
 * "Am I getting faster" is the question a segment exists to answer, and a column of times makes
 * you work it out. A sparkline answers it before you read anything: sloping down is progress.
 *
 * Plotted oldest to newest with the y axis inverted, so faster is higher.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(0x42, 0xA5, 0xF5)
    }
    private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x2E, 0x7D, 0x32)
    }

    /** Elapsed times, newest first, as the history store returns them. */
    var times: List<Long> = emptyList()
        set(value) {
            field = value
            // One point is not a trend; two is the minimum that says anything.
            visibility = if (value.size >= 2) VISIBLE else GONE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val ordered = times.reversed()
        if (ordered.size < 2) {
            return
        }
        val fastest = ordered.min()
        val slowest = ordered.max()
        val span = (slowest - fastest).coerceAtLeast(1L)

        val padding = dp(3f)
        val usableWidth = width - padding * 2
        val usableHeight = height - padding * 2

        var previousX = 0f
        var previousY = 0f
        ordered.forEachIndexed { index, millis ->
            val x = padding + usableWidth * index / (ordered.size - 1).toFloat()
            // Inverted: a quicker time sits higher, so an improving trend rises.
            val y = padding + usableHeight * ((millis - fastest).toFloat() / span)
            if (index > 0) {
                canvas.drawLine(previousX, previousY, x, y, linePaint)
            }
            if (millis == fastest) {
                canvas.drawCircle(x, y, dp(2.5f), bestPaint)
            }
            previousX = x
            previousY = y
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
