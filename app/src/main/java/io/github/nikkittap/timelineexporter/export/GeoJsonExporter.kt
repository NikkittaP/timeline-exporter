package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.format.DateTimeFormatter

/**
 * Export to GeoJSON (RFC 7946) — the standard JSON interchange for geo data.
 * Useful for QGIS, Mapbox/MapLibre web maps, programmatic post-processing.
 *
 * Coordinate order per RFC 7946 §3.1.1: longitude FIRST, then latitude.
 *
 * A track is a FeatureCollection containing one Feature whose geometry is a
 * single LineString. Places are a FeatureCollection of Point Features, one per
 * place, each carrying the whole aggregate in its `properties` — which is the
 * point of choosing GeoJSON over the two XML formats: QGIS and pandas will
 * happily sort, filter and join on `visits` or `total_seconds` without anyone
 * having to parse a description string back apart.
 */
object GeoJsonExporter : Exporter {
    override val displayName = "GeoJSON"
    override val fileExtension = "geojson"
    override val mimeType = "application/geo+json"

    private const val CREATOR = "Timeline Exporter for Android"

    override fun export(content: ExportContent, name: String): String = when (content) {
        is ExportContent.Track -> exportTrack(content.points, name)
        is ExportContent.Places -> exportPlaces(content.places, name)
    }

    private fun exportTrack(points: List<PathPoint>, trackName: String): String {
        val sb = StringBuilder(points.size * 35 + 500)
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
            sb.append("          [").append(p.longitude).append(", ").append(p.latitude).append(']')
            if (i < points.size - 1) sb.append(',')
            sb.append('\n')
        }
        if (points.isNotEmpty()) sb.append("        ") else sb.append(' ')
        sb.append("]\n")
        sb.append("      }\n")
        sb.append("    }\n")
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun exportPlaces(places: List<ExportPlace>, documentName: String): String {
        val sb = StringBuilder(places.size * 400 + 500)
        sb.append("{\n")
        sb.append("  \"type\": \"FeatureCollection\",\n")
        sb.append("  \"name\": ").append(jsonString(documentName)).append(",\n")
        sb.append("  \"features\": [\n")
        for ((i, place) in places.withIndex()) {
            sb.append("    {\n")
            sb.append("      \"type\": \"Feature\",\n")
            sb.append("      \"properties\": {\n")
            sb.append("        \"name\": ").append(jsonString(place.name)).append(",\n")
            sb.append("        \"visits\": ").append(place.visits).append(",\n")
            sb.append("        \"total_seconds\": ").append(place.totalSeconds).append(",\n")
            sb.append("        \"first_visit\": ")
                .append(jsonString(DateTimeFormatter.ISO_INSTANT.format(place.firstVisit)))
                .append(",\n")
            sb.append("        \"last_visit\": ")
                .append(jsonString(DateTimeFormatter.ISO_INSTANT.format(place.lastVisit)))
                .append(",\n")
            // null rather than an omitted key: a table built from these
            // features keeps the same columns for every row, which is what
            // QGIS and pandas both want.
            sb.append("        \"category\": ").append(jsonOrNull(place.category)).append(",\n")
            sb.append("        \"address\": ").append(jsonOrNull(place.address)).append(",\n")
            sb.append("        \"place_id\": ").append(jsonOrNull(place.placeId)).append(",\n")
            sb.append("        \"google_maps_url\": ").append(jsonOrNull(place.mapsUrl)).append('\n')
            sb.append("      },\n")
            sb.append("      \"geometry\": {\n")
            sb.append("        \"type\": \"Point\",\n")
            sb.append("        \"coordinates\": [")
                .append(place.longitude).append(", ").append(place.latitude).append("]\n")
            sb.append("      }\n")
            sb.append("    }")
            if (i < places.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
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
