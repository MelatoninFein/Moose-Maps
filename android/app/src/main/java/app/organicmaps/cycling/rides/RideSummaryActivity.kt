package app.organicmaps.cycling.rides

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import app.organicmaps.base.BaseToolbarActivity

/**
 * The screen shown the moment a ride ends.
 *
 * Finishing used to hand the rider back to the map with a toast, so the numbers for the last three
 * hours sat four taps away behind Settings with nothing saying they existed. A lap of a segment got
 * a full classification card while the ride itself - the larger thing - got nothing.
 *
 * It is the ride screen in a different frame: same trace, same tiles, same chart and zones, with
 * the keep-or-discard decision on top. Reusing it rather than writing a second version means the
 * figures cannot drift apart between the two places a rider reads them.
 */
class RideSummaryActivity : BaseToolbarActivity() {

    override fun getFragmentClass(): Class<out Fragment> = RideDetailFragment::class.java

    companion object {

        fun start(context: Context, fileName: String) {
            val intent = Intent(context, RideSummaryActivity::class.java)
                .putExtra(RideDetailFragment.EXTRA_FILE_NAME, fileName)
                .putExtra(RideDetailFragment.EXTRA_JUST_FINISHED, true)
            context.startActivity(intent)
        }
    }
}
