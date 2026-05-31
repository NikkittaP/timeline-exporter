package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint

/**
 * Export to GeoJSON (RFC 7946) — the standard JSON interchange for geo data.
 * Useful for QGIS, Mapbox/MapLibre web maps, programmatic post-processing.
 *
 * Coordinate order per RFC 7946 §3.1.1: longitude FIRST, then latitude.
 *
 * Output is a FeatureCollection containing one Feature whose geometry is a
 * single LineString. Properties carry the track name and creator.
 */
object GeoJsonExporter : Exporter {
    override val displayName = "GeoJSON"
    override val fileExtension = "geojson"
    override val mimeType = "application/geo+json"

    private const val CREATOR = "Timeline Exporter for Android"

    override fun export(points: List<PathPoint>, trackName: String): String {
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
