package io.github.nikkittap.timelineexporter.trips

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.Segment
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
 * [maxDistanceMeters] is the greatest straight-line distance from home of any
 * position in the trip.
 *
 * [travelledMeters] is the distance the file's movement data accounts for:
 * Google's own figure for every activity that overlaps the trip, each stretch
 * of time counted once, plus the length of any track recorded outside both
 * activities and visits. That last part is usually zero; it matters in old
 * exports where Google kept the track but classified none of it (a 1,300 km
 * drive in September 2013 that came out as "19 km" before). When that part is
 * significant, or the file cuts the trip off, [travelledEstimated] is set and
 * the figure is an estimate rather than Google's number.
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
    val travelledEstimated: Boolean,
    val placeCount: Int,
    /** Metres per movement group, longest first; drives the transport icons. */
    val movements: List<Pair<MovementGroup, Double>>,
    val openStart: Boolean,
    val openEnd: Boolean,
    /** How the person mostly got around, longest first; see [transportOf]. */
    val transport: List<TransportMode> = emptyList(),
) {
    val durationSeconds: Long get() = end.epochSecond - start.epochSecond

    /** Shorter than a day: shown as a compact row. */
    val isDayTrip: Boolean get() = durationSeconds < SECONDS_PER_DAY

    val longAbsence: Boolean get() = durationSeconds > LONG_ABSENCE_DAYS * SECONDS_PER_DAY

    /** A stable identity for selection; two trips never share a start. */
    val key: Long get() = start.epochSecond

    fun covers(time: Instant): Boolean = time >= start && time <= end
}

/**
 * A means of transport worth an icon. Finer than [MovementGroup] where the
 * difference is visible at a glance (a train trip and a bus tour are not the
 * same holiday), coarser where it is not (a taxi is a car).
 */
enum class TransportMode { FLIGHT, CAR, TRAIN, BUS, BOAT, BICYCLE, WALK }

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

/** Within this of home is at the door, where a trip starts and ends. */
private const val AT_HOME_METERS = 1_000.0

/**
 * How far before leaving, or after coming back within [AWAY_RADIUS_METERS],
 * to look for the door. Long enough for a drive across the 50 km with stops,
 * short enough that a few days with friends nearby are not absorbed.
 */
private const val DOOR_SEARCH_SECONDS = 6 * 3600L

/**
 * Untyped track above this share of the activity total (and above
 * [ESTIMATE_MIN_METERS]) makes the trip's distance an estimate.
 */
private const val ESTIMATE_SHARE = 0.10
private const val ESTIMATE_MIN_METERS = 10_000.0

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
 * point, in time order. Being more than [AWAY_RADIUS_METERS] from home is
 * what makes a trip; pieces separated by less than [MERGE_GAP_SECONDS] back
 * inside that radius are one trip. A trip less than [MIN_TRIP_SECONDS]
 * beyond the radius is dropped, and the ends of the rest are moved out to
 * the door (see [reachDoor]).
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
    var lastHome = -1
    var homeIndex = 0
    val distances = DoubleArray(positions.size)
    for ((i, p) in positions.withIndex()) {
        while (homeIndex + 1 < homes.size && homes[homeIndex + 1].time <= p.time) homeIndex++
        val home = homes[homeIndex]
        val distance = haversineMeters(p.latitude, p.longitude, home.latitude, home.longitude)
        distances[i] = distance
        if (distance > AWAY_RADIUS_METERS) {
            val span = current
                ?: Span(positions, if (lastHome >= 0) lastHome else i, openStart = lastHome < 0).also { current = it }
            span.toIndex = i
            if (distance > span.maxDistance) {
                span.maxDistance = distance
                span.farthest = p
            }
        } else {
            current?.let {
                it.toIndex = i
                raw += it
                current = null
            }
            lastHome = i
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
            previous.toIndex = span.toIndex
            previous.openEnd = span.openEnd
            if (span.maxDistance > previous.maxDistance) {
                previous.maxDistance = span.maxDistance
                previous.farthest = span.farthest
            }
        } else {
            merged += span
        }
    }

    // The minimum is measured between the 50 km crossings, before the ends
    // move to the door. Measured door to door, an afternoon drive just past
    // the line became a trip: 145 on the 13-year file instead of 126.
    val trips = merged
        .filter { it.to.time.epochSecond - it.from.time.epochSecond >= MIN_TRIP_SECONDS }
        .onEach { reachDoor(it, distances) }
        .map { it.toTrip(parsed) }
    return TripDetection.Found(trips)
}

private class Position(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val offsetMinutes: Int?,
)

