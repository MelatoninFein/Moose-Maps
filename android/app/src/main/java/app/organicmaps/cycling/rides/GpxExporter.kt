package app.organicmaps.cycling.rides

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a recorded ride as GPX 1.1 with Garmin's TrackPointExtension.
 *
 * This is the format that gets a ride *out* of this app: Strava, Golden Cheetah, Garmin Connect and
 * everything else read it, heart rate and cadence included. Without it a ride is trapped in the
 * private JSON this app happens to write, which is a bad deal for anyone who later stops using it.
 *
 * Pure string building with no Android types, so the output can be asserted on in a unit test
 * rather than eyeballed on a device.
 */
object GpxExporter {

    private const val TPX_NS = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"

    /**
     * Timestamps must be UTC with a trailing Z. A local-time offset is technically legal GPX but
     * several importers, Strava included, are happier with Z.
     */
    private fun isoFormatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun export(trackName: String, samples: List<RideSample>): String {
        val iso = isoFormatter()
        val builder = StringBuilder(samples.size * 200)

        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"Moose Maps\"\n")
        builder.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        builder.append("     xmlns:gpxtpx=\"").append(TPX_NS).append("\">\n")

        samples.minByOrNull { it.timestampMs }?.let { first ->
            builder.append("  <metadata><time>").append(iso.format(Date(first.timestampMs)))
                .append("</time></metadata>\n")
        }

        builder.append("  <trk>\n    <name>").append(escape(trackName)).append("</name>\n")
        builder.append("    <trkseg>\n")

        samples.sortedBy { it.timestampMs }.forEach { sample ->
            builder.append("      <trkpt lat=\"").append(coordinate(sample.latitude))
                .append("\" lon=\"").append(coordinate(sample.longitude)).append("\">\n")
            sample.altitudeMetres?.let {
                builder.append("        <ele>").append(coordinate(it)).append("</ele>\n")
            }
            builder.append("        <time>").append(iso.format(Date(sample.timestampMs))).append("</time>\n")

            // Only emit an extensions block when there is actually something to put in it -
            // an empty one trips up stricter parsers.
            val hasExtensions = sample.heartRateBpm != null || sample.cadenceRpm != null || sample.powerWatts != null
            if (hasExtensions) {
                builder.append("        <extensions>\n")
                // Power sits outside TrackPointExtension: that is where Strava and Garmin read it.
                sample.powerWatts?.let { builder.append("          <power>").append(it).append("</power>\n") }
                if (sample.heartRateBpm != null || sample.cadenceRpm != null) {
                    builder.append("          <gpxtpx:TrackPointExtension>\n")
                    sample.heartRateBpm?.let {
                        builder.append("            <gpxtpx:hr>").append(it).append("</gpxtpx:hr>\n")
                    }
                    sample.cadenceRpm?.let {
                        builder.append("            <gpxtpx:cad>").append(it).append("</gpxtpx:cad>\n")
                    }
                    builder.append("          </gpxtpx:TrackPointExtension>\n")
                }
                builder.append("        </extensions>\n")
            }
            builder.append("      </trkpt>\n")
        }

        builder.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return builder.toString()
    }

    /** Fixed notation with enough precision for ~1 cm, and never scientific notation. */
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
