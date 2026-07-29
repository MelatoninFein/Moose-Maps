package app.organicmaps.cycling.ui

import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.organicmaps.R
import app.organicmaps.cycling.RideMode
import app.organicmaps.cycling.media.MediaControlHub
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Wires the music transport buttons that live in the map's control column.
 *
 * Kept out of [app.organicmaps.maplayer.MapButtonsController] so that class doesn't grow a
 * dependency on the media subsystem: it only has to call [attach] once, and everything else -
 * clicks, the play/pause icon following the actual playback state - is handled here.
 */
object MusicButtons {

    /**
     * Binds the buttons inside [frame], if it contains them. The navigation layout has no music
     * column, so every lookup is null-checked rather than assumed.
     */
    @JvmStatic
    fun attach(frame: View, lifecycleOwner: LifecycleOwner) {
        val previous: FloatingActionButton? = frame.findViewById(R.id.music_previous)
        val playPause: FloatingActionButton? = frame.findViewById(R.id.music_play_pause)
        val next: FloatingActionButton? = frame.findViewById(R.id.music_next)
        if (previous == null || playPause == null || next == null) {
            return
        }

        // Always shown, as asked for: transport works through media key events even with no
        // permission and no recognised player, so there is no state in which they are dead.
        frame.findViewById<View>(R.id.music_buttons_container)?.visibility = View.VISIBLE

        val media = MediaControlHub.from(frame.context)

        fun refreshIcon() {
            val playing = media.nowPlaying.value?.isPlaying == true || media.isMusicActive()
            playPause.setImageResource(if (playing) R.drawable.ic_cycling_pause else R.drawable.ic_cycling_play)
        }

        // Players take a moment to react to a key event, so re-read shortly after the press. This
        // is what keeps the icon honest without notification access, where no callback ever arrives.
        fun pressThenRefresh(action: () -> Unit) {
            action()
            playPause.postDelayed({ refreshIcon() }, ICON_SETTLE_MS)
        }

        /**
         * Says what is playing after a skip.
         *
         * Removing the media panel took the only place a track name appeared, so skipping was a
         * blind operation - you could change track but never learn what you had changed to. A brief
         * toast answers it without putting a permanent readout back on the map. Nothing is shown
         * without notification access, because then there is genuinely no title to show.
         */
        fun announceTrack() {
            playPause.postDelayed({
                val playing = media.nowPlaying.value ?: return@postDelayed
                val title = playing.title ?: return@postDelayed
                val text = listOfNotNull(title, playing.artist).joinToString(" — ")
                Toast.makeText(frame.context, text, Toast.LENGTH_SHORT).show()
            }, ICON_SETTLE_MS)
        }

        previous.setOnClickListener { pressThenRefresh { media.skipToPrevious() }; announceTrack() }
        next.setOnClickListener { pressThenRefresh { media.skipToNext() }; announceTrack() }
        playPause.setOnClickListener { pressThenRefresh { media.togglePlayPause() } }

        media.nowPlaying.observe(lifecycleOwner) { refreshIcon() }

        // Without notification access no callback ever arrives, so pausing from a headphone button
        // left the icon wrong until the rider touched it. Polling the audio state is the only
        // signal available there; it runs while the map is in front and stops with it.
        val poll = object : Runnable {
            override fun run() {
                refreshIcon()
                playPause.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        refreshIcon()
                        playPause.postDelayed(poll, POLL_INTERVAL_MS)
                    }
                    Lifecycle.Event.ON_PAUSE -> playPause.removeCallbacks(poll)
                    else -> Unit
                }
            },
        )
        refreshIcon()
    }

    /** Long enough for a player to actually start or stop before we re-read the audio state. */
    private const val ICON_SETTLE_MS = 400L

    /** Slow enough to be free, quick enough that a wrong icon is never wrong for long. */
    private const val POLL_INTERVAL_MS = 3_000L
}
