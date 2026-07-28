package app.organicmaps.cycling.rides

import androidx.fragment.app.Fragment
import app.organicmaps.base.BaseToolbarActivity

/**
 * Rides, reachable from the main menu.
 *
 * Recorded rides are content, not configuration - the same category as bookmarks - so they belong
 * beside them in the menu rather than buried behind Settings.
 */
class RidesActivity : BaseToolbarActivity() {
    override fun getFragmentClass(): Class<out Fragment> = RidesFragment::class.java
}