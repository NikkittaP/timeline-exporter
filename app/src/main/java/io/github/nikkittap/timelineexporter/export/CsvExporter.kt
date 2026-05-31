package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.format.DateTimeFormatter

/**
 * Export to RFC 4180 CSV — one row per point, comma-separated.
 *
 * Uses CRLF line endings per the spec (Excel parses LF-only too, but CRLF
 * is the safest for cross-tool compatibility).
 *
 * No track name field — CSV has no standard way to attach metadata.
 * Columns: time_utc (ISO-8601), latitude, longitude.
 */
object CsvExporter : Exporter {
    override val displayName = "CSV"
    override val fileExtension = "csv"
    override val mimeType = "text/csv"

    override fun export(points: List<PathPoint>, trackName: String): String {
        val sb = StringBuilder(points.size * 50 + 30)
        sb.append("time_utc,latitude,longitude\r\n")
        for (p in points) {
            sb.append(DateTimeFormatter.ISO_INSTANT.format(p.timeUtc))
                .append(',').append(p.latitude)
                .append(',').append(p.longitude)
                .append("\r\n")
        }
        return sb.toString()
    }
}
