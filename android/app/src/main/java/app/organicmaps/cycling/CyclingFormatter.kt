package app.organicmaps.cycling

import android.content.Context
import app.organicmaps.R
import app.organicmaps.cycling.sensors.SensorSnapshot
import app.organicmaps.sdk.settings.UnitLocale
import java.util.Locale

/**
 * Formats sensor readings for display. Speed follows the app's measurement-units setting; heart
 * rate, cadence and power have no imperial equivalent.
 */
object CyclingFormatter {

    private const val PLACEHOLDER = "--"

    private const val SECONDS_PER_HOUR = 3600.0
    private const val METRES_PER_KM = 1000.0
    private const val METRES_PER_MILE = 1609.344
    private const val FEET_PER_METRE = 3.28084

    fun speedValue(speedMps: Double?): String {
        if (speedMps == null) {
            return PLACEHOLDER
        }
        val converted = if (isImperial()) {
            speedMps * SECONDS_PER_HOUR / METRES_PER_MILE
        } else {
            speedMps * SECONDS_PER_HOUR / METRES_PER_KM
        }
        // One decimal below 10 - the difference between 8.2 and 8.7 km/h is meaningful when climbing,
        // but nobody needs a decimal at 40.
        return if (converted < 10.0) {
            String.format(Locale.getDefault(), "%.1f", converted)
        } else {
            converted.toInt().toString()
        }
    }

    fun speedUnit(context: Context): String =
        context.getString(if (isImperial()) R.string.cycling_unit_mph else R.string.cycling_unit_kmh)

    fun intValue(value: Int?): String = value?.toString() ?: PLACEHOLDER

    /** Single-line summary used in the foreground-service notification and the PiP window. */
    fun notificationSummary(context: Context, snapshot: SensorSnapshot?): String {
        if (snapshot == null || !snapshot.hasAnyReading) {
            return context.getString(R.string.cycling_sensors_waiting)
        }

        val parts = buildList {
            snapshot.heartRateBpm?.let { add("$it ${context.getString(R.string.cycling_unit_bpm)}") }
            snapshot.cadenceRpm?.let { add("$it ${context.getString(R.string.cycling_unit_rpm)}") }
            snapshot.speedMps?.let { add("${speedValue(it)} ${speedUnit(context)}") }
            snapshot.powerWatts?.let { add("$it ${context.getString(R.string.cycling_unit_watts)}") }
        }
        return parts.joinToString(" · ")
    }

    /**
     * Short distance for the quick-navigate list. Metres/feet close by, kilometres/miles beyond -
     * a rider scanning a list wants one glanceable number, not four significant figures.
     */
    /**
     * Climbing, always in metres or feet.
     *
     * This used to go through [distanceText], which turns anything over a kilometre into "1.2 km" -
     * so a good day in the hills read as a distance. Nobody anywhere quotes ascent that way; a
     * four-figure number of metres is the whole point of saying it.
     */
    fun ascentText(metres: Double): String = if (isImperial()) {
        "${(metres * FEET_PER_METRE).toInt()} ft"
    } else {
        "${metres.toInt()} m"
    }

    fun distanceText(metres: Double): String = if (isImperial()) {
        val miles = metres / METRES_PER_MILE
        if (miles < 0.2) {
            "${(metres * FEET_PER_METRE).toInt()} ft"
        } else {
            String.format(Locale.getDefault(), "%.1f mi", miles)
        }
    } else {
        if (metres < METRES_PER_KM) {
            "${metres.toInt()} m"
        } else {
            String.format(Locale.getDefault(), "%.1f km", metres / METRES_PER_KM)
        }
    }

    private fun isImperial(): Boolean = UnitLocale.getUnits() == UnitLocale.UNITS_FOOT
}
