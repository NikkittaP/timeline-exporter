package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Export to RFC 4180 CSV — one row per point, or one row per place.
 *
 * Uses CRLF line endings per the spec (Excel parses LF-only too, but CRLF
 * is the safest for cross-tool compatibility).
 *
 * No track name field — CSV has no standard way to attach metadata.
 *
 * **Track columns:** time_utc (ISO-8601), latitude, longitude, time_local,
 * utc_offset.
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
 *
 * **Place columns:** name, latitude, longitude, visits, total_seconds, then
 * the first and last visit each as UTC / local / offset, then category,
 * address, place_id and google_maps_url. Wide, but a places CSV exists to be
 * sorted and pivoted, and a column that has to be reconstructed by hand is a
 * column that may as well not be there.
 *
 * Unlike the track rows, place rows contain free text — a user's own label for
 * a place, or a Google address with a comma in it — so every field goes
 * through [csvField] rather than being appended raw.
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

    override fun export(content: ExportContent, name: String): String = when (content) {
        is ExportContent.Track -> exportTrack(content.points)
        is ExportContent.Places -> exportPlaces(content.places)
    }

    private fun exportTrack(points: List<PathPoint>): String {
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

    private fun exportPlaces(places: List<ExportPlace>): String {
        val sb = StringBuilder(places.size * 220 + 200)
        sb.append("name,latitude,longitude,visits,total_seconds,")
        sb.append("first_visit_utc,first_visit_local,first_visit_offset,")
        sb.append("last_visit_utc,last_visit_local,last_visit_offset,")
        sb.append("category,address,place_id,google_maps_url\r\n")
        for (place in places) {
            sb.append(csvField(place.name))
                .append(',').append(place.latitude)
                .append(',').append(place.longitude)
                .append(',').append(place.visits)
                .append(',').append(place.totalSeconds)
                .append(',')
            appendMoment(sb, place.firstVisit, place.firstVisitOffsetMinutes)
            sb.append(',')
            appendMoment(sb, place.lastVisit, place.lastVisitOffsetMinutes)
            sb.append(',').append(csvField(place.category))
                .append(',').append(csvField(place.address))
                .append(',').append(csvField(place.placeId))
                .append(',').append(csvField(place.mapsUrl))
                .append("\r\n")
        }
        return sb.toString()
    }

    /** Three columns: the instant in UTC, the same instant local, the offset. */
    private fun appendMoment(sb: StringBuilder, instant: Instant, offsetMinutes: Int?) {
        sb.append(DateTimeFormatter.ISO_INSTANT.format(instant)).append(',')
        if (offsetMinutes != null) {
            val offset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60)
            sb.append(LOCAL_TIME_FORMAT.format(instant.atOffset(offset)))
                .append(',').append(formatOffset(offsetMinutes))
        } else {
            sb.append(',')
        }
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

/**
 * One CSV field, quoted per RFC 4180 §2.6-2.7 when it has to be.
 *
 * A place name is whatever the user typed, and a Google address routinely
 * contains a comma; either would otherwise split one row into several and
 * shift every column after it. Quoting only when needed keeps files written
 * from data that needs none byte-identical to the unquoted form.
 *
 * Null and blank become an empty field rather than the text "null".
 */
internal fun csvField(value: String?): String {
    if (value.isNullOrEmpty()) return ""
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}
