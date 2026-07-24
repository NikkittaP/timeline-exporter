package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Export to RFC 4180 CSV — one row per point, comma-separated.
 *
 * Uses CRLF line endings per the spec (Excel parses LF-only too, but CRLF
 * is the safest for cross-tool compatibility).
 *
 * No track name field — CSV has no standard way to attach metadata.
 *
 * Columns: time_utc (ISO-8601), latitude, longitude, time_local, utc_offset.
 *
 * `time_local` is the wall-clock time where the point was recorded, written as
 * "YYYY-MM-DD HH:MM:SS" — the shape Excel and LibreOffice recognise as a date
 * without any import wrangling. `utc_offset` keeps the row lossless (e.g.
 * "+02:00"), so the local column can be verified or recomputed.
 *
 * Both are empty when the export doesn't carry a timezone (Records.json and
 * other UTC-only formats): an empty cell is honest, a guessed one is not.
 *
 * The two columns are appended after the original three on purpose — anything
 * that already reads column 1..3 by position keeps working.
 */
object CsvExporter : Exporter {
    override val displayName = "CSV"
    override val fileExtension = "csv"
    override val mimeType = "text/csv"

    // Locale.ROOT on both formatters: the file is data, not UI. Under an
    // Arabic or Hindi locale a default-locale formatter would happily emit
    // Arabic-Indic digits and quietly produce a CSV no spreadsheet can read.
    private val LOCAL_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    override fun export(points: List<PathPoint>, trackName: String): String {
        val sb = StringBuilder(points.size * 70 + 60)
        sb.append("time_utc,latitude,longitude,time_local,utc_offset\r\n")
        for (p in points) {
            sb.append(DateTimeFormatter.ISO_INSTANT.format(p.timeUtc))
                .append(',').append(p.latitude)
                .append(',').append(p.longitude)
                .append(',')
            val minutes = p.tzOffsetMinutes
            if (minutes != null) {
                val offset = ZoneOffset.ofTotalSeconds(minutes * 60)
                sb.append(LOCAL_TIME_FORMAT.format(p.timeUtc.atOffset(offset)))
                    .append(',').append(formatOffset(minutes))
            } else {
                sb.append(',')
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Fixed-width "+HH:MM" / "-HH:MM". Not ZoneOffset.getId(), which collapses
     * zero to the bare letter "Z" — inconsistent with every other row and
     * useless to a spreadsheet.
     */
    private fun formatOffset(minutes: Int): String {
        val sign = if (minutes < 0) '-' else '+'
        val abs = abs(minutes)
        return String.format(Locale.ROOT, "%c%02d:%02d", sign, abs / 60, abs % 60)
    }
}
