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

    override fun export(content: ExportContent, name: String, out: Appendable) = when (content) {
        is ExportContent.Track -> exportTrack(content.points, out)
        is ExportContent.Places -> exportPlaces(content.places, out)
        is ExportContent.Trips -> exportTrips(content.trips, out)
    }

    /**
     * One row per trip: the list the ballot asked for ("start, end, time and
     * distance"), ready for a spreadsheet. Every trip is listed, including one
     * whose points the filters removed, because its dates and distances are
     * still true. `movements` is the groups by distance, separated by `;`.
     */
    private fun exportTrips(trips: List<ExportTrip>, sb: Appendable) {
        sb.append("name,start_utc,start_local,start_offset,end_utc,end_local,end_offset,")
        sb.append("duration_seconds,max_distance_km,travelled_km,places,movements,")
        sb.append("farthest_latitude,farthest_longitude,open_start,open_end,points\r\n")
        for (trip in trips) {
            val t = trip.trip
            sb.append(csvField(trip.name)).append(',')
            appendMoment(sb, t.start, t.startOffsetMinutes)
            sb.append(',')
            appendMoment(sb, t.end, t.endOffsetMinutes)
            sb.append(',').append(t.durationSeconds.toString())
            sb.append(',').append(String.format(Locale.ROOT, "%.1f", t.maxDistanceMeters / 1000))
            sb.append(',').append(String.format(Locale.ROOT, "%.1f", t.travelledMeters / 1000))
            sb.append(',').append(t.placeCount.toString())
            sb.append(',').append(csvField(movementList(t)))
            sb.append(',')
            appendCoordinate(sb, t.farthestLatitude)
            sb.append(',')
            appendCoordinate(sb, t.farthestLongitude)
            sb.append(',').append(t.openStart.toString())
            sb.append(',').append(t.openEnd.toString())
            sb.append(',').append(trip.points.size.toString())
            sb.append("\r\n")
        }
    }

    private fun exportTrack(points: List<PathPoint>, sb: Appendable) {
        sb.append("time_utc,latitude,longitude,time_local,utc_offset\r\n")
        for (p in points) {
            appendIsoInstant(sb, p.timeUtc)
            sb.append(',')
            appendCoordinate(sb, p.latitude)
            sb.append(',')
            appendCoordinate(sb, p.longitude)
            sb.append(',')
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
    }

    private fun exportPlaces(places: List<ExportPlace>, sb: Appendable) {
        sb.append("name,latitude,longitude,visits,total_seconds,")
        sb.append("first_visit_utc,first_visit_local,first_visit_offset,")
        sb.append("last_visit_utc,last_visit_local,last_visit_offset,")
        sb.append("category,address,place_id,google_maps_url\r\n")
        for (place in places) {
            sb.append(csvField(place.name)).append(',')
            appendCoordinate(sb, place.latitude)
            sb.append(',')
            appendCoordinate(sb, place.longitude)
            sb.append(',').append(place.visits.toString())
                .append(',').append(place.totalSeconds.toString())
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
    }

    /** Three columns: the instant in UTC, the same instant local, the offset. */
    private fun appendMoment(sb: Appendable, instant: Instant, offsetMinutes: Int?) {
        appendIsoInstant(sb, instant)
        sb.append(',')
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
