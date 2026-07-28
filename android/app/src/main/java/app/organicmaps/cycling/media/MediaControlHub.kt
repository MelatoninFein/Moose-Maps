package app.organicmaps.cycling.media

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.organicmaps.sdk.util.log.Logger

/**
 * Transport controls and now-playing metadata for whatever music app the rider is using.
 *
 * This deliberately does not integrate against Spotify's or TIDAL's own SDKs. Those need per-app
 * API keys, an account login inside this app, and network access - all of which would be at odds
 * with a privacy-focused offline map, and neither would work for the third player somebody else
 * prefers. The platform media session is the common denominator: every compliant Android player
 * publishes one, so a single implementation controls all of them.
 *
 * There are two levels of capability, and the UI degrades between them:
 *
 *  - **With notification-listener access** ([MediaNotificationListener]): full metadata - track,
 *    artist, artwork, play state - and targeted commands to a specific session.
 *  - **Without it**: no metadata at all, but play/pause/skip still work, because media *key* events
 *    can be dispatched to whichever app currently holds audio focus without any permission.
 *
 * The rider is never forced to grant notification access just to skip a track.
 */
class MediaControlHub private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val sessionManager: MediaSessionManager? =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _nowPlaying = MutableLiveData(NowPlaying.NOTHING)
    val nowPlaying: LiveData<NowPlaying> = _nowPlaying

    private var activeController: MediaController? = null
    private var listening = false

    /** True when full metadata is available; false when only blind key events can be sent. */
    val hasMetadataAccess: Boolean
        get() = MediaNotificationListener.isEnabled(appContext)

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> selectController(controllers.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadata?) = publishState()

        override fun onPlaybackStateChanged(state: PlaybackState?) = publishState()

        override fun onSessionDestroyed() {
            activeController = null
            refreshSessions()
        }
    }

    /** Starts tracking media sessions. No-op when notification access has not been granted. */
    @MainThread
    fun start() {
        if (listening || !hasMetadataAccess) {
            return
        }
        val manager = sessionManager ?: return
        val component = MediaNotificationListener.componentName(appContext)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component, handler)
            selectController(manager.getActiveSessions(component))
            listening = true
        } catch (e: SecurityException) {
            // The setting can be toggled off between the check and the call.
            Logger.w(TAG, "Media session access denied: ${e.message}")
        }
    }

    @MainThread
    fun stop() {
        if (!listening) {
            return
        }
        listening = false
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        _nowPlaying.value = NowPlaying.NOTHING
    }

    /** Re-reads sessions after the user grants access without leaving the app. */
    @MainThread
    fun refreshSessions() {
        if (listening) {
            stop()
        }
        start()
    }

    /**
     * Whether audio is playing, without needing any permission.
     *
     * The media-session metadata is only readable with notification access; this is the fallback
     * that lets the play/pause icon show the truth for everyone else.
     */
    fun isMusicActive(): Boolean = audioManager?.isMusicActive == true

    fun togglePlayPause() {
        val controller = activeController
        if (controller == null) {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipToNext() {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToNext()
        } else {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun skipToPrevious() {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToPrevious()
        } else {
            dispatchKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    /**
     * Blind fallback used when no session is visible. A media key event goes to whichever app holds
     * audio focus, so it works with no permission at all - but nothing comes back, which is why
     * there is no metadata in this mode.
     *
     * Both the down and up events are required; players ignore a lone key-down.
     */
    private fun dispatchKey(keyCode: Int) {
        val manager = audioManager ?: return
        val eventTime = SystemClock.uptimeMillis()
        manager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        manager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /**
     * Picks the session to control: whichever player is actually producing sound, else the most
     * recently active one.
     *
     * No app is preferred over any other. `getActiveSessions` already returns controllers in
     * descending priority - most recently active first - so taking the first match defers to the
     * platform's own notion of "what the user is listening to". An earlier version ranked Spotify
     * and TIDAL above everything else, which meant a stale Spotify session could win over the
     * Qobuz or YouTube Music track actually playing.
     */
    private fun selectController(controllers: List<MediaController>) {
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()

        if (chosen?.sessionToken == activeController?.sessionToken) {
            publishState()
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = chosen
        chosen?.registerCallback(controllerCallback, handler)
        publishState()
    }

    private fun publishState() {
        val controller = activeController
        if (controller == null) {
            _nowPlaying.value = NowPlaying.NOTHING
            return
        }

        val metadata = controller.metadata
        _nowPlaying.value = NowPlaying(
            packageName = controller.packageName,
            appLabel = MusicApps.labelFor(appContext, controller.packageName),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    /** What the music panel shows. [isActive] is false when nothing is controllable. */
    data class NowPlaying(
        val packageName: String? = null,
        val appLabel: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val artwork: Bitmap? = null,
        val isPlaying: Boolean = false,
    ) {
        val isActive: Boolean
            get() = packageName != null

        companion object {
            val NOTHING = NowPlaying()
        }
    }

    companion object {
        private const val TAG = "MediaControlHub"

        @Volatile
        private var instance: MediaControlHub? = null

        @JvmStatic
        fun from(context: Context): MediaControlHub = instance ?: synchronized(this) {
            instance ?: MediaControlHub(context).also { instance = it }
        }
    }
}
