package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint

/**
 * Export to KML 2.2 (Google Earth, Google My Maps).
 *
 * KML coordinate format is "longitude,latitude,altitude" — note the order
 * is the reverse of GPX. Altitude is required by the spec; we always emit 0.
 *
 * KML color format is AABBGGRR (alpha, blue, green, red) in hex — also
 * reversed from the usual web RGB. The line color below is the same orange
 * we use on the map: web #FF5722 -> KML ff2257ff.
 */
object KmlExporter : Exporter {
    override val displayName = "KML"
    override val fileExtension = "kml"
    override val mimeType = "application/vnd.google-earth.kml+xml"

    override fun export(points: List<PathPoint>, trackName: String): String {
        val sb = StringBuilder(points.size * 50 + 500)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        sb.append("  <Document>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <Style id=\"trackStyle\">\n")
        sb.append("      <LineStyle>\n")
        sb.append("        <color>ff2257ff</color>\n")  // AABBGGRR for #FF5722
        sb.append("        <width>4</width>\n")
        sb.append("      </LineStyle>\n")
        sb.append("    </Style>\n")
        sb.append("    <Placemark>\n")
        sb.append("      <name>Track</name>\n")
        sb.append("      <styleUrl>#trackStyle</styleUrl>\n")
        sb.append("      <LineString>\n")
        sb.append("        <coordinates>\n")
        for (p in points) {
            // lon,lat,alt  — whitespace-separated between points.
            sb.append("          ")
                .append(p.longitude).append(',')
                .append(p.latitude).append(",0\n")
        }
        sb.append("        </coordinates>\n")
        sb.append("      </LineString>\n")
        sb.append("    </Placemark>\n")
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
        return sb.toString()
    }
}
