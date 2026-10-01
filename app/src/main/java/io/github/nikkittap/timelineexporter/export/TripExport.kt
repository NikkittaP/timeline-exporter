package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.trips.Trip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * One trip ready to be written: the trip, its name, its colour and the track
 * points recorded during it.
 *
 * [points] are the filtered track's, so the step-2 filters (movement groups,
 * time of day, skip repeated) shape a trip's line exactly as they shape the
 * plain track. A trip whose points were all filtered away keeps its row in
 * the CSV and is left out of the formats that draw lines.
 *
 * [name] is the trip's local dates in ISO form, "2025-07-19 – 2025-08-01".
 * Not localized, like the CSV columns and the filenames: it is a label inside
 * a data file, and it sorts.
 */
data class ExportTrip(
    val trip: Trip,
    val points: List<PathPoint>,
    val name: String,
    val color: String,
)

/**
 * Pair every trip with its slice of [points] (sorted by time), its name and
 * its colour. The colour is the day colour of the trip's first local date, so
 * a trip is drawn in the same colour as its first day in `Color by day`.
 */
fun exportTrips(trips: List<Trip>, points: List<PathPoint>, fallbackZone: ZoneId = ZoneId.systemDefault()): List<ExportTrip> =
    trips.map { trip ->
        val from = lowerBound(points, trip.start)
        val to = lowerBound(points, trip.end.plusNanos(1))
        val startDate = localDate(trip.start, trip.startOffsetMinutes, fallbackZone)
        val endDate = localDate(trip.end, trip.endOffsetMinutes, fallbackZone)
        ExportTrip(
            trip = trip,
            points = points.subList(from, to),
            name = if (startDate == endDate) "$startDate" else "$startDate – $endDate",
            color = trackDayColor(startDate),
        )
    }

/** First index whose time is at or after [time]. */
private fun lowerBound(points: List<PathPoint>, time: Instant): Int {
    var lo = 0
    var hi = points.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].timeUtc < time) lo = mid + 1 else hi = mid
    }
    return lo
}

internal fun localDate(instant: Instant, offsetMinutes: Int?, zone: ZoneId): LocalDate =
    if (offsetMinutes != null) instant.atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toLocalDate()
    else instant.atZone(zone).toLocalDate()

/**
 * The one-line summary written into a trip's description. English and ASCII,
 * like [describe] for places, for the same reasons.
 */
internal fun describe(trip: ExportTrip): String {
    val t = trip.trip
    val days = t.durationSeconds / 86_400.0
    val length = if (days >= 1.0) String.format(Locale.ROOT, "%.1f days", days)
    else String.format(Locale.ROOT, "%.1f h", t.durationSeconds / 3600.0)
    val far = String.format(Locale.ROOT, "%.0f km from home", t.maxDistanceMeters / 1000)
    val about = if (t.travelledEstimated) "about " else ""
    val travelled = String.format(Locale.ROOT, "%s%.0f km travelled", about, t.travelledMeters / 1000)
    val places = if (t.placeCount == 1) "1 place" else "${t.placeCount} places"
    return "$length, $far, $travelled, $places"
}

/** Movement groups by distance, e.g. "FLYING;DRIVING;WALKING". */
internal fun movementList(trip: Trip): String = trip.movements.joinToString(";") { it.first.name }

/**
 * All of [points] as one span, for the line writers that take a day's span.
 * The date is never read on this path; only the indices are.
 */
internal fun wholeTrack(points: List<PathPoint>): TrackDaySpan = TrackDaySpan(LocalDate.MIN, 0, points.lastIndex)
