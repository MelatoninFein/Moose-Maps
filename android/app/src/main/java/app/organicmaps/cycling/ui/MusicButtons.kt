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
        previous.setOnClickListener { media.skipToPrevious() }
        next.setOnClickListener { media.skipToNext() }
        playPause.setOnClickListener { media.togglePlayPause() }

        media.nowPlaying.observe(lifecycleOwner) { nowPlaying ->
            playPause.setImageResource(
                if (nowPlaying.isPlaying) R.drawable.ic_cycling_pause else R.drawable.ic_cycling_play,
            )
        }
    }
}
