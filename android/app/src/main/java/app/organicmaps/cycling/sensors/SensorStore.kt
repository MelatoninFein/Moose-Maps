package app.organicmaps.cycling.sensors

import android.content.Context
import android.content.SharedPreferences
import app.organicmaps.sdk.util.log.Logger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists the paired sensor list and the wheel circumference in a dedicated preferences file.
 *
 * Sensors are kept out of the C++ core config on purpose: they are a platform-level concern with no
 * counterpart on other platforms, and the list is small enough that JSON in SharedPreferences is
 * the simplest thing that works.
 */
class SensorStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var wheelCircumferenceMm: Int
        get() = prefs.getInt(KEY_WHEEL_CIRCUMFERENCE, DEFAULT_WHEEL_CIRCUMFERENCE_MM)
        set(value) {
            val clamped = value.coerceIn(MIN_CIRCUMFERENCE_MM, MAX_CIRCUMFERENCE_MM)
            prefs.edit().putInt(KEY_WHEEL_CIRCUMFERENCE, clamped).apply()
        }

    /** Whether sensors should be connected at all. Off by default so Bluetooth stays untouched. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether the rider has dismissed the explanation of what this fork adds.
     *
     * Nothing described sensors, rides or racing anywhere: the features were discoverable only by
     * opening every screen. A card that can be read once and dismissed says it without turning
     * first launch into a tour.
     */
    var hasSeenIntro: Boolean
        get() = prefs.getBoolean(KEY_SEEN_INTRO, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEN_INTRO, value).apply()

    /** Whether the live sensor readout is drawn over the map. */
    var isOverlayVisible: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    /**
     * Maximum heart rate, used to place readings into training zones. Defaults to a rough adult
     * figure; the zones mean little until the rider replaces it with their own.
     */
    var maxHeartRateBpm: Int
        get() = prefs.getInt(KEY_MAX_HR, DEFAULT_MAX_HEART_RATE_BPM)
        set(value) {
            val clamped = value.coerceIn(MIN_MAX_HEART_RATE_BPM, MAX_MAX_HEART_RATE_BPM)
            prefs.edit().putInt(KEY_MAX_HR, clamped).apply()
        }

    fun loadPairedSensors(): List<PairedSensor> {
        val raw = prefs.getString(KEY_SENSORS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toPairedSensor() }
        } catch (e: JSONException) {
            // A corrupt list must not brick the settings screen - start over with an empty one.
            Logger.e(TAG, "Failed to read paired sensors, resetting", e)
            prefs.edit().remove(KEY_SENSORS).apply()
            emptyList()
        }
    }

    fun savePairedSensors(sensors: List<PairedSensor>) {
        val array = JSONArray()
        sensors.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SENSORS, array.toString()).apply()
    }

    private fun PairedSensor.toJson() = JSONObject().apply {
        put(FIELD_ADDRESS, address)
        put(FIELD_NAME, name)
        put(FIELD_KINDS, JSONArray(kinds.map { it.name }))
    }

    private fun JSONObject.toPairedSensor(): PairedSensor? {
        val address = optString(FIELD_ADDRESS).takeIf { it.isNotEmpty() } ?: return null
        val kindsArray = optJSONArray(FIELD_KINDS)
        val kinds = buildSet {
            for (index in 0 until (kindsArray?.length() ?: 0)) {
                val name = kindsArray?.optString(index) ?: continue
                // Unknown names are dropped rather than fatal: a downgrade must not lose the pairing.
                SensorKind.entries.firstOrNull { it.name == name }?.let { add(it) }
            }
        }
        return PairedSensor(address, optString(FIELD_NAME), kinds)
    }

    companion object {
        private const val TAG = "SensorStore"

        private const val PREFS_NAME = "CyclingSensors"
        private const val KEY_SENSORS = "paired_sensors"
        private const val KEY_WHEEL_CIRCUMFERENCE = "wheel_circumference_mm"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OVERLAY = "overlay_visible"
        private const val KEY_SEEN_INTRO = "seen_intro"
        private const val KEY_MAX_HR = "max_heart_rate_bpm"

        private const val FIELD_ADDRESS = "address"
        private const val FIELD_NAME = "name"
        private const val FIELD_KINDS = "kinds"

        /** 700x25c, the most common road wheel. */
        const val DEFAULT_WHEEL_CIRCUMFERENCE_MM = 2105

        const val MIN_CIRCUMFERENCE_MM = 800
        const val MAX_CIRCUMFERENCE_MM = 2500

        const val DEFAULT_MAX_HEART_RATE_BPM = 190
        const val MIN_MAX_HEART_RATE_BPM = 120
        const val MAX_MAX_HEART_RATE_BPM = 230
    }
}
