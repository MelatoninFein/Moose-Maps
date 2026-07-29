package app.organicmaps.cycling.rides

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sector division and the purple/green/yellow grading.
 *
 * The grading is the part that carries meaning a rider already knows from watching racing, so
 * getting it subtly wrong - green where it should be purple - would be worse than not colouring
 * anything at all.
 */
class SectorsTest {

    @Test
    fun `a segment divides into three even sectors`() {
        // Ten intervals across eleven points.
        assertEquals(listOf(3, 6, 10), Sectors.boundaries(11))
    }

    @Test
    fun `a segment too short to divide stays one sector`() {
        assertEquals(listOf(2), Sectors.boundaries(3))
        assertEquals(listOf(1), Sectors.boundaries(2))
    }

    @Test
    fun `points fall in the sector they belong to`() {
        // Boundaries at 3, 6, 10.
        assertEquals(0, Sectors.sectorOf(0, 11))
        assertEquals(0, Sectors.sectorOf(3, 11))
        assertEquals(1, Sectors.sectorOf(4, 11))
        assertEquals(1, Sectors.sectorOf(6, 11))
        assertEquals(2, Sectors.sectorOf(7, 11))
        assertEquals(2, Sectors.sectorOf(10, 11))
    }

    @Test
    fun `sector times are durations rather than running totals`() {
        // Cumulative splits: sector ends at 30 s, 70 s, 120 s.
        val splits = listOf(0L, 10_000, 20_000, 30_000, 45_000, 60_000, 70_000, 85_000, 100_000, 110_000, 120_000)

        // A slow middle sector must read as 40 s, not as "70 s" inherited by everything after it.
        assertEquals(listOf(30_000L, 40_000L, 50_000L), Sectors.sectorTimes(splits))
    }

    @Test
    fun `sector times sum to the lap`() {
        val splits = listOf(0L, 5_000, 11_000, 18_000, 26_000, 35_000, 45_000)
        assertEquals(splits.last(), Sectors.sectorTimes(splits).sum())
    }

    @Test
    fun `beating the outright record is purple`() {
        assertEquals(
            SectorGrade.RECORD,
            SectorGrade.of(actualMillis = 29_000, recordMillis = 30_000, bestLapMillis = 32_000),
        )
    }

    @Test
    fun `beating your best lap without beating the record is green`() {
        // Faster than the same sector in the best lap, but the record was set in some other ride.
        assertEquals(
            SectorGrade.IMPROVED,
            SectorGrade.of(actualMillis = 31_000, recordMillis = 30_000, bestLapMillis = 32_000),
        )
    }

    @Test
    fun `slower than both is yellow`() {
        assertEquals(
            SectorGrade.SLOWER,
            SectorGrade.of(actualMillis = 35_000, recordMillis = 30_000, bestLapMillis = 32_000),
        )
    }

    @Test
    fun `the first ever ride of a sector sets the record`() {
        assertEquals(
            SectorGrade.RECORD,
            SectorGrade.of(actualMillis = 40_000, recordMillis = null, bestLapMillis = null),
        )
    }

    @Test
    fun `a record can be held by a lap that was not the best overall`() {
        // The point of keeping records separately: the best lap's middle sector was poor, so a
        // merely-decent sector still beats it while falling short of the outright record.
        assertEquals(
            SectorGrade.IMPROVED,
            SectorGrade.of(actualMillis = 41_000, recordMillis = 38_000, bestLapMillis = 44_000),
        )
    }
}
