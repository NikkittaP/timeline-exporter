package io.github.nikitapetroff.timelineexporter.parser

import kotlinx.serialization.Serializable
import java.time.Instant

// ---------- Normalized model (what the rest of the app consumes) ----------

/**
 * A single GPS sample with a UTC timestamp.
 * Time is stored as Instant (a precise UTC moment) so downstream code never has
 * to think about timezones.
 */
data class PathPoint(
    val timeUtc: Instant,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Result of parsing a Timeline.json file.
 * pathPoints are sorted ascending by time and ready to feed into filters / exporters.
 * The segment counts are useful diagnostics for the UI ("Parsed 678 segments: ...").
 */
data class ParsedTimeline(
    val pathPoints: List<PathPoint>,
    val totalSegments: Int,
    val pathSegments: Int,
    val visitSegments: Int,
    val activitySegments: Int,
) {
    val isEmpty: Boolean get() = pathPoints.isEmpty()
}

// ---------- Raw model (mirrors the on-disk JSON) ----------
//
// @Serializable marks a class so kotlinx.serialization generates the
// JSON <-> object conversion code at compile time. All fields are nullable
// or have defaults because real Timeline.json files vary segment to segment.

@Serializable
internal data class RawTimelineFile(
    val semanticSegments: List<RawSegment> = emptyList(),
)

@Serializable
internal data class RawSegment(
    val startTime: String? = null,
    val endTime: String? = null,
    val timelinePath: List<RawPathPoint>? = null,
    val visit: RawVisit? = null,
    val activity: RawActivity? = null,
)

@Serializable
internal data class RawPathPoint(
    /** Looks like "41.284025°, 69.242256°". Note the degree symbols. */
    val point: String,
    /** ISO-8601 with offset, e.g. "2025-06-09T21:58:00.000+02:00". */
    val time: String,
)

@Serializable
internal data class RawVisit(
    val hierarchyLevel: Int? = null,
    val probability: Double? = null,
    val topCandidate: RawVisitCandidate? = null,
)

@Serializable
internal data class RawVisitCandidate(
    val placeId: String? = null,
    val semanticType: String? = null,
    val probability: Double? = null,
    val placeLocation: RawPlaceLocation? = null,
)

@Serializable
internal data class RawPlaceLocation(
    /** Same format as RawPathPoint.point. */
    val latLng: String,
)

@Serializable
internal data class RawActivity(
    val start: RawPlaceLocation? = null,
    val end: RawPlaceLocation? = null,
    val distanceMeters: Double? = null,
    val probability: Double? = null,
    val topCandidate: RawActivityCandidate? = null,
)

@Serializable
internal data class RawActivityCandidate(
    /** e.g. "IN_PASSENGER_VEHICLE", "WALKING", "CYCLING". */
    val type: String? = null,
    val probability: Double? = null,
)
