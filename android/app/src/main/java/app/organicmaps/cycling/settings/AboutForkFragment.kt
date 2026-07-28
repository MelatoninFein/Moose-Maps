package app.organicmaps.cycling.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import app.organicmaps.BuildConfig
import app.organicmaps.R
import app.organicmaps.base.BaseMwmFragment
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.util.DateUtils
import app.organicmaps.util.Utils
import app.organicmaps.util.WindowInsetUtils.ScrollableContentInsetsListener

/**
 * The fork's About screen.
 *
 * Upstream's version is not reused: it links to Organic Maps' donation page, Telegram, GitHub,
 * Matrix, Mastodon, Twitter and store listing, none of which serve this build's users. What is
 * kept is the attribution that the licences actually require - OpenStreetMap for the map data
 * under ODbL, and Organic Maps for the code under Apache-2.0.
 */
class AboutForkFragment : BaseMwmFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_about_fork, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ScrollableContentInsetsListener(view))

        view.findViewById<TextView>(R.id.about_version).text = BuildConfig.VERSION_NAME

        // The map data has a version of its own, independent of the app's.
        val dataVersion = DateUtils.getShortDateFormatter().format(Framework.getDataVersion())
        view.findViewById<TextView>(R.id.about_osm).text = getString(R.string.about_fork_osm, dataVersion)

        view.findViewById<Button>(R.id.about_source).setOnClickListener {
            Utils.openUri(requireContext(), Uri.parse(SOURCE_URL), null)
        }
    }

    private companion object {
        const val SOURCE_URL = "https://github.com/MelatoninFein/Moose-Maps"
    }
}
