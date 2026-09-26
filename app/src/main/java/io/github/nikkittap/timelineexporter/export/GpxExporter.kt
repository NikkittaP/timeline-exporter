package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint

/**
 * Export to GPX 1.1 (gpsies.com / Garmin / Strava / OsmAnd / Google Earth).
 *
 * A track becomes `<gpx><trk><name/><trkseg><trkpt lat lon><time/></trkpt>…`.
 *
 * Places become `<wpt>` waypoints, which is the element the format already has
 * for exactly this — a named point of interest rather than a fix along a
 * route. Every consumer that understands GPX at all shows waypoints, so a
 * places export drops into OsmAnd or Garmin next to the track with no
 * conversion.
 *
 * Child-element order inside `<wpt>` is not free: the GPX 1.1 schema declares
 * wptType as a `xsd:sequence`, so time → name → desc → link → type is the only
 * order that validates. Writing them in a "nicer" order produces a file that
 * strict readers reject.
 *
 * `Color by day` is ignored here. GPX 1.1 has no line colour, every app that
 * adds one does it with its own extension, and the only way to give each day
 * its own colour would be one `<trk>` per day - which breaks the imports that
 * read just the first track, Strava's among them, for the sake of a colour
 * most of them would not draw anyway.
 */
object GpxExporter : Exporter {
    override val displayName = "GPX"
    override val fileExtension = "gpx"
    override val mimeType = "application/gpx+xml"

    private const val CREATOR = "Timeline Exporter for Android"

    override fun export(content: ExportContent, name: String, out: Appendable) = when (content) {
        is ExportContent.Track -> exportTrack(content.points, name, out)
        is ExportContent.Places -> exportPlaces(content.places, name, out)
    }

    private fun header(sb: Appendable) {
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="""").append(escapeXml(CREATOR)).append("\"\n")
        sb.append("""     xmlns="http://www.topografix.com/GPX/1/1"""").append('\n')
        sb.append("""     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""").append('\n')
        sb.append("""     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
            .append('\n')
    }

    private fun exportTrack(points: List<PathPoint>, trackName: String, sb: Appendable) {
        header(sb)
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"")
            appendCoordinate(sb, p.latitude)
            sb.append("\" lon=\"")
            appendCoordinate(sb, p.longitude)
            sb.append("\">\n")
            sb.append("        <time>")
            appendIsoInstant(sb, p.timeUtc)
            sb.append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
    }

    private fun exportPlaces(places: List<ExportPlace>, documentName: String, sb: Appendable) {
        header(sb)
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(documentName)).append("</name>\n")
        sb.append("  </metadata>\n")
        for (place in places) {
            sb.append("  <wpt lat=\"")
            appendCoordinate(sb, place.latitude)
            sb.append("\" lon=\"")
            appendCoordinate(sb, place.longitude)
            sb.append("\">\n")
            // The last visit, not the first: a waypoint's <time> is read as
            // "when this was current", and a place the user still goes to
            // should not be stamped with a date from a year ago.
            sb.append("    <time>")
            appendIsoInstant(sb, place.lastVisit)
            sb.append("</time>\n")
            sb.append("    <name>").append(escapeXml(place.name)).append("</name>\n")
            sb.append("    <desc>").append(escapeXml(describe(place))).append("</desc>\n")
            sb.append("    <link href=\"").append(escapeXml(place.mapsUrl)).append("\">\n")
            sb.append("      <text>Google Maps</text>\n")
            sb.append("    </link>\n")
            place.category?.takeIf { it.isNotEmpty() }?.let {
                sb.append("    <type>").append(escapeXml(it)).append("</type>\n")
            }
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
    }
}

/**
 * The one-line summary written into a waypoint description.
 *
 * English and ASCII on purpose, like the CSV columns and the suggested
 * filenames: this is a field inside a data file that other software reads and
 * that the user may hand to someone in another country, not a label rendered
 * in the app. The numbers are the part that carries meaning; translating the
 * word around them would only make two exports of the same data differ.
 */
internal fun describe(place: ExportPlace): String {
    val visits = if (place.visits == 1) "1 visit" else "${place.visits} visits"
    val hours = place.totalSeconds / 3600.0
    val duration = if (hours >= 1.0) ", %.1f h total".format(java.util.Locale.ROOT, hours) else ""
    val address = place.address?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
    return "$visits$duration$address"
}
