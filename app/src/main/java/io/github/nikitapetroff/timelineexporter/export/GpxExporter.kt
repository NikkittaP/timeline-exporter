package io.github.nikitapetroff.timelineexporter.export

import io.github.nikitapetroff.timelineexporter.parser.PathPoint
import java.time.format.DateTimeFormatter

/**
 * Build a GPX 1.1 document from a list of GPS points.
 *
 * GPX is the de-facto interchange format for tracks — supported by Strava,
 * Garmin, Komoot, OsmAnd, Google Earth, QGIS, and most other GPS tools.
 *
 * Output structure (matches the Python prototype):
 *   <gpx>
 *     <trk>
 *       <name>...</name>
 *       <trkseg>
 *         <trkpt lat="..." lon="...">
 *           <time>...</time>
 *         </trkpt>
 *         ...
 *       </trkseg>
 *     </trk>
 *   </gpx>
 */
fun buildGpx(
    points: List<PathPoint>,
    trackName: String = "Google Timeline export",
    creator: String = "Timeline Exporter for Android",
): String {
    // Preallocate roughly enough to avoid mid-build reallocations.
    val sb = StringBuilder(points.size * 150 + 500)
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<gpx version="1.1" creator="""").append(escapeXml(creator)).append("\"\n")
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

/**
 * Replace the five characters that have special meaning in XML.
 * Short-circuit on the common case where nothing needs escaping.
 */
private fun escapeXml(s: String): String {
    if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
