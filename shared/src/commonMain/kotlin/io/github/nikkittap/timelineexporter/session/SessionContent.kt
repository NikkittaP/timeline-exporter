package io.github.nikkittap.timelineexporter.session

import io.github.nikkittap.timelineexporter.export.ExportContent
import io.github.nikkittap.timelineexporter.export.ExportPlace
import io.github.nikkittap.timelineexporter.export.exportTrips
import io.github.nikkittap.timelineexporter.export.googleMapsUrl
import io.github.nikkittap.timelineexporter.export.localDate
import io.github.nikkittap.timelineexporter.export.trackDayColor
import io.github.nikkittap.timelineexporter.export.trackEpochDay
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.PlaceEntry
import io.github.nikkittap.timelineexporter.parser.formatCoordinates
import io.github.nikkittap.timelineexporter.parser.placeEntries
import io.github.nikkittap.timelineexporter.trips.Trip
import io.github.nikkittap.timelineexporter.trips.TripDetection
import io.github.nikkittap.timelineexporter.trips.haversineMeters
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

// Trips, places and the map's geometry for the iOS app, in the same
// plain-value style as TimelineSession.kt: epoch milliseconds, epoch days,
// strings, primitive arrays.

/**
 * One detected trip. [startEpochDay] / [endEpochDay] are the local dates where
 * it began and ended (LocalDate.toEpochDays), the dates the Android list shows.
 * [transport] names TransportMode entries, at most two, often none.
 */
class TripInfo(
    val key: Long,
    val startMillis: Long,
    val endMillis: Long,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val durationSeconds: Long,
    val isDayTrip: Boolean,
    val maxDistanceMeters: Double,
    val travelledMeters: Double,
    val travelledEstimated: Boolean,
    val placeCount: Int,
    val openStart: Boolean,
    val openEnd: Boolean,
    val longAbsence: Boolean,
    val transport: List<String>,
    /** The day colour of its first local date, as the trips export draws it. */
    val color: String,
) {
    /** Calendar days touched, "19 July - 1 August" being 14. */
    val dayCount: Int get() = (endEpochDay - startEpochDay + 1).toInt()
}

/** Every trip in the file, oldest first. [noHome]: no home to measure from. */
class TripList(val noHome: Boolean, val trips: List<TripInfo>)

/**
 * One place, as the Places list shows it. [name] is Google's name, or null:
 * the display name (user label, Home, Work, coordinates) is the app's to pick.
 */
class PlaceInfo(
    val key: String,
    val placeId: String?,
    val latitude: Double,
    val longitude: Double,
    val semanticType: String?,
    val name: String?,
    val address: String?,
    val hierarchyLevel: Int,
    val visits: Int,
    val firstVisitMillis: Long,
    val lastVisitMillis: Long,
    val totalSeconds: Long,
    val isHome: Boolean,
    val isWork: Boolean,
    /** "55.59462, 13.00655", the fallback name. */
    val coordinates: String,
    val mapsUrl: String,
)

/**
 * A track ready for the map: points in [latitudes] / [longitudes], cut into
 * pieces. Piece i runs from pieceStarts[i] to pieceStarts[i + 1] - 1 (the
 * last to the end) and is drawn in pieceColors[i], "" meaning the app's own
 * track colour. Thinned to a point budget; [sourcePoints] is the count before.
 */
class MapTrack(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val pieceStarts: IntArray,
    val pieceColors: List<String>,
    val sourcePoints: Int,
) {
    val pointCount: Int get() = latitudes.size
    val pieceCount: Int get() = pieceStarts.size
}

/** A jump longer than this is a gap (a flight, a missing week), not a line. */
const val MAP_GAP_METERS = 80_000.0

fun TimelineSession.tripList(): TripList = when (val detection = trips) {
    is TripDetection.Found -> TripList(false, detection.trips.sortedBy { it.start }.map { it.info(zone) })
    TripDetection.NoHome -> TripList(true, emptyList())
    null -> TripList(false, emptyList())
}

