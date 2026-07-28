package app.organicmaps.cycling.rides

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import app.organicmaps.sdk.util.log.Logger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Speaks segment progress, because reading a number at 35 km/h is not realistic.
 *
 * This is what makes live racing usable rather than something you review afterwards: the delta only
 * helps if you learn it while you can still do something about it.
 *
 * Announcements are deliberately sparse. A voice every second is unbearable on a long segment, so
 * the delta is spoken on a fixed interval and otherwise only at the moments that matter - crossing
 * the line, and finishing.
 */
class SegmentVoice(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false

    private var lastSpokenMs = 0L
    private var announcedApproach = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Logger.w(TAG, "Text to speech unavailable; segment cues will be silent")
                return@TextToSpeech
            }
            engine?.setAudioAttributes(
                AudioAttributes.Builder()
                    // Announced over music rather than pausing it - a four-second update is not
                    // worth interrupting a track for.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine?.language = Locale.getDefault()
        }
    }

    /** "Segment ahead" as the start gate approaches, so a rider can wind up rather than be surprised. */
    fun announceApproach(segmentName: String, metresAway: Int) {
        if (announcedApproach) {
            return
        }
        announcedApproach = true
        speak("$segmentName in $metresAway metres")
    }

    fun onSegmentStarted(segmentName: String) {
        announcedApproach = false
        lastSpokenMs = 0L
        speak("$segmentName started")
    }

    /** Speaks the gap to your best, at most once every [INTERVAL_MS]. */
    fun announceDelta(deltaMillis: Long?, nowMs: Long) {
        if (deltaMillis == null || nowMs - lastSpokenMs < INTERVAL_MS) {
            return
        }
        lastSpokenMs = nowMs
        val seconds = (abs(deltaMillis) / 1000.0).roundToInt()
        // Within a second of your best is "level" - announcing "zero seconds up" is noise.
        val phrase = when {
            seconds == 0 -> "level"
            deltaMillis < 0 -> "$seconds seconds up"
            else -> "$seconds seconds down"
        }
        speak(phrase)
    }

    fun onSegmentFinished(segmentName: String, elapsedMillis: Long, wasPersonalBest: Boolean) {
        announcedApproach = false
        val totalSeconds = elapsedMillis / 1000
        val time = "${totalSeconds / 60} minutes ${totalSeconds % 60} seconds"
        speak(if (wasPersonalBest) "$segmentName, new best, $time" else "$segmentName finished, $time")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun speak(text: String) {
        if (!ready) {
            return
        }
        // Flush rather than queue: a stale delta is worse than none, and they arrive faster than
        // they can be spoken on a twisty segment.
        engine?.speak(text, QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private companion object {
        const val TAG = "SegmentVoice"
        const val UTTERANCE_ID = "cycling-segment"

        /** Often enough to race against, rare enough not to become noise. */
        const val INTERVAL_MS = 20_000L
    }
}
