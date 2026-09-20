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
 *
 * A track is one Placemark wrapping a LineString. Places are one Placemark
 * each wrapping a Point, which is what makes them individually clickable in
 * Google Earth and importable as pins into My Maps. As in GPX, child order
 * inside Placemark follows the schema's sequence: name → description →
 * TimeSpan → styleUrl → geometry.
 */
object KmlExporter : Exporter {
    override val displayName = "KML"
    override val fileExtension = "kml"
    override val mimeType = "application/vnd.google-earth.kml+xml"

    override fun export(content: ExportContent, name: String, out: Appendable) = when (content) {
        is ExportContent.Track -> exportTrack(content.points, name, out)
        is ExportContent.Places -> exportPlaces(content.places, name, out)
    }

    private fun exportTrack(points: List<PathPoint>, trackName: String, sb: Appendable) {
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
            appendCoordinate(sb, p.longitude)
            sb.append(',')
            appendCoordinate(sb, p.latitude)
            sb.append(",0\n")
        }
        sb.append("        </coordinates>\n")
        sb.append("      </LineString>\n")
        sb.append("    </Placemark>\n")
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
    }

    private fun exportPlaces(places: List<ExportPlace>, documentName: String, sb: Appendable) {
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        sb.append("  <Document>\n")
        sb.append("    <name>").append(escapeXml(documentName)).append("</name>\n")
        sb.append("    <Style id=\"placeStyle\">\n")
        sb.append("      <IconStyle>\n")
        sb.append("        <color>ff2257ff</color>\n")  // same orange as the track
        sb.append("        <Icon>\n")
        // Google's own hosted pin. A relative or missing href makes Google
        // Earth fall back to a default that is not guaranteed across versions.
        sb.append("          <href>https://maps.google.com/mapfiles/kml/pushpin/ylw-pushpin.png</href>\n")
        sb.append("        </Icon>\n")
        sb.append("      </IconStyle>\n")
        sb.append("    </Style>\n")
        for (place in places) {
            sb.append("    <Placemark>\n")
            sb.append("      <name>").append(escapeXml(place.name)).append("</name>\n")
            sb.append("      <description>").append(escapeXml(describe(place)))
                .append("</description>\n")
            // TimeSpan makes Google Earth's time slider work on the result:
            // dragging it filters the pins down to the places visited in the
            // chosen window, which is most of a stats feature for four lines.
            sb.append("      <TimeSpan>\n")
            sb.append("        <begin>")
            appendIsoInstant(sb, place.firstVisit)
            sb.append("</begin>\n")
            sb.append("        <end>")
            appendIsoInstant(sb, place.lastVisit)
            sb.append("</end>\n")
            sb.append("      </TimeSpan>\n")
            sb.append("      <styleUrl>#placeStyle</styleUrl>\n")
            sb.append("      <Point>\n")
            sb.append("        <coordinates>")
            appendCoordinate(sb, place.longitude)
            sb.append(',')
            appendCoordinate(sb, place.latitude)
            sb.append(",0</coordinates>\n")
            sb.append("      </Point>\n")
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
    }
}
