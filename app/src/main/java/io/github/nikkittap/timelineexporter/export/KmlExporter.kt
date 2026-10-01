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
 * A track is one Placemark wrapping a LineString - or, with `Color by day` on,
 * one Placemark per local day in that day's colour. Places are one Placemark
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
        is ExportContent.Track -> {
            val days = if (content.colorByDay) trackDays(content.points, content.fallbackZone) else emptyList()
            // Fewer than two samples have no day with a line in it; the plain
            // track is still the right file for them.
            if (days.isEmpty()) exportTrack(content.points, name, out)
            else exportTrackByDay(content.points, days, name, out)
        }
        is ExportContent.Places -> exportPlaces(content.places, name, out)
        is ExportContent.Trips -> exportTrips(content.trips, name, out)
    }

    /**
     * One Placemark per trip: a line in the trip's colour, named by its dates,
     * with a TimeSpan for Google Earth's time slider and the summary as its
     * description. Trips with no points left after filtering are left out.
     */
    private fun exportTrips(trips: List<ExportTrip>, documentName: String, sb: Appendable) {
        val drawn = trips.filter { it.points.isNotEmpty() }
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        sb.append("  <Document>\n")
        sb.append("    <name>").append(escapeXml(documentName)).append("</name>\n")
        for (color in drawn.map { it.color }.distinct()) {
            sb.append("    <Style id=\"").append(dayStyleId(color)).append("\">\n")
            sb.append("      <LineStyle>\n")
            sb.append("        <color>").append(kmlColor(color)).append("</color>\n")
            sb.append("        <width>4</width>\n")
            sb.append("      </LineStyle>\n")
            sb.append("    </Style>\n")
        }
        for (trip in drawn) {
            sb.append("    <Placemark>\n")
            sb.append("      <name>").append(escapeXml(trip.name)).append("</name>\n")
            sb.append("      <description>").append(escapeXml(describe(trip))).append("</description>\n")
            sb.append("      <TimeSpan>\n")
            sb.append("        <begin>")
            appendIsoInstant(sb, trip.trip.start)
            sb.append("</begin>\n")
            sb.append("        <end>")
            appendIsoInstant(sb, trip.trip.end)
            sb.append("</end>\n")
            sb.append("      </TimeSpan>\n")
            sb.append("      <styleUrl>#").append(dayStyleId(trip.color)).append("</styleUrl>\n")
            appendLineString(trip.points, wholeTrack(trip.points), "      ", sb)
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
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

    /**
     * The track as one Placemark per local day, each styled in the colour the
     * map's `Color by day` mode gives that date.
     *
     * One Style per colour rather than per day: the palette repeats every
     * three weeks, so a year of track needs twenty-one styles, not 365. They
     * all come first because the schema puts a Document's style selectors
     * before its features.
     *
     * A day gets a TimeSpan from its first sample to its last, which puts the
     * track on Google Earth's time slider as well: drag it and the days play
     * back one line at a time. A day made of more than one run - the rare
     * date-line crossing that revisits a date, see [trackDaySpans] - is one
     * Placemark around a MultiGeometry, so the file never lists a date twice.
     */
    private fun exportTrackByDay(
        points: List<PathPoint>,
        days: List<TrackDay>,
        trackName: String,
        sb: Appendable,
    ) {
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        sb.append("  <Document>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        for (color in days.map { it.color }.distinct()) {
            sb.append("    <Style id=\"").append(dayStyleId(color)).append("\">\n")
            sb.append("      <LineStyle>\n")
            sb.append("        <color>").append(kmlColor(color)).append("</color>\n")
            sb.append("        <width>4</width>\n")
            sb.append("      </LineStyle>\n")
            sb.append("    </Style>\n")
        }
        for (day in days) {
            sb.append("    <Placemark>\n")
            sb.append("      <name>").append(day.date.toString()).append("</name>\n")
            sb.append("      <TimeSpan>\n")
            sb.append("        <begin>")
            appendIsoInstant(sb, day.runs.minOf { points[it.first].timeUtc })
            sb.append("</begin>\n")
            sb.append("        <end>")
            appendIsoInstant(sb, day.runs.maxOf { points[it.last].timeUtc })
            sb.append("</end>\n")
            sb.append("      </TimeSpan>\n")
            sb.append("      <styleUrl>#").append(dayStyleId(day.color)).append("</styleUrl>\n")
            if (day.runs.size == 1) {
                appendLineString(points, day.runs.single(), "      ", sb)
            } else {
                sb.append("      <MultiGeometry>\n")
                for (run in day.runs) appendLineString(points, run, "        ", sb)
                sb.append("      </MultiGeometry>\n")
            }
            sb.append("    </Placemark>\n")
        }
        sb.append("  </Document>\n")
        sb.append("</kml>\n")
    }

    private fun appendLineString(points: List<PathPoint>, span: TrackDaySpan, indent: String, sb: Appendable) {
        sb.append(indent).append("<LineString>\n")
        sb.append(indent).append("  <coordinates>\n")
        for (i in span.first..span.last) {
            val p = points[i]
            sb.append(indent).append("    ")
            appendCoordinate(sb, p.longitude)
            sb.append(',')
            appendCoordinate(sb, p.latitude)
            sb.append(",0\n")
        }
        sb.append(indent).append("  </coordinates>\n")
        sb.append(indent).append("</LineString>\n")
    }

    /** A style id for a `#RRGGBB` colour, e.g. `day-0072B2`. */
    private fun dayStyleId(color: String): String = "day-" + color.removePrefix("#")

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

/**
 * A web `#RRGGBB` colour as KML's opaque `aabbggrr`: alpha first, then the
 * channels in reverse - the same flip the track's orange gets by hand above.
 */
internal fun kmlColor(rgb: String): String {
    val hex = rgb.removePrefix("#").lowercase()
    require(hex.length == 6) { "Expected #RRGGBB, got $rgb" }
    return "ff" + hex.substring(4, 6) + hex.substring(2, 4) + hex.substring(0, 2)
}
