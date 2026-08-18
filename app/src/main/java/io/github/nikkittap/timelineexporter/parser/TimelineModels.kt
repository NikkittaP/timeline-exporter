package io.github.nikkittap.timelineexporter.parser

import java.time.Instant

// ---------- Normalized model (what the rest of the app consumes) ----------
//
// The on-disk JSON is read by a streaming pull-parser (see TimelineParser),
// which extracts points token-by-token and discards each raw segment as soon
// as it has been processed. That means there are no @Serializable mirror
// classes here any more: nothing ever holds the whole file as an object tree,
// so memory stays proportional to the extracted points rather than to the
// file size. This is what lets very large exports load without OOM.

/**
 * A single GPS sample with a UTC timestamp.
 * Time is stored as Instant (a precise UTC moment) so downstream code never has
 * to think about timezones.
 *
 * [tzOffsetMinutes] is the offset from UTC that was in effect *where and when
 * the point was recorded* (e.g. 120 for +02:00), when the export carries it —
 * either in the ISO timestamp itself or in the segment's
 * `startTimeTimezoneUtcOffsetMinutes`. It is null for formats that only store
 * UTC (`Z` timestamps, epoch millis), and consumers must treat it as "unknown"
 * rather than assuming UTC: only CSV uses it, to add a human-readable local
 * time column next to the UTC one.
 */
data class PathPoint(
    val timeUtc: Instant,
    val latitude: Double,
    val longitude: Double,
    val tzOffsetMinutes: Int? = null,
)

/**
 * What a [Segment] turned out to describe.
 *
 * In a phone takeout these are mutually exclusive: measured over two real
 * exports (5432 and 4835 segments), not one segment carried more than one of
 * `timelinePath` / `activity` / `visit`. The enum encodes that fact rather
 * than pretending a segment can be several things at once.
 */
enum class SegmentKind {
    /** Carries `timelinePath`: the only kind with GPS points in it. */
    PATH,

    /** A trip: type of movement, distance, endpoints — but no track. */
    ACTIVITY,

    /** Time spent at a place. */
    VISIT,

    /** Anything else (e.g. `timelineMemory`), kept only so counts add up. */
    OTHER,
}

/**
 * Coarse grouping of Google's activity types, for filtering and for the
 * per-type distance breakdown.
 *
 * Google's raw string is kept on the segment; this is the bucket the UI shows.
 * Grouping rather than listing every type keeps the filter to a handful of
 * rows — the observed vocabulary alone is ten types, and Google adds more over
 * time. An unrecognized type lands in [OTHER] instead of disappearing.
 */
enum class MovementGroup {
    WALKING,
    CYCLING,
    DRIVING,
    TRANSIT,
    FLYING,
    OTHER;

    companion object {
        /** Bucket for Google's `activity.topCandidate.type`. */
        fun of(rawType: String?): MovementGroup = when (rawType?.uppercase()) {
            "WALKING", "ON_FOOT", "RUNNING", "HIKING" -> WALKING
            "CYCLING", "ON_BICYCLE" -> CYCLING
            "IN_PASSENGER_VEHICLE", "DRIVING", "IN_VEHICLE", "MOTORCYCLING",
            "IN_TAXI",
            -> DRIVING
            "IN_BUS", "IN_TRAIN", "IN_SUBWAY", "IN_TRAM", "IN_FERRY",
            "IN_CABLECAR", "IN_FUNICULAR",
            -> TRANSIT
            "FLYING" -> FLYING
            else -> OTHER
        }
    }
}

/**
 * One row of the per-movement breakdown: how far, over how many trips.
 *
 * [distanceMeters] is the sum of Google's own figures, not a total measured
 * off the track — see [Segment.distanceMeters] for why that matters.
 */
data class MovementStats(
    val group: MovementGroup,
    val trips: Int,
    val distanceMeters: Double,
)

/**
 * A place Google matched a visit to.
 *
 * [probability] is Google's confidence in the visit itself. It is worth
 * surfacing but not worth filtering on by default: only 3% of visits in the
 * sample export scored below 0.5, so a confidence threshold changes almost
 * nothing while looking like it does something.
 *
 * [hierarchyLevel] is 0 for a place and 1 for a container it sits inside (a
 * shop and its mall, say), which is why the same stay can appear twice.
 */
data class Place(
    val placeId: String?,
    val semanticType: String?,
    val latitude: Double,
    val longitude: Double,
    val probability: Double? = null,
    val hierarchyLevel: Int? = null,
)

/**
 * One entry from the export, normalized.
 *
 * The important structural fact, and the reason this type exists: **movement
 * type and GPS track live in different segments.** A PATH segment has points
 * and no activity label; an ACTIVITY segment has a label, a distance and its
 * endpoints, but no track. To answer "show me only the cycling", points have
 * to be joined to activities by time.
 *
 * That join is exact. Measured on a year of real data: activity intervals
 * never overlap each other, and every single path point falls inside either an
 * activity interval (29%) or a visit interval (71%) — no gaps, no ambiguity.
 *
 * [distanceMeters] is Google's own figure for an activity. Prefer it over
 * summing haversine distances between points: the sample export's track adds
 * up to 14 986 km against Google's 14 501 km, and the difference is not error
 * to be cleaned up — the track is a downsampled version of the trip, with a
 * median gap of seven minutes between points.
 */