/**
 * Move a trip's ends from the 50 km circle out to the door.
 *
 * The circle decides whether someone is away, but it is a poor place for a
 * trip to begin or end: on the drive back from Norway the first position
 * within 50 km of Malmö is at Helsingborg, 45 minutes before home, so the
 * trip stopped there on the map and in every export. Each end now walks
 * outward to the nearest position within [AT_HOME_METERS] of home, if there
 * is one within [DOOR_SEARCH_SECONDS] before the trip goes away again.
 * Without one (a night at friends' 20 km off, a gap in the data) the end
 * stays where it was. An end cut off by the file stays too.
 *
 * Two neighbouring trips cannot overlap: one end looks for the first door
 * position and the other for the last, both in the same stretch at home.
 */
private fun reachDoor(span: Span, distances: DoubleArray) {
    val positions = span.positions
    if (!span.openStart) {
        val limit = positions[span.fromIndex].time.epochSecond - DOOR_SEARCH_SECONDS
        var i = span.fromIndex
        while (i >= 0 && positions[i].time.epochSecond >= limit && distances[i] <= AWAY_RADIUS_METERS) {
            if (distances[i] <= AT_HOME_METERS) {
                span.fromIndex = i
                break
            }
            i--
        }
    }
    if (!span.openEnd) {
        val limit = positions[span.toIndex].time.epochSecond + DOOR_SEARCH_SECONDS
        var i = span.toIndex
        while (i < positions.size && positions[i].time.epochSecond <= limit && distances[i] <= AWAY_RADIUS_METERS) {
            if (distances[i] <= AT_HOME_METERS) {
                span.toIndex = i
                break
            }
            i++
        }
    }
}

private class Span(val positions: List<Position>, var fromIndex: Int, val openStart: Boolean) {
    var toIndex: Int = fromIndex
    val from: Position get() = positions[fromIndex]
    val to: Position get() = positions[toIndex]
    var maxDistance: Double = 0.0
    var farthest: Position = from
    var openEnd: Boolean = false

    fun toTrip(parsed: ParsedTimeline): Trip {
        val start = from.time
        val end = to.time
        val inside = parsed.segments.filter { it.start >= start && it.end <= end }
        // Overlapping rather than inside: a trip begins at the last position
        // within 50 km of home, which on a drive is part-way through the
        // activity that took the person away. Counting only activities fully
        // inside dropped the journey out and back.
        // Each stretch of time once: see ParsedTimeline.distinctActivities.
        val activities = parsed.distinctActivities.filter { it.end > start && it.start < end }
        val activityMeters = activities.sumOf { it.distanceMeters ?: 0.0 }
        val transport = transportOf(activities, maxDistance)
        val untyped = untypedTrackMeters(parsed, start, end, activities)
        val movements = activities
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
            travelledMeters = activityMeters + untyped,
            travelledEstimated = openStart || openEnd ||
                (untyped > ESTIMATE_MIN_METERS && untyped > activityMeters * ESTIMATE_SHARE),
            placeCount = places.size,
            movements = movements,
            openStart = openStart,
            openEnd = openEnd,
            transport = transport,
        )
    }
}

/** Only a plane averages this over a leg of at least [FLIGHT_MIN_METERS]. */
private const val FLIGHT_MIN_KMH = 250.0
private const val FLIGHT_MIN_METERS = 150_000.0
private const val WALK_MAX_KMH = 15.0
private const val BICYCLE_MAX_KMH = 45.0

/** A mode earns an icon from this share of the trip's typed distance... */
private const val TRANSPORT_MIN_SHARE = 0.10

/** ...and at least this far, so the taxi to the hotel never does. */
private const val TRANSPORT_MIN_METERS = 25_000.0

/** A walk-only trip shows the walker from this far. */
private const val WALK_ONLY_MIN_METERS = 5_000.0

/** Typed distance below this multiple of the farthest point is too little to describe. */
private const val TRANSPORT_MIN_COVERAGE = 1.2

private const val MAX_TRANSPORT_ICONS = 2

/**
 * The one or two ways of travelling that carried most of the trip, or none
 * when the data cannot say.
 *
 * Measured on the 13-year reference file before writing this. Recent data is
 * clean, but older activities carry no confidence at all and are often
 * plainly mislabelled: 27,000 km of "walking" at up to 2,500 km/h, a 9,174 km
 * "car" journey to Florida, flights typed UNKNOWN. Taking the longest label
 * as given put the wrong main icon on 8 of 83 multi-day trips. So:
 *
 * - Physics first. A leg of 150 km or more averaging 250 km/h or more is a
 *   flight whatever it is labelled. Walking faster than 15 km/h and cycling
 *   faster than 45 km/h are dropped, and so is anything Google did not type.
 * - A mode counts from 10% of the typed distance and 25 km.
 * - Walking is shown only on a trip that was nothing else.
 * - Longest first, at most two. The reference data never produced a third.
 * - No icons at all when the typed distance is under 1.2 times the farthest
 *   point. That much movement does not reach the place and back, so most of
 *   the journey went unrecorded (September 2013: 19 km typed of ~1,300), and
 *   any icon would describe only the part that happened to be typed.
 */
