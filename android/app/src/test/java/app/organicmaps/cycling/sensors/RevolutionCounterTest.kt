package app.organicmaps.cycling.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cases that matter here are the ones a rider hits within the first few minutes: the counters
 * wrapping, and the sensor going quiet at a traffic light.
 */
class RevolutionCounterTest {

    private companion object {
        const val TICKS_PER_SECOND = 1024
        const val DELTA = 0.0001
    }

    @Test
    fun `first sample only establishes a baseline`() {
        val counter = RevolutionCounter.forCrank()

        assertNull(counter.update(revolutions = 100, eventTime = 0, nowMs = 0))
    }

    @Test
    fun `computes revolutions per second from the second sample`() {
        val counter = RevolutionCounter.forCrank()
        counter.update(revolutions = 100, eventTime = 0, nowMs = 0)

        // 80 crank revolutions over 60 s is 80 rpm.
        val rate = counter.update(
            revolutions = 180,
            eventTime = 60 * TICKS_PER_SECOND % 65536,
            nowMs = 60_000,
        )

        assertEquals(80.0 / 60.0, rate!!, DELTA)
    }

    @Test
    fun `handles the event time wrapping at 16 bits`() {
        val counter = RevolutionCounter.forCrank()
        // Start near the top of the 16-bit event-time range.
        counter.update(revolutions = 100, eventTime = 65_000, nowMs = 0)

        // Wraps past 65535: 65_536 - 65_000 + 488 = 1024 ticks = exactly one second, one revolution.
        val rate = counter.update(revolutions = 101, eventTime = 488, nowMs = 1_000)

        assertEquals(1.0, rate!!, DELTA)
    }

    @Test
    fun `handles the crank revolution counter wrapping at 16 bits`() {
        val counter = RevolutionCounter.forCrank()
        counter.update(revolutions = 65_534, eventTime = 0, nowMs = 0)

        // 65534 -> 2 is four revolutions across the wrap, not a 65k jump backwards.
        val rate = counter.update(revolutions = 2, eventTime = TICKS_PER_SECOND, nowMs = 1_000)

        assertEquals(4.0, rate!!, DELTA)
    }

    @Test
    fun `handles the wheel revolution counter wrapping at 32 bits`() {
        val counter = RevolutionCounter.forWheel()
        val justBelowWrap = (1L shl 32) - 2
        counter.update(revolutions = justBelowWrap, eventTime = 0, nowMs = 0)

        val rate = counter.update(revolutions = 3, eventTime = TICKS_PER_SECOND, nowMs = 1_000)

        assertEquals(5.0, rate!!, DELTA)
    }

    @Test
    fun `holds the last rate briefly when no new revolution arrives`() {
        val counter = RevolutionCounter.forCrank(stallTimeoutMs = 3_000)
        counter.update(revolutions = 100, eventTime = 0, nowMs = 0)
        counter.update(revolutions = 101, eventTime = TICKS_PER_SECOND, nowMs = 1_000)

        // The sensor repeats the same counters: within the timeout the last rate stands.
        val held = counter.update(revolutions = 101, eventTime = TICKS_PER_SECOND, nowMs = 2_000)

        assertEquals(1.0, held!!, DELTA)
    }

    @Test
    fun `decays to zero once the stall timeout passes`() {
        val counter = RevolutionCounter.forCrank(stallTimeoutMs = 3_000)
        counter.update(revolutions = 100, eventTime = 0, nowMs = 0)
        counter.update(revolutions = 101, eventTime = TICKS_PER_SECOND, nowMs = 1_000)

        // Stopped at a light: the readout must fall to zero rather than freeze at the last speed.
        val stalled = counter.update(revolutions = 101, eventTime = TICKS_PER_SECOND, nowMs = 5_000)

        assertEquals(0.0, stalled!!, DELTA)
    }

    @Test
    fun `resumes cleanly after a stall`() {
        val counter = RevolutionCounter.forCrank(stallTimeoutMs = 3_000)
        counter.update(revolutions = 100, eventTime = 0, nowMs = 0)
        counter.update(revolutions = 101, eventTime = TICKS_PER_SECOND, nowMs = 1_000)
        counter.update(revolutions = 101, eventTime = TICKS_PER_SECOND, nowMs = 5_000)

        val resumed = counter.update(revolutions = 103, eventTime = 3 * TICKS_PER_SECOND, nowMs = 6_000)

        assertEquals(1.0, resumed!!, DELTA)
    }

    @Test
    fun `a revolution reported with no elapsed time does not divide by zero`() {
        val counter = RevolutionCounter.forCrank()
        counter.update(revolutions = 100, eventTime = 500, nowMs = 0)

        val rate = counter.update(revolutions = 105, eventTime = 500, nowMs = 100)

        assertEquals(0.0, rate!!, DELTA)
    }

    @Test
    fun `reset drops the baseline so a reconnect does not spike`() {
        val counter = RevolutionCounter.forCrank()
        counter.update(revolutions = 100, eventTime = 0, nowMs = 0)
        counter.reset()

        // After a reconnect the first sample must again be treated as a baseline.
        assertNull(counter.update(revolutions = 5_000, eventTime = 30_000, nowMs = 60_000))
    }
}
