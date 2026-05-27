package io.github.nikitapetroff.timelineexporter.export

import io.github.nikitapetroff.timelineexporter.parser.PathPoint
import java.time.format.DateTimeFormatter

/**
 * Export to GPX 1.1 (gpsies.com / Garmin / Strava / OsmAnd / Google Earth).
 * Output structure mirrors the Python prototype: `<gpx><trk><name/><trkseg>
 * <trkpt lat lon><time/></trkpt>...</trkseg></trk></gpx>`.
 */
object GpxExporter : Exporter {
    override val displayName = "GPX"
    override val fileExtension = "gpx"
    override val mimeType = "application/gpx+xml"

    private const val CREATOR = "Timeline Exporter for Android"

    override fun export(points: List<PathPoint>, trackName: String): String {
        val sb = StringBuilder(points.size * 150 + 500)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="""").append(escapeXml(CREATOR)).append("\"\n")
        sb.append("""     xmlns="http://www.topografix.com/GPX/1/1"""").append('\n')
        sb.append("""     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""").append('\n')
        sb.append("""     xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""")
            .append('\n')
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(p.latitude)
                .append("\" lon=\"").append(p.longitude).append("\">\n")
            sb.append("        <time>")
                .append(DateTimeFormatter.ISO_INSTANT.format(p.timeUtc))
                .append("</time>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
