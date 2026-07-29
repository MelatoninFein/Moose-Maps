package app.organicmaps.cycling.rides

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a recorded ride as a route trace, coloured by a chosen metric with arrows whose density
 * follows how fast you were going.
 *
 * This is drawn on its own canvas rather than onto the live map. Colouring the map's own track
 * rendering would mean changing the C++ drape engine; a self-contained view gets the same
 * information across without touching the renderer, and it is the ride-review screen - not the
 * navigation screen - where anyone actually studies this.
 */
class RideTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** What the line colour represents. */
    enum class Metric { SPEED, HEART_RATE }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * White fill with a dark ring, so the marker stays visible over any colour the trace takes -
     * a single-colour dot disappears into whichever part of the gradient it lands on.
     */
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws a bare course, for a saved segment rather than a ridden one.
     *
     * A segment stores only where it goes, so there is nothing to grade it by; the renderer already
     * draws an unmeasured stretch grey, which is the honest look for a course nobody has ridden yet.
     */
    var coursePoints: List<SegmentPoint> = emptyList()
        set(value) {
            field = value
            samples = value.map { point ->
                RideSample(
                    timestampMs = 0,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitudeMetres = null,
                    gpsSpeedMps = null,
                    sensorSpeedMps = null,
                    heartRateBpm = null,
                    cadenceRpm = null,
                    powerWatts = null,
                )
            }
        }

    /** The sample marked on the route, driven by scrubbing the chart. */
    var highlightIndex: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    var metric: Metric = Metric.SPEED
        set(value) {
            field = value
            range = metricRange(samples)
            invalidate()
        }

    var samples: List<RideSample> = emptyList()
        set(value) {
            field = value
            // Bounds depend only on the samples, so they are found once here rather than on every
            // draw. Four min/max passes over a 14,000-point ride, per frame, is a lot of work to
            // repeat for an answer that cannot have changed.
            bounds = if (value.size < 2) {
                null
            } else {
                Bounds(
                    minLat = value.minOf { it.latitude },
                    maxLat = value.maxOf { it.latitude },
                    minLon = value.minOf { it.longitude },
                    maxLon = value.maxOf { it.longitude },
                )
            }
            range = metricRange(value)
            invalidate()
        }

    private class Bounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    private var bounds: Bounds? = null

    /** Cached alongside the bounds; it too depends only on the samples and the chosen metric. */
    private var range: Pair<Double, Double> = 0.0 to 1.0

    override fun onDraw(canvas: Canvas) {
        val points = samples
        if (points.size < 2) {
            return
        }

        val extent = bounds ?: return
        val padding = dp(12f)
        val usableWidth = width - padding * 2
        val usableHeight = height - padding * 2
        if (usableWidth <= 0 || usableHeight <= 0) {
            return
        }

        val minLat = extent.minLat
        val maxLat = extent.maxLat
        // Longitude degrees shrink toward the poles; without this correction a north-south ride
        // looks stretched sideways.
        val squeeze = cos(Math.toRadians((minLat + maxLat) / 2))
        val latSpan = max(maxLat - minLat, 1e-6)
        val lonSpan = max((extent.maxLon - extent.minLon) * squeeze, 1e-6)
        // One scale for both axes keeps the route's actual shape rather than distorting it to fill.
        val scale = min(usableWidth / lonSpan, usableHeight / latSpan)
        val offsetX = padding + (usableWidth - lonSpan * scale) / 2
        val offsetY = padding + (usableHeight - latSpan * scale) / 2

        fun projectX(lon: Double) = (offsetX + (lon - extent.minLon) * squeeze * scale).toFloat()
        // Screen y grows downward, latitude grows northward, so this is inverted.
        fun projectY(lat: Double) = (offsetY + (maxLat - lat) * scale).toFloat()

        linePaint.strokeWidth = dp(4f)

        drawRoute(canvas, points, ::projectX, ::projectY)

        if (coursePoints.isEmpty()) {
            drawEnds(
                canvas,
                projectX(points.first().longitude), projectY(points.first().latitude),
                projectX(points.last().longitude), projectY(points.last().latitude),
            )
        }

        // The moment being inspected on the chart below, marked on the road where it happened.
        highlightIndex?.coerceIn(0, points.size - 1)?.let { index ->
            val sample = points[index]
            val x = projectX(sample.longitude)
            val y = projectY(sample.latitude)
            markerPaint.style = Paint.Style.FILL
            markerPaint.color = android.graphics.Color.WHITE
            canvas.drawCircle(x, y, dp(6f), markerPaint)
            markerPaint.style = Paint.Style.STROKE
            markerPaint.strokeWidth = dp(2f)
            markerPaint.color = android.graphics.Color.BLACK
            canvas.drawCircle(x, y, dp(6f), markerPaint)
        }
    }

    /** The coloured line itself, with arrows spaced by how fast that stretch was ridden. */
    private fun drawRoute(
        canvas: Canvas,
        points: List<RideSample>,
        projectX: (Double) -> Float,
        projectY: (Double) -> Float,
    ) {
        var distanceSinceArrow = 0.0
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]

            val x1 = projectX(previous.longitude)
            val y1 = projectY(previous.latitude)
            val x2 = projectX(current.longitude)
            val y2 = projectY(current.latitude)

            linePaint.color = colourFor(metricValue(current), range)
            canvas.drawLine(x1, y1, x2, y2, linePaint)

            // Arrow spacing shortens as speed rises, so a fast section visibly bristles with them
            // while a slow climb is nearly bare.
            val speed = current.gpsSpeedMps ?: current.sensorSpeedMps ?: 0.0
            val segmentLength = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
            distanceSinceArrow += segmentLength
            if (distanceSinceArrow >= arrowSpacingPx(speed) && segmentLength > 0.5) {
                distanceSinceArrow = 0.0
                arrowPaint.color = linePaint.color
                drawArrow(canvas, x1, y1, x2, y2)
            }
        }
    }

    /**
     * Where it began and where it ended.
     *
     * A closed loop is otherwise a shape with no direction: the arrows say which way round it was
     * ridden, but nothing said which end was the start.
     */
    private fun drawEnds(canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float) {
        markerPaint.style = Paint.Style.FILL
        val radius = dp(5f)
        markerPaint.color = START_COLOUR
        canvas.drawCircle(startX, startY, radius, markerPaint)
        markerPaint.color = FINISH_COLOUR
        canvas.drawCircle(endX, endY, radius, markerPaint)
    }

    private fun metricValue(sample: RideSample): Double? = when (metric) {
        Metric.SPEED -> sample.gpsSpeedMps ?: sample.sensorSpeedMps
        Metric.HEART_RATE -> sample.heartRateBpm?.toDouble()
    }

    /**
     * The observed range of the metric across this ride, so the gradient always uses its full span.
     * Fixed bounds would render a gentle ride entirely in one colour.
     */
    private fun metricRange(points: List<RideSample>): Pair<Double, Double> {
        val values = points.mapNotNull { metricValue(it) }
        if (values.isEmpty()) {
            return 0.0 to 1.0
        }
        val low = values.min()
        val high = values.max()
        return if (high - low < 1e-6) low to low + 1.0 else low to high
    }

    /** Blue (low) through green to red (high) - the convention every cycling app uses. */
    private fun colourFor(value: Double?, range: Pair<Double, Double>): Int {
        if (value == null) {
            return Color.GRAY
        }
        val t = ((value - range.first) / (range.second - range.first)).coerceIn(0.0, 1.0)
        return if (t < 0.5) {
            val k = (t / 0.5).toFloat()
            Color.rgb((0x21 + (0x4C - 0x21) * k).toInt(), (0x96 + (0xAF - 0x96) * k).toInt(), (0xF3 - 0x3A * k).toInt())
        } else {
            val k = ((t - 0.5) / 0.5).toFloat()
            Color.rgb((0x4C + (0xF4 - 0x4C) * k).toInt(), (0xAF - (0xAF - 0x43) * k).toInt(), (0x50 - 0x14 * k).toInt())
        }
    }

    /** Faster riding gets arrows closer together, clamped so they never merge into a smear. */
    private fun arrowSpacingPx(speedMps: Double): Double {
        val fast = (speedMps / MAX_EXPECTED_SPEED_MPS).coerceIn(0.0, 1.0)
        return dp(48f) - dp(32f) * fast.toDouble()
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val size = dp(5f)
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(
                (x2 - size * cos(angle - ARROW_SPREAD)).toFloat(),
                (y2 - size * sin(angle - ARROW_SPREAD)).toFloat(),
            )
            lineTo(
                (x2 - size * cos(angle + ARROW_SPREAD)).toFloat(),
                (y2 - size * sin(angle + ARROW_SPREAD)).toFloat(),
            )
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        /** ~54 km/h: beyond this everything is simply "fast" as far as arrow density goes. */
        const val MAX_EXPECTED_SPEED_MPS = 15.0
        const val ARROW_SPREAD = 0.5

        /** Green away, red home - saturated enough to hold against any part of the speed gradient. */
        val START_COLOUR = Color.rgb(0x2E, 0x7D, 0x32)
        val FINISH_COLOUR = Color.rgb(0xC6, 0x28, 0x28)
    }
}
