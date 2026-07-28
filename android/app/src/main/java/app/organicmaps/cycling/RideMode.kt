package app.organicmaps.cycling

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Whether a ride is in progress.
 *
 * The single piece of state the cycling UI hangs off. Organic Maps' map screen carries five
 * controls and no permanent readouts; every widget this fork adds is instrumentation that only
 * means anything while actually riding, so all of it is gated on this rather than living on the
 * map forever. Not riding, the app is upstream again.
 *
 * Deliberately a plain observable rather than something derived from the recorder's internals:
 * several unrelated views need to react, and none of them should have to know how recording works.
 */
object RideMode {

    private val _riding = MutableLiveData(false)
    val riding: LiveData<Boolean> = _riding

    val isRiding: Boolean
        get() = _riding.value == true

    /** Called by the recorder; posted rather than set so it is safe from any thread. */
    fun setRiding(riding: Boolean) {
        if (_riding.value != riding) {
            _riding.postValue(riding)
        }
    }
}
