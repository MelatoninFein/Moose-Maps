package app.organicmaps.cycling.rides

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * The gap to your best, as a bar growing from the centre.
 *
 * Every racing game draws this and they all draw it the same way, because reading "+4.2" takes
 * focus a rider does not have at 35 km/h while a bar that has moved left or right does not. Green
 * to the left is ahead, red to the right is behind, and the eye catches which side is filled long
 * before it could read a number.
 *
 * The scale is deliberately small - a few seconds spans the whole bar - because the interesting
 * question is never "am I two minutes down", it is "am I gaining or losing right now".
 */
class DeltaBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(0x55, 0x00, 0x00, 0x00)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val rect = RectF()

    /** Negative is ahead of your best, positive behind. Null hides the bar entirely. */
    var deltaMillis: Long? = null
        set(value) {
            field = value
            visibility = if (value == null) GONE else VISIBLE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val delta = deltaMillis ?: return
        val centre = width / 2f
        val radius = height / 2f

        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Clamped, so a rider who is a minute down still sees a full bar rather than an overflow.
        val extent = (abs(delta).toFloat() / FULL_SCALE_MS).coerceIn(0f, 1f) * centre
        if (delta <= 0) {
            fillPaint.color = AHEAD
            rect.set(centre - extent, 0f, centre, height.toFloat())
        } else {
            fillPaint.color = BEHIND
            rect.set(centre, 0f, centre + extent, height.toFloat())
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // The zero line. Without it the bar has no anchor and a small gap reads as no gap.
        val tick = dp(1f)
        canvas.drawRect(centre - tick, 0f, centre + tick, height.toFloat(), centrePaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        /** Five seconds fills the bar. Beyond that the number matters more than the picture. */
        const val FULL_SCALE_MS = 5_000f
        val AHEAD = Color.rgb(0x2E, 0xC4, 0x66)
        val BEHIND = Color.rgb(0xE5, 0x39, 0x35)
    }
}

/**
 * Three sector lights, as a timing screen carries.
 *
 * Unlit until you cross the line that ends each sector, then purple, green or yellow. It answers
 * "where did the lap go" without a single number: two greens and a yellow says the descent cost
 * you, and it says it at the top of the descent rather than at the finish.
 */
class SectorLightsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    var grades: List<SectorGrade> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** The sector being ridden, drawn brighter than the ones not yet reached. */
    var currentSector: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val gap = dp(3f)
        val cellWidth = (width - gap * (Sectors.COUNT - 1)) / Sectors.COUNT
        val radius = dp(2f)

        for (sector in 0 until Sectors.COUNT) {
            paint.color = when {
                sector < grades.size -> colourFor(grades[sector])
                sector == currentSector -> PENDING
                else -> UNREACHED
            }
            val left = sector * (cellWidth + gap)
            rect.set(left, 0f, left + cellWidth, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun colourFor(grade: SectorGrade): Int = when (grade) {
        SectorGrade.RECORD -> RECORD
        SectorGrade.IMPROVED -> IMPROVED
        SectorGrade.SLOWER -> SLOWER
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        /** The colours every motorsport timing screen uses, so they need no explaining. */
        val RECORD = Color.rgb(0x9C, 0x27, 0xB0)
        val IMPROVED = Color.rgb(0x2E, 0xC4, 0x66)
        val SLOWER = Color.rgb(0xFF, 0xC1, 0x07)
        val PENDING = Color.argb(0x99, 0xFF, 0xFF, 0xFF)
        val UNREACHED = Color.argb(0x44, 0xFF, 0xFF, 0xFF)
    }
}
