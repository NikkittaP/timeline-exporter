package io.github.nikkittap.timelineexporter.trips

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.SegmentKind
import io.github.nikkittap.timelineexporter.parser.localMinuteOfDay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One journey away from home: a weekend at the coast, two weeks in Norway.
 *
 * Not one of Google's activities, and not a home-to-work commute. Asked for on
 * the ballot as "a list of trips with start, end, time and distance" (seven
 * votes), and the unit people mean by it is the holiday.
 *
 * Detected rather than read. Google's own `timelineMemory` trips were measured
 * against the reference exports and rejected as the source: their
 * destinations are city IDs with no name or position in the file, a whole year
 * of them is missing from the 13-year export, and the most recent multi-day
 * trip is not there at all. The rule below finds every one of Google's
 * trips longer than three hours and the ones Google missed.
 *
 * [farthestLatitude]/[farthestLongitude] is the point furthest from home,
 * which is the closest thing to "where" a trip has without a place name.
 * [travelledMeters] sums Google's per-activity distances, the same figure the
 * movement breakdown uses, so the two agree.
 *
 * [openStart] / [openEnd]: the file begins or ends while the person is away,
 * so the real trip is longer than what is shown. [longAbsence]: away longer
 * than [LONG_ABSENCE_DAYS], which in the reference data is as likely to be a
 * move that Google had not yet re-labelled as home as it is a trip.
 */
data class Trip(
    val start: Instant,
    val end: Instant,
    val startOffsetMinutes: Int?,
    val endOffsetMinutes: Int?,
    val maxDistanceMeters: Double,
    val farthestLatitude: Double,
    val farthestLongitude: Double,
    val travelledMeters: Double,
    val placeCount: Int,
    /** Metres per movement group, longest first; drives the transport icons. */
    val movements: List<Pair<MovementGroup, Double>>,
    val openStart: Boolean,
    val openEnd: Boolean,
) {
    val durationSeconds: Long get() = end.epochSecond - start.epochSecond

    /** Shorter than a day: shown as a compact row. */
    val isDayTrip: Boolean get() = durationSeconds < SECONDS_PER_DAY

    val longAbsence: Boolean get() = durationSeconds > LONG_ABSENCE_DAYS * SECONDS_PER_DAY

    /** A stable identity for selection; two trips never share a start. */
    val key: Long get() = start.epochSecond

    fun covers(time: Instant): Boolean = time >= start && time <= end
}

/** What [detectTrips] found, or why it found nothing. */
sealed interface TripDetection {
    data class Found(val trips: List<Trip>) : TripDetection

    /** No home in the file, so "away" has no meaning. */
    data object NoHome : TripDetection
}

/** Further than this from home counts as away. Google's own trips start near 50 km. */
const val AWAY_RADIUS_METERS = 50_000.0

/** A return home shorter than this does not end a trip. */
const val MERGE_GAP_SECONDS = 3 * 3600L

/** Absences shorter than this are errands, not trips. */
const val MIN_TRIP_SECONDS = 3 * 3600L

const val LONG_ABSENCE_DAYS = 60L

private const val SECONDS_PER_DAY = 86_400L

private val HOME_TYPES = setOf("HOME", "INFERRED_HOME")

/**
 * Find the trips in [parsed].
 *
 * Home is the place of the most recent `HOME` / `INFERRED_HOME` visit at each
 * moment, so a move is followed once Google labels the new home. A file with
 * no such label (older layouts, `Records.json`) falls back to where the
 * person most often is between midnight and six; a file with neither has no
 * home and no trips.
 *
 * Positions are every visit's place at its start and end plus every track
 * point, in time order. A trip runs from the last position at home to the
 * first one back. Pieces separated by less than [MERGE_GAP_SECONDS] at home
 * are one trip, and anything shorter than [MIN_TRIP_SECONDS] is dropped.
 *
 * Linear in the number of points after one sort; well under a second for the
 * 284,000-point reference file, but still meant for a background thread.
 */
fun detectTrips(parsed: ParsedTimeline, fallbackZone: ZoneId = ZoneId.systemDefault()): TripDetection {
    val positions = positionsOf(parsed)
    if (positions.isEmpty()) return TripDetection.Found(emptyList())
    val homes = homesOf(parsed, positions, fallbackZone) ?: return TripDetection.NoHome

    val raw = ArrayList<Span>()
    var current: Span? = null
    var lastHome: Position? = null
    var homeIndex = 0
    for (p in positions) {
        while (homeIndex + 1 < homes.size && homes[homeIndex + 1].time <= p.time) homeIndex++
        val home = homes[homeIndex]
        val distance = haversineMeters(p.latitude, p.longitude, home.latitude, home.longitude)
        if (distance > AWAY_RADIUS_METERS) {
            val span = current ?: Span(from = lastHome ?: p, openStart = lastHome == null).also { current = it }
            span.to = p
            if (distance > span.maxDistance) {
                span.maxDistance = distance
                span.farthest = p
            }
        } else {
            current?.let {
                it.to = p
                raw += it
                current = null
            }
            lastHome = p
        }
    }
    current?.let {
        it.openEnd = true
        raw += it
    }

    val merged = ArrayList<Span>()
    for (span in raw) {
        val previous = merged.lastOrNull()
        if (previous != null && span.from.time.epochSecond - previous.to.time.epochSecond < MERGE_GAP_SECONDS) {
            previous.to = span.to
            previous.openEnd = span.openEnd
            if (span.maxDistance > previous.maxDistance) {
                previous.maxDistance = span.maxDistance
                previous.farthest = span.farthest
            }
        } else {
            merged += span
        }
    }

    val trips = merged
        .filter { it.to.time.epochSecond - it.from.time.epochSecond >= MIN_TRIP_SECONDS }
        .map { it.toTrip(parsed) }
    return TripDetection.Found(trips)
}