private fun Trip.info(zone: TimeZone): TripInfo {
    val startDate = localDate(start, startOffsetMinutes, zone)
    return TripInfo(
        key = key,
        startMillis = start.toEpochMilliseconds(),
        endMillis = end.toEpochMilliseconds(),
        startEpochDay = startDate.toEpochDays(),
        endEpochDay = localDate(end, endOffsetMinutes, zone).toEpochDays(),
        durationSeconds = durationSeconds,
        isDayTrip = isDayTrip,
        maxDistanceMeters = maxDistanceMeters,
        travelledMeters = travelledMeters,
        travelledEstimated = travelledEstimated,
        placeCount = placeCount,
        openStart = openStart,
        openEnd = openEnd,
        longAbsence = longAbsence,
        transport = transport.map { it.name },
        color = trackDayColor(startDate),
    )
}

/**
 * The trips touching the filter's date range (all without one), oldest first;
 * when [keys] is not empty, only those. "Touch", as on Android: a holiday that
 * began the day before the range is still the one the user is looking at.
 */
private fun TimelineSession.selectTrips(spec: FilterSpec, keys: LongArray): List<Trip> {
    val all = (trips as? TripDetection.Found)?.trips ?: return emptyList()
    val range = spec.toFilter().dateRange
    val wanted = keys.toSet()
    return all.filter { range == null || (it.end >= range.start && it.start <= range.endInclusive) }
        .filter { wanted.isEmpty() || it.key in wanted }
        .sortedBy { it.start }
}

/** Places visited in the filter's range and time of day, most visited first. */
fun TimelineSession.places(spec: FilterSpec, includeContainers: Boolean): List<PlaceInfo> {
    val filter = spec.toFilter()
    return timeline.placeEntries(filter.dateRange, includeContainers, filter.timeOfDay, zone).map { it.info() }
}

private fun PlaceEntry.info() = PlaceInfo(
    key = key,
    placeId = placeId,
    latitude = latitude,
    longitude = longitude,
    semanticType = semanticType,
    name = name?.takeIf { it.isNotBlank() },
    address = address,
    hierarchyLevel = hierarchyLevel,
    visits = visits,
    firstVisitMillis = firstVisit.toEpochMilliseconds(),
    lastVisitMillis = lastVisit.toEpochMilliseconds(),
    totalSeconds = totalSeconds,
    isHome = isHome,
    isWork = isWork,
    coordinates = formatCoordinates(latitude, longitude),
    mapsUrl = googleMapsUrl(latitude, longitude, placeId),
)

/**
 * Write the trips touching the range ([keys] picks some; empty is every one),
 * each with its slice of the filtered track. Returns how many were written.
 */
fun TimelineSession.exportTripsResult(
    spec: FilterSpec,
    keys: LongArray,
    format: ExportFormat,
    name: String,
    path: String,
): ExportResult = try {
    val chosen = selectTrips(spec, keys)
    if (chosen.isEmpty()) {
        ExportResult(0, null, null)
    } else {
        val trips = exportTrips(chosen, filteredPoints(spec), zone)
        writeExport(format, ExportContent.Trips(trips), name, path)
        ExportResult(trips.size, null, null)
    }
} catch (e: Throwable) {
    ExportResult(0, e::class.simpleName ?: "Throwable", e.message)
}

/**
 * Write the places of [places] for the filter. [names] maps a place key to
 * the name to write (the app resolves labels, Home and Work in its language);
 * a place missing from it is written under its coordinates.
 */
fun TimelineSession.exportPlacesResult(
    spec: FilterSpec,
    includeContainers: Boolean,
    names: Map<String, String>,
    format: ExportFormat,
    name: String,
    path: String,
): ExportResult = try {
    val filter = spec.toFilter()
    val entries = timeline.placeEntries(filter.dateRange, includeContainers, filter.timeOfDay, zone)
    if (entries.isEmpty()) {
        ExportResult(0, null, null)
    } else {
        val places = entries.map { e ->
            ExportPlace(
                name = names[e.key] ?: formatCoordinates(e.latitude, e.longitude),
                latitude = e.latitude,
                longitude = e.longitude,
                placeId = e.placeId,
                category = e.semanticType,
                address = e.address,
                visits = e.visits,
                firstVisit = e.firstVisit,
                lastVisit = e.lastVisit,
                firstVisitOffsetMinutes = e.firstVisitOffsetMinutes,
                lastVisitOffsetMinutes = e.lastVisitOffsetMinutes,
                totalSeconds = e.totalSeconds,
            )
        }
        writeExport(format, ExportContent.Places(places), name, path)
        ExportResult(places.size, null, null)
    }
} catch (e: Throwable) {
    ExportResult(0, e::class.simpleName ?: "Throwable", e.message)
}