internal fun transportOf(activities: List<Segment>, maxDistanceMeters: Double): List<TransportMode> {
    val byMode = HashMap<TransportMode, Double>()
    for (seg in activities) {
        val meters = seg.distanceMeters ?: continue
        val hours = (seg.end.epochSecond - seg.start.epochSecond).coerceAtLeast(60) / 3600.0
        val kmh = meters / 1000 / hours
        val mode = when {
            meters >= FLIGHT_MIN_METERS && kmh >= FLIGHT_MIN_KMH -> TransportMode.FLIGHT
            else -> when (val m = transportModeOf(seg.activityType)) {
                TransportMode.WALK -> m.takeIf { kmh <= WALK_MAX_KMH }
                TransportMode.BICYCLE -> m.takeIf { kmh <= BICYCLE_MAX_KMH }
                else -> m
            }
        } ?: continue
        byMode[mode] = (byMode[mode] ?: 0.0) + meters
    }
    val typed = byMode.values.sum()
    if (typed < maxDistanceMeters * TRANSPORT_MIN_COVERAGE) return emptyList()
    val ranked = byMode.entries.sortedByDescending { it.value }
    val shown = ranked
        .filter { it.key != TransportMode.WALK }
        .filter { it.value >= TRANSPORT_MIN_METERS && it.value >= typed * TRANSPORT_MIN_SHARE }
        .map { it.key }
        .take(MAX_TRANSPORT_ICONS)
    if (shown.isNotEmpty()) return shown
    val walked = byMode[TransportMode.WALK] ?: 0.0
    return if (walked >= WALK_ONLY_MIN_METERS) listOf(TransportMode.WALK) else emptyList()
}

/** Google's raw activity type as an icon, or null for types not worth one. */
internal fun transportModeOf(rawType: String?): TransportMode? = when (rawType?.uppercase()) {
    "FLYING" -> TransportMode.FLIGHT
    "IN_PASSENGER_VEHICLE", "IN_VEHICLE", "IN_TAXI", "DRIVING", "MOTORCYCLING", "IN_CAR" -> TransportMode.CAR
    "IN_TRAIN", "IN_SUBWAY", "IN_TRAM", "IN_FUNICULAR", "IN_CABLECAR" -> TransportMode.TRAIN
    "IN_BUS" -> TransportMode.BUS
    "IN_FERRY", "BOATING", "SAILING" -> TransportMode.BOAT
    "CYCLING", "ON_BICYCLE" -> TransportMode.BICYCLE
    "WALKING", "ON_FOOT", "RUNNING", "HIKING" -> TransportMode.WALK
    else -> null
}

private class Home(val time: Instant, val latitude: Double, val longitude: Double)

/**
 * Length of the track between [start] and [end] that no activity and no visit
 * covers: consecutive points both outside every such interval. Points inside a
 * visit are a phone at rest, and points inside an activity are already in
 * Google's figure for it, so neither is counted again.
 */
private fun untypedTrackMeters(
    parsed: ParsedTimeline,
    start: Instant,
    end: Instant,
    activities: List<Segment>,
): Double {
    val points = parsed.pathPoints
    var lo = 0
    var hi = points.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (points[mid].timeUtc < start) lo = mid + 1 else hi = mid
    }
    if (lo >= points.size || points[lo].timeUtc > end) return 0.0

    // Covered intervals, merged and in time order, walked with one cursor.
    val covered = (activities + parsed.segments.filter {
        it.kind == SegmentKind.VISIT && it.end >= start && it.start <= end
    }).map { it.start to it.end }.sortedBy { it.first }
    val merged = ArrayList<Pair<Instant, Instant>>()
    for (c in covered) {
        val last = merged.lastOrNull()
        if (last != null && c.first <= last.second) {
            if (c.second > last.second) merged[merged.lastIndex] = last.first to c.second
        } else {
            merged += c
        }
    }
    var cursor = 0
    fun isCovered(t: Instant): Boolean {
        while (cursor < merged.size && merged[cursor].second < t) cursor++
        return cursor < merged.size && merged[cursor].first <= t
    }

    var total = 0.0
    var previous: PathPoint? = null
    var previousFree = false
    var i = lo
    while (i < points.size && points[i].timeUtc <= end) {
        val p = points[i]
        val free = !isCovered(p.timeUtc)
        val prev = previous
        if (prev != null && free && previousFree) {
            total += haversineMeters(prev.latitude, prev.longitude, p.latitude, p.longitude)
        }
        previous = p
        previousFree = free
        i++
    }
    return total
}

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