data class Segment(
    val kind: SegmentKind,
    val start: Instant,
    val end: Instant,
    /** Non-empty only for [SegmentKind.PATH]. */
    val points: List<PathPoint> = emptyList(),
    /** Google's raw activity type, e.g. "IN_PASSENGER_VEHICLE". */
    val activityType: String? = null,
    /** Google's own distance for an activity, in metres. */
    val distanceMeters: Double? = null,
    /** Confidence in [activityType] (`topCandidate.probability`). */
    val activityProbability: Double? = null,
    val place: Place? = null,
    val tzOffsetMinutes: Int? = null,
) {
    /** Bucket for [activityType]; null for anything that isn't an activity. */
    val movement: MovementGroup?
        get() = if (kind == SegmentKind.ACTIVITY) MovementGroup.of(activityType) else null

    /** True when [time] falls inside this segment's half-open-ish range. */
    fun covers(time: Instant): Boolean = time >= start && time <= end
}

/**
 * Which on-disk layout a file turned out to be. Google has shipped several
 * incompatible "Timeline" / "Location History" formats over the years; we
 * detect and support all of them, and surface which one was used for
 * diagnostics in the UI.
 */
enum class TimelineFormat {
    /** Phone export: top-level object with `semanticSegments` / `rawSignals`. */
    PHONE_TAKEOUT,

    /** Phone export, iOS array variant: top-level is a bare array of segments. */
    PHONE_TAKEOUT_ARRAY,

    /** Takeout "Semantic Location History" monthly files: `timelineObjects`. */
    SEMANTIC_LOCATION_HISTORY,

    /** Takeout "Records.json": flat `locations` array of raw GPS pings. */
    RECORDS,

    /** Couldn't be matched to any known layout. */
    UNKNOWN,
}

/**
 * Result of parsing a Timeline export.
 * pathPoints are sorted ascending by time and ready to feed into filters / exporters.
 * The segment counts are useful diagnostics for the UI ("Parsed 678 segments: ...").
 *
 * [pathPoints] and [segments] are produced in the same pass and share the very
 * same [PathPoint] objects — a point reachable through a PATH segment is the
 * identical instance stored in the flat list, not a copy. The flat list costs
 * one extra reference per point (~2 MB on the largest export seen in the wild)
 * and is kept because every existing filter and exporter consumes it.
 *
 * [segments] is sorted ascending by start time and may be empty for formats
 * that carry no segment structure at all.
 */
data class ParsedTimeline(
    val pathPoints: List<PathPoint>,
    val totalSegments: Int,
    val pathSegments: Int,
    val visitSegments: Int,
    val activitySegments: Int,
    val format: TimelineFormat = TimelineFormat.PHONE_TAKEOUT,
    val segments: List<Segment> = emptyList(),
) {
    val isEmpty: Boolean get() = pathPoints.isEmpty()

    // Lazy rather than `get() =`: Compose reads these during recomposition, and
    // a plain getter would re-scan tens of thousands of segments every frame.

    /** Activity segments only, in time order. */
    val activities: List<Segment> by lazy { segments.filter { it.kind == SegmentKind.ACTIVITY } }

    /** Visit segments only, in time order. One stay may appear more than once. */
    val visits: List<Segment> by lazy { segments.filter { it.kind == SegmentKind.VISIT } }

    /**
     * Total distance per movement group, in metres, from Google's own
     * `distanceMeters`. Activities missing a distance contribute nothing
     * rather than being estimated from the track.
     */
    fun distanceByMovement(): Map<MovementGroup, Double> =
        segments.asSequence()
            .filter { it.kind == SegmentKind.ACTIVITY }
            .mapNotNull { seg -> seg.distanceMeters?.let { (seg.movement ?: MovementGroup.OTHER) to it } }
            .groupingBy { it.first }
            .fold(0.0) { acc, (_, meters) -> acc + meters }

    /**
     * Per-movement totals for the breakdown shown next to the filter, longest
     * distance first, groups with nothing in them omitted.
     *
     * [range] narrows the summary to activities that start inside it, so the
     * numbers track whatever date range the user has chosen rather than always
     * describing the whole file.
     */
    fun movementBreakdown(range: ClosedRange<Instant>? = null): List<MovementStats> =
        segments.asSequence()
            .filter { it.kind == SegmentKind.ACTIVITY }
            .filter { range == null || it.start in range }
            .groupBy { it.movement ?: MovementGroup.OTHER }
            .map { (group, group_) ->
                MovementStats(
                    group = group,
                    trips = group_.size,
                    distanceMeters = group_.sumOf { it.distanceMeters ?: 0.0 },
                )
            }
            .sortedByDescending { it.distanceMeters }

    /** Number of distinct places visited within [range]. */
    fun placeCount(range: ClosedRange<Instant>? = null): Int =
        segments.asSequence()
            .filter { it.kind == SegmentKind.VISIT }
            .filter { range == null || it.start in range }
            .mapNotNull { it.place?.placeId }
            .filter { it.isNotEmpty() }
            .toSet()
            .size

    /**
     * Distinct places, most-visited first, with a visit count each.
     *
     * Deduplicated by `placeId`: the sample export records 1071 visits against
     * only 313 distinct places, so counting raw visits would report the same
     * café 40 times over. Visits Google could not match to a place are
     * dropped, since there is no identity to group them on.
     */
    fun placesByVisitCount(): List<Pair<Place, Int>> =
        segments.asSequence()
            .filter { it.kind == SegmentKind.VISIT }
            .mapNotNull { it.place }
            .filter { !it.placeId.isNullOrEmpty() }
            .groupBy { it.placeId }
            .map { (_, group) -> group.first() to group.size }
            .sortedByDescending { it.second }
}
