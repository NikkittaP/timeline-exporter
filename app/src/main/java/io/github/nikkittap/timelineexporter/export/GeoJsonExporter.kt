package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint

/**
 * Export to GeoJSON (RFC 7946) — the standard JSON interchange for geo data.
 * Useful for QGIS, Mapbox/MapLibre web maps, programmatic post-processing.
 *
 * Coordinate order per RFC 7946 §3.1.1: longitude FIRST, then latitude.
 *
 * A track is a FeatureCollection containing one Feature whose geometry is a
 * single LineString - or, with `Color by day` on, one Feature per local day.
 * Places are a FeatureCollection of Point Features, one per place, each carrying the whole aggregate in its `properties` — which is the
 * point of choosing GeoJSON over the two XML formats: QGIS and pandas will
 * happily sort, filter and join on `visits` or `total_seconds` without anyone
 * having to parse a description string back apart.
 */
object GeoJsonExporter : Exporter {
    override val displayName = "GeoJSON"
    override val fileExtension = "geojson"
    override val mimeType = "application/geo+json"

    private const val CREATOR = "Timeline Exporter for Android"

    override fun export(content: ExportContent, name: String, out: Appendable) = when (content) {
        is ExportContent.Track -> {
            val days = if (content.colorByDay) trackDays(content.points, content.fallbackZone) else emptyList()
            if (days.isEmpty()) exportTrack(content.points, name, out)
            else exportTrackByDay(content.points, days, name, out)
        }
        is ExportContent.Places -> exportPlaces(content.places, name, out)
        is ExportContent.Trips -> exportTrips(content.trips, name, out)
    }

