package app.organicmaps.cycling.ui

import android.view.View
import androidx.lifecycle.LifecycleOwner
import app.organicmaps.R
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

        previous.setOnClickListener { pressThenRefresh { media.skipToPrevious() } }
        next.setOnClickListener { pressThenRefresh { media.skipToNext() } }
        playPause.setOnClickListener { pressThenRefresh { media.togglePlayPause() } }

        media.nowPlaying.observe(lifecycleOwner) { refreshIcon() }
        refreshIcon()
    }

    /** Long enough for a player to actually start or stop before we re-read the audio state. */
    private const val ICON_SETTLE_MS = 400L
}
