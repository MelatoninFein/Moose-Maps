package app.organicmaps.cycling.ui

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import app.organicmaps.R

/**
 * One metric readout: a big number over a small unit label, the layout every bike computer uses.
 *
 * The tile hides itself when the value is null, so a rider with only a heart-rate strap doesn't get
 * three columns of dashes taking up map.
 */
class SensorTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val valueView: TextView
    private val labelView: TextView

    /** Set to null to hide the tile entirely. */
    var value: String? = null
        set(newValue) {
            field = newValue
            valueView.text = newValue.orEmpty()
            visibility = if (newValue == null) View.GONE else View.VISIBLE
        }

    var label: CharSequence?
        get() = labelView.text
        set(newLabel) {
            labelView.text = newLabel
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        LayoutInflater.from(context).inflate(R.layout.cycling_sensor_tile, this, true)
        valueView = findViewById(R.id.tile_value)
        labelView = findViewById(R.id.tile_label)

        context.obtainStyledAttributes(attrs, R.styleable.SensorTileView).use { typed ->
            labelView.text = typed.getString(R.styleable.SensorTileView_tileLabel).orEmpty()
            if (typed.getBoolean(R.styleable.SensorTileView_tileCompact, false)) {
                // Compact tiles only appear in the picture-in-picture window, which always draws on
                // a dark scrim - hence the Light (inverse) text appearances regardless of theme.
                // TextView.setTextAppearance(int) is API 23+; the compat helper covers minSdk 21.
                TextViewCompat.setTextAppearance(valueView, R.style.MwmTextAppearance_Body2_Light)
                TextViewCompat.setTextAppearance(labelView, R.style.MwmTextAppearance_Body4_Light)
            }
        }

        // Start hidden: nothing has been measured yet.
        visibility = View.GONE
    }
}

// Local equivalent of the core-ktx helper; the app depends on androidx.core, not core-ktx.
private inline fun <T> TypedArray.use(block: (TypedArray) -> T): T = try {
    block(this)
} finally {
    recycle()
}