/**
 * The filtered track for the map. [colorByDay] cuts it at local midnight and
 * gives each day its export colour; otherwise pieces carry "".
 */
fun TimelineSession.mapTrack(spec: FilterSpec, colorByDay: Boolean, budget: Int): MapTrack {
    val points = filteredPoints(spec)
    val runs = if (colorByDay) dayRuns(points) else listOf(Run(points, ""))
    return buildMapTrack(runs, budget)
}

/**
 * The trips' slices of the filtered track for the map, each in its trip's
 * colour. [keys] as for [exportTripsResult].
 */
fun TimelineSession.mapTrips(spec: FilterSpec, keys: LongArray, budget: Int): MapTrack {
    val chosen = selectTrips(spec, keys)
    if (chosen.isEmpty()) return buildMapTrack(emptyList(), budget)
    val trips = exportTrips(chosen, filteredPoints(spec), zone)
    return buildMapTrack(trips.map { Run(it.points, it.color) }, budget)
}

internal class Run(val points: List<PathPoint>, val color: String)

private fun TimelineSession.dayRuns(points: List<PathPoint>): List<Run> {
    val runs = ArrayList<Run>()
    var start = 0
    var day = Long.MIN_VALUE
    for ((i, p) in points.withIndex()) {
        val d = p.trackEpochDay(zone)
        if (i > 0 && d != day) {
            runs += Run(points.subList(start, i), trackDayColor(LocalDate.fromEpochDays(day)))
            start = i
        }
        day = d
    }
    if (start < points.size) runs += Run(points.subList(start, points.size), trackDayColor(LocalDate.fromEpochDays(day)))
    return runs
}

/**
 * Thin [runs] to about [budget] points (every n-th, each piece's ends kept)
 * and cut them further wherever consecutive points are a gap apart.
 */
internal fun buildMapTrack(runs: List<Run>, budget: Int): MapTrack {
    val total = runs.sumOf { it.points.size }
    val stride = if (budget > 0 && total > budget) (total + budget - 1) / budget else 1
    val lats = ArrayList<Double>(minOf(total, budget.coerceAtLeast(1) + runs.size * 2))
    val lons = ArrayList<Double>(lats.size)
    val starts = ArrayList<Int>()
    val colors = ArrayList<String>()
    for (run in runs) {
        val pts = run.points
        if (pts.isEmpty()) continue
        var prev: PathPoint? = null
        var pieceLength = 0
        fun cut() {
            starts += lats.size
            colors += run.color
            pieceLength = 0
        }
        cut()
        for ((i, p) in pts.withIndex()) {
            if (i % stride != 0 && i != pts.lastIndex) continue
            val last = prev
            if (last != null && haversineMeters(last.latitude, last.longitude, p.latitude, p.longitude) > MAP_GAP_METERS) {
                if (pieceLength < 2) {
                    // A piece of one point draws nothing; drop it.
                    lats.subList(lats.size - pieceLength, lats.size).clear()
                    lons.subList(lons.size - pieceLength, lons.size).clear()
                    starts.removeAt(starts.lastIndex)
                    colors.removeAt(colors.lastIndex)
                }
                cut()
            }
            lats += p.latitude
            lons += p.longitude
            pieceLength++
            prev = p
        }
        if (pieceLength < 2) {
            lats.subList(lats.size - pieceLength, lats.size).clear()
            lons.subList(lons.size - pieceLength, lons.size).clear()
            starts.removeAt(starts.lastIndex)
            colors.removeAt(colors.lastIndex)
        }
    }
    return MapTrack(lats.toDoubleArray(), lons.toDoubleArray(), starts.toIntArray(), colors, total)
}