    /**
     * One Feature per trip, a LineString in the trip's simplestyle `stroke`,
     * with the trip's figures as properties so a table built from the file
     * has them as columns. Trips with no points left after filtering are left
     * out; the CSV still lists them.
     */
    private fun exportTrips(trips: List<ExportTrip>, documentName: String, sb: Appendable) {
        val drawn = trips.filter { it.points.isNotEmpty() }
        sb.append("{\n")
        sb.append("  \"type\": \"FeatureCollection\",\n")
        sb.append("  \"name\": ").append(jsonString(documentName)).append(",\n")
        sb.append("  \"features\": [\n")
        for ((i, trip) in drawn.withIndex()) {
            val t = trip.trip
            sb.append("    {\n")
            sb.append("      \"type\": \"Feature\",\n")
            sb.append("      \"properties\": {\n")
            sb.append("        \"name\": ").append(jsonString(trip.name)).append(",\n")
            sb.append("        \"start_time\": \"")
            appendIsoInstant(sb, t.start)
            sb.append("\",\n")
            sb.append("        \"end_time\": \"")
            appendIsoInstant(sb, t.end)
            sb.append("\",\n")
            sb.append("        \"duration_seconds\": ").append(t.durationSeconds.toString()).append(",\n")
            sb.append("        \"max_distance_km\": ").append(km(t.maxDistanceMeters)).append(",\n")
            sb.append("        \"travelled_km\": ").append(km(t.travelledMeters)).append(",\n")
            sb.append("        \"places\": ").append(t.placeCount.toString()).append(",\n")
            sb.append("        \"movements\": ").append(jsonString(movementList(t))).append(",\n")
            sb.append("        \"open_start\": ").append(t.openStart.toString()).append(",\n")
            sb.append("        \"open_end\": ").append(t.openEnd.toString()).append(",\n")
            sb.append("        \"stroke\": \"").append(trip.color).append("\",\n")
            sb.append("        \"stroke-width\": 4,\n")
            sb.append("        \"creator\": ").append(jsonString(CREATOR)).append('\n')
            sb.append("      },\n")
            sb.append("      \"geometry\": {\n")
            sb.append("        \"type\": \"LineString\",\n")
            sb.append("        \"coordinates\": [\n")
            appendPositions(trip.points, wholeTrack(trip.points), "          ", sb)
            sb.append("        ]\n")
            sb.append("      }\n")
            sb.append("    }")
            if (i < drawn.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
    }

    private fun km(meters: Double): String = String.format(java.util.Locale.ROOT, "%.1f", meters / 1000)

    private fun exportTrack(points: List<PathPoint>, trackName: String, sb: Appendable) {
        sb.append("{\n")
        sb.append("  \"type\": \"FeatureCollection\",\n")
        sb.append("  \"features\": [\n")
        sb.append("    {\n")
        sb.append("      \"type\": \"Feature\",\n")
        sb.append("      \"properties\": {\n")
        sb.append("        \"name\": ").append(jsonString(trackName)).append(",\n")
        sb.append("        \"creator\": ").append(jsonString(CREATOR)).append('\n')
        sb.append("      },\n")
        sb.append("      \"geometry\": {\n")
        sb.append("        \"type\": \"LineString\",\n")
        sb.append("        \"coordinates\": [")
        if (points.isNotEmpty()) sb.append('\n')
        for ((i, p) in points.withIndex()) {
            // [lon, lat] per RFC 7946
            sb.append("          [")
            appendCoordinate(sb, p.longitude)
            sb.append(", ")
            appendCoordinate(sb, p.latitude)
            sb.append(']')
            if (i < points.size - 1) sb.append(',')
            sb.append('\n')
        }
        if (points.isNotEmpty()) sb.append("        ") else sb.append(' ')
        sb.append("]\n")
        sb.append("      }\n")
        sb.append("    }\n")
        sb.append("  ]\n")
        sb.append("}\n")
    }

    /**
     * The track as one Feature per local day, coloured the way the map's
     * `Color by day` mode draws it.
     *
     * GeoJSON itself has no notion of style, so the colour travels in the
     * `stroke` / `stroke-width` properties of the simplestyle convention,
     * which geojson.io, GitHub's viewer and uMap honour, and everything else
     * simply keeps as data. `date` is there for the readers that ignore
     * style: filtering a day out of the file should not mean parsing a name.
     *
     * A day made of more than one run - see [trackDaySpans] - is a
     * MultiLineString, so each date is one Feature.
     */
    private fun exportTrackByDay(
        points: List<PathPoint>,
        days: List<TrackDay>,
        trackName: String,
        sb: Appendable,
    ) {
        sb.append("{\n")
        sb.append("  \"type\": \"FeatureCollection\",\n")
        sb.append("  \"name\": ").append(jsonString(trackName)).append(",\n")
        sb.append("  \"features\": [\n")
        for ((d, day) in days.withIndex()) {
            // No jsonString() around the date or the colour: digits, dashes
            // and a hash, none of which JSON asks to be escaped.
            val date = day.date.toString()
            sb.append("    {\n")
            sb.append("      \"type\": \"Feature\",\n")
            sb.append("      \"properties\": {\n")
            sb.append("        \"name\": \"").append(date).append("\",\n")
            sb.append("        \"date\": \"").append(date).append("\",\n")
            sb.append("        \"start_time\": \"")
            appendIsoInstant(sb, day.runs.minOf { points[it.first].timeUtc })
            sb.append("\",\n")
            sb.append("        \"end_time\": \"")
            appendIsoInstant(sb, day.runs.maxOf { points[it.last].timeUtc })
            sb.append("\",\n")
            sb.append("        \"stroke\": \"").append(day.color).append("\",\n")
            sb.append("        \"stroke-width\": 4,\n")
            sb.append("        \"creator\": ").append(jsonString(CREATOR)).append('\n')
            sb.append("      },\n")
            sb.append("      \"geometry\": {\n")
            if (day.runs.size == 1) {
                sb.append("        \"type\": \"LineString\",\n")
                sb.append("        \"coordinates\": [\n")
                appendPositions(points, day.runs.single(), "          ", sb)
                sb.append("        ]\n")
            } else {
                sb.append("        \"type\": \"MultiLineString\",\n")
                sb.append("        \"coordinates\": [\n")
                for ((r, run) in day.runs.withIndex()) {
                    sb.append("          [\n")
                    appendPositions(points, run, "            ", sb)
                    sb.append("          ]")
                    if (r < day.runs.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("        ]\n")
            }
            sb.append("      }\n")
            sb.append("    }")
            if (d < days.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
    }

    /** One `[lon, lat]` per line for the samples of [span], comma-separated. */
    private fun appendPositions(points: List<PathPoint>, span: TrackDaySpan, indent: String, sb: Appendable) {
        for (i in span.first..span.last) {
            val p = points[i]
            sb.append(indent).append('[')
            appendCoordinate(sb, p.longitude)
            sb.append(", ")
            appendCoordinate(sb, p.latitude)
            sb.append(']')
            if (i < span.last) sb.append(',')
            sb.append('\n')
        }
    }

    private fun exportPlaces(places: List<ExportPlace>, documentName: String, sb: Appendable) {
        sb.append("{\n")
        sb.append("  \"type\": \"FeatureCollection\",\n")
        sb.append("  \"name\": ").append(jsonString(documentName)).append(",\n")
        sb.append("  \"features\": [\n")
        for ((i, place) in places.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"type\": \"Feature\",\n")
            sb.append("      \"properties\": {\n")
            sb.append("        \"name\": ").append(jsonString(place.name)).append(",\n")
            sb.append("        \"visits\": ").append(place.visits.toString()).append(",\n")
            sb.append("        \"total_seconds\": ").append(place.totalSeconds.toString())
                .append(",\n")
            // No jsonString() around these: an ISO instant is digits, dashes,
            // colons and a Z, none of which JSON asks to be escaped.
            sb.append("        \"first_visit\": \"")
            appendIsoInstant(sb, place.firstVisit)
            sb.append("\",\n")
            sb.append("        \"last_visit\": \"")
            appendIsoInstant(sb, place.lastVisit)
            sb.append("\",\n")
            // null rather than an omitted key: a table built from these
            // features keeps the same columns for every row, which is what
            // QGIS and pandas both want.
            sb.append("        \"category\": ").append(jsonOrNull(place.category)).append(",\n")
            sb.append("        \"address\": ").append(jsonOrNull(place.address)).append(",\n")
            sb.append("        \"place_id\": ").append(jsonOrNull(place.placeId)).append(",\n")
            sb.append("        \"google_maps_url\": ").append(jsonString(place.mapsUrl)).append('\n')
            sb.append("      },\n")
            sb.append("      \"geometry\": {\n")
            sb.append("        \"type\": \"Point\",\n")
            sb.append("        \"coordinates\": [")
            appendCoordinate(sb, place.longitude)
            sb.append(", ")
            appendCoordinate(sb, place.latitude)
            sb.append("]\n")
            sb.append("      }\n")
            sb.append("    }")
            if (i < places.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
    }
}

/**
 * Minimal JSON string encoder — escapes the characters the JSON spec
 * requires inside a quoted string. Avoids pulling kotlinx.serialization
 * just to format a few strings.
 */
private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else -> if (c.code < 0x20) {
                sb.append("\\u").append("%04x".format(c.code))
            } else {
                sb.append(c)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

/** A JSON string, or the literal `null` for a missing or blank value. */
private fun jsonOrNull(s: String?): String =
    if (s.isNullOrBlank()) "null" else jsonString(s)
