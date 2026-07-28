package app.organicmaps.cycling.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output has to be readable by Strava and Garmin Connect, so these assert the details those
 * importers are fussy about: UTC timestamps, namespaced extensions, and no scientific notation.
 */
class GpxExporterTest {

    private companion object {
        /** 2023-11-14T22:13:20Z */
        const val START = 1_700_000_000_000L
    }

    private fun sample(
        offsetSeconds: Long = 0,
        lat: Double = 55.6050,
        lon: Double = 13.0038,
        altitude: Double? = null,
        hr: Int? = null,
        cadence: Int? = null,
        power: Int? = null,
    ) = RideSample(
        timestampMs = START + offsetSeconds * 1000,
        latitude = lat,
        longitude = lon,
        altitudeMetres = altitude,
        gpsSpeedMps = 5.0,
        sensorSpeedMps = null,
        heartRateBpm = hr,
        cadenceRpm = cadence,
        powerWatts = power,
    )

    @Test
    fun `produces a well formed gpx skeleton`() {
        val gpx = GpxExporter.export("Morning ride", listOf(sample(), sample(10)))

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx.contains("<name>Morning ride</name>"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun `timestamps are utc with a trailing Z`() {
        val gpx = GpxExporter.export("Ride", listOf(sample(), sample(10)))

        assertTrue(gpx.contains("<time>2023-11-14T22:13:20Z</time>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:30Z</time>"))
    }

    @Test
    fun `heart rate and cadence go in the garmin extension`() {
        val gpx = GpxExporter.export("Ride", listOf(sample(hr = 152, cadence = 88), sample(10)))

        assertTrue(gpx.contains("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\""))
        assertTrue(gpx.contains("<gpxtpx:hr>152</gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:cad>88</gpxtpx:cad>"))
    }

    @Test
    fun `power sits outside the trackpoint extension`() {
        val gpx = GpxExporter.export("Ride", listOf(sample(power = 240), sample(10)))

        // Strava reads <power> as a direct child of <extensions>, not inside TrackPointExtension.
        val powerIndex = gpx.indexOf("<power>240</power>")
        val extensionsIndex = gpx.indexOf("<extensions>")
        assertTrue(powerIndex > extensionsIndex)
        assertFalse(gpx.contains("<gpxtpx:TrackPointExtension>\n            <power>"))
    }

    @Test
    fun `a point with no sensor data has no extensions block`() {
        val gpx = GpxExporter.export("Ride", listOf(sample(), sample(10)))

        assertFalse(gpx.contains("<extensions>"))
    }

    @Test
    fun `altitude is emitted only when present`() {
        val withAltitude = GpxExporter.export("Ride", listOf(sample(altitude = 12.5), sample(10)))
        val without = GpxExporter.export("Ride", listOf(sample(), sample(10)))

        assertTrue(withAltitude.contains("<ele>12.5000000</ele>"))
        assertFalse(without.contains("<ele>"))
    }

    @Test
    fun `coordinates never use scientific notation`() {
        // A longitude near zero is where naive formatting produces 1.0E-5 and breaks importers.
        val gpx = GpxExporter.export("Ride", listOf(sample(lon = 0.00001), sample(10, lon = 0.00001)))

        assertFalse(gpx.contains("E-"))
        assertTrue(gpx.contains("lon=\"0.0000100\""))
    }

    @Test
    fun `names with xml characters are escaped`() {
        val gpx = GpxExporter.export("Tom & Jerry's <ride>", listOf(sample(), sample(10)))

        assertTrue(gpx.contains("<name>Tom &amp; Jerry&apos;s &lt;ride&gt;</name>"))
    }

    @Test
    fun `points are written in time order regardless of input order`() {
        val gpx = GpxExporter.export("Ride", listOf(sample(60, lat = 55.7), sample(0, lat = 55.6)))

        assertTrue(gpx.indexOf("55.6000000") < gpx.indexOf("55.7000000"))
    }
}
