package app.organicmaps.cycling.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.organicmaps.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A compass dial with the current speed in the middle and a dot on the rim for each waypoint.
 *
 * Everything is drawn rather than composed from child views: the dial rotates on every compass
 * update, which on a phone is several times a second, and re-laying-out a view hierarchy at that
 * rate is wasteful when the whole thing is a few arcs and circles.
 *
 * Bearings are true bearings in degrees; the view subtracts the device heading itself, so callers
 * never have to think about screen orientation.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** A place to mark on the rim. [bearingDegrees] is a true bearing from the current position. */
    data class Waypoint(val bearingDegrees: Double, val isFavourite: Boolean)

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val favouritePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Device heading in degrees, 0 = true north. */
    var headingDegrees: Double = 0.0
        set(value) {
            // Redrawing for sub-degree jitter is wasted work; the dial can't show it anyway.
            if (kotlin.math.abs(value - field) >= HEADING_EPSILON) {
                field = value
                invalidate()
            }
        }

    var speedText: String = "--"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var speedUnit: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var waypoints: List<Waypoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    init {
        val accent = ContextCompat.getColor(context, R.color.base_accent)
        backgroundPaint.color = ContextCompat.getColor(context, R.color.bg_compass)
        dialPaint.color = ContextCompat.getColor(context, R.color.fg_compass)
        speedPaint.color = ContextCompat.getColor(context, R.color.fg_compass)
        unitPaint.color = ContextCompat.getColor(context, R.color.fg_compass)
        northPaint.color = ContextCompat.getColor(context, R.color.base_red)
        waypointPaint.color = accent
        favouritePaint.color = ContextCompat.getColor(context, R.color.base_yellow)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always square: a compass that isn't round reads as broken.
        val size = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(2f)

        canvas.drawCircle(cx, cy, radius, backgroundPaint)
        canvas.drawCircle(cx, cy, radius, dialPaint)

        // North marker: a wedge on the rim that rotates opposite the heading, so it keeps pointing
        // at true north as the rider turns.
        val northAngle = -headingDegrees
        val markerRadius = radius - dp(6f)
        drawWedge(canvas, cx, cy, markerRadius, northAngle, northPaint)

        waypoints.forEach { waypoint ->
            val angle = waypoint.bearingDegrees - headingDegrees
            val paint = if (waypoint.isFavourite) favouritePaint else waypointPaint
            val point = pointOnCircle(cx, cy, radius - dp(10f), angle)
            canvas.drawCircle(point.first, point.second, dp(3f), paint)
        }

        speedPaint.textSize = radius * 0.62f
        unitPaint.textSize = radius * 0.26f
        // Centre the number on the dial rather than on the text baseline.
        val baseline = cy - (speedPaint.descent() + speedPaint.ascent()) / 2f - radius * 0.10f
        canvas.drawText(speedText, cx, baseline, speedPaint)
        canvas.drawText(speedUnit, cx, baseline + radius * 0.34f, unitPaint)
    }

    private fun drawWedge(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Double, paint: Paint) {
        val tip = pointOnCircle(cx, cy, r, angleDeg)
        val left = pointOnCircle(cx, cy, r - dp(7f), angleDeg - WEDGE_HALF_WIDTH_DEG)
        val right = pointOnCircle(cx, cy, r - dp(7f), angleDeg + WEDGE_HALF_WIDTH_DEG)
        val path = android.graphics.Path().apply {
            moveTo(tip.first, tip.second)
            lineTo(left.first, left.second)
            lineTo(right.first, right.second)
            close()
        }
        canvas.drawPath(path, paint)
    }

    /** Screen coordinates for an angle measured clockwise from straight up. */
    private fun pointOnCircle(cx: Float, cy: Float, r: Float, angleDeg: Double): Pair<Float, Float> {
        val radians = Math.toRadians(angleDeg - 90.0)
        return (cx + r * cos(radians)).toFloat() to (cy + r * sin(radians)).toFloat()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val HEADING_EPSILON = 1.0
        const val WEDGE_HALF_WIDTH_DEG = 9.0
    }
}