private class Position(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val offsetMinutes: Int?,
)

private class Span(var from: Position, val openStart: Boolean) {
    var to: Position = from
    var maxDistance: Double = 0.0
    var farthest: Position = from
    var openEnd: Boolean = false

    fun toTrip(parsed: ParsedTimeline): Trip {
        val start = from.time
        val end = to.time
        val inside = parsed.segments.filter { it.start >= start && it.end <= end }
        val movements = inside
            .filter { it.kind == SegmentKind.ACTIVITY }
            .groupBy { it.movement ?: MovementGroup.OTHER }
            .mapValues { (_, segs) -> segs.sumOf { it.distanceMeters ?: 0.0 } }
            .filterValues { it > 0.0 }
            .toList()
            .sortedByDescending { it.second }
        val places = inside
            .filter { it.kind == SegmentKind.VISIT }
            .mapNotNull { it.place }
            .filter { (it.hierarchyLevel ?: 0) == 0 }
            .mapNotNull { it.placeId?.takeIf { id -> id.isNotEmpty() } }
            .toSet()
        return Trip(
            start = start,
            end = end,
            startOffsetMinutes = from.offsetMinutes,
            endOffsetMinutes = to.offsetMinutes,
            maxDistanceMeters = maxDistance,
            farthestLatitude = farthest.latitude,
            farthestLongitude = farthest.longitude,
            travelledMeters = movements.sumOf { it.second },
            placeCount = places.size,
            movements = movements,
            openStart = openStart,
            openEnd = openEnd,
        )
    }
}

private class Home(val time: Instant, val latitude: Double, val longitude: Double)

private fun positionsOf(parsed: ParsedTimeline): List<Position> {
    val out = ArrayList<Position>(parsed.pathPoints.size + parsed.segments.size * 2)
    for (seg in parsed.segments) {
        if (seg.kind != SegmentKind.VISIT) continue
        val place = seg.place ?: continue
        out += Position(seg.start, place.latitude, place.longitude, seg.tzOffsetMinutes)
        out += Position(seg.end, place.latitude, place.longitude, seg.tzOffsetMinutes)
    }
    for (p in parsed.pathPoints) out += Position(p.timeUtc, p.latitude, p.longitude, p.tzOffsetMinutes)
    out.sortBy { it.time }
    return out
}

/**
 * Home over time, oldest first, or null when there is none to find.
 *
 * The first labelled home also stands for the time before it, so a file that
 * opens on a trip still measures it from somewhere.
 */
private fun homesOf(parsed: ParsedTimeline, positions: List<Position>, zone: ZoneId): List<Home>? {
    val labelled = parsed.segments
        .filter { it.kind == SegmentKind.VISIT && it.place?.semanticType?.uppercase() in HOME_TYPES }
        .mapNotNull { seg -> seg.place?.let { Home(seg.start, it.latitude, it.longitude) } }
        .sortedBy { it.time }
    if (labelled.isNotEmpty()) return labelled
    return nightHome(positions, zone)?.let { listOf(it) }
}

/**
 * Where the person most often is at night, for files that never say "home".
 *
 * Positions between 00:00 and 06:00 local, counted on a grid of about 5 km;
 * the busiest cell's average position is home. Crude, but it only has to be
 * right to within the 50 km that decides "away".
 */
private fun nightHome(positions: List<Position>, zone: ZoneId): Home? {
    val cells = HashMap<Long, DoubleArray>()
    for (p in positions) {
        if (localMinuteOfDay(p.time, p.offsetMinutes, zone) >= 6 * 60) continue
        val key = floor(p.latitude / 0.05).toLong() * 100_000L + floor(p.longitude / 0.05).toLong()
        val acc = cells.getOrPut(key) { DoubleArray(3) }
        acc[0] += p.latitude
        acc[1] += p.longitude
        acc[2] += 1.0
    }
    val best = cells.values.maxByOrNull { it[2] } ?: return null
    return Home(Instant.EPOCH, best[0] / best[2], best[1] / best[2])
}

internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
