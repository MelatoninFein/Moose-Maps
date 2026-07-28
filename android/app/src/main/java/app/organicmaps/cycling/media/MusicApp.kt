package app.organicmaps.cycling.media

import android.content.Context
import android.content.Intent

/**
 * The music apps this build knows about by name.
 *
 * Playback control itself is app-agnostic - it goes through the platform media session, so any
 * player works. This list only decides which app is *preferred* when several are publishing a
 * session at once, and which ones get a "open the app" shortcut in the map controls.
 *
 * Package names must also be declared in the manifest `<queries>` block; without that, package
 * visibility on Android 11+ hides them and the launch intent resolves to null.
 */
enum class MusicApp(val packageName: String, val displayName: String) {
    SPOTIFY("com.spotify.music", "Spotify"),
    TIDAL("com.aspiro.tidal", "TIDAL"),
    ;

    fun isInstalled(context: Context): Boolean = launchIntent(context) != null

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    companion object {

        /** Preference order used when more than one player has an active session. */
        val preferenceOrder: List<MusicApp> = listOf(SPOTIFY, TIDAL)

        fun forPackage(packageName: String): MusicApp? = entries.firstOrNull { it.packageName == packageName }

        fun installed(context: Context): List<MusicApp> = entries.filter { it.isInstalled(context) }
    }
}
