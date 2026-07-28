package app.organicmaps.cycling.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An installed music app, discovered at runtime rather than hard-coded. */
data class MusicApp(
    val packageName: String,
    val displayName: String,
)

/**
 * Finds the music apps installed on the device.
 *
 * Nothing here decides which players are *supported*. Playback control goes through the platform
 * media session, so every compliant player works whether or not it appears in this list - YouTube,
 * YouTube Music, TIDAL, Qobuz, Spotify, Poweramp and the rest are all driven by the same code.
 *
 * This list exists only to offer "open the app" shortcuts in the music panel, and it is built by
 * asking the package manager for anything declaring CATEGORY_APP_MUSIC rather than by matching
 * known package names. An earlier version hard-coded Spotify and TIDAL, which meant a rider's
 * player was silently missing from the panel purely because this app had not heard of it.
 */
object MusicApps {

    private val musicLauncherIntent: Intent
        get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)

    fun installed(context: Context): List<MusicApp> {
        val packageManager = context.packageManager
        val resolved = try {
            packageManager.queryIntentActivities(musicLauncherIntent, 0)
        } catch (e: RuntimeException) {
            // A package manager transaction can fail under memory pressure. An empty shortcut list
            // is a fine degradation - playback control does not depend on it.
            emptyList()
        }

        return resolved
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                MusicApp(packageName, info.loadLabel(packageManager).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.displayName.lowercase() }
    }

    fun launchIntent(context: Context, packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    /** Human-readable name for a package, falling back to the package id when it can't be read. */
    fun labelFor(context: Context, packageName: String): String = try {
        val packageManager = context.packageManager
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}
