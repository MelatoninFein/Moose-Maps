package app.organicmaps.cycling.rides

import androidx.fragment.app.Fragment
import app.organicmaps.base.BaseToolbarActivity

/** Segments and their times, reachable from the main menu rather than from Settings. */
class SegmentsActivity : BaseToolbarActivity() {
    override fun getFragmentClass(): Class<out Fragment> = SegmentsFragment::class.java
}