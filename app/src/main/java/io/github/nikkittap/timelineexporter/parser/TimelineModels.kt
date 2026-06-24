package io.github.nikkittap.timelineexporter.parser

import kotlinx.serialization.SerialName
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
 */
data class ParsedTimeline(
    val pathPoints: List<PathPoint>,
    val totalSegments: Int,
    val pathSegments: Int,
    val visitSegments: Int,
    val activitySegments: Int,
    val format: TimelineFormat = TimelineFormat.PHONE_TAKEOUT,
) {
    val isEmpty: Boolean get() = pathPoints.isEmpty()
}

// ---------- Raw model (mirrors the on-disk JSON) ----------
//
// @Serializable marks a class so kotlinx.serialization generates the
// JSON <-> object conversion code at compile time. All fields are nullable
// or have defaults because real export files vary wildly — segment to segment,
// version to version, and platform to platform. New keys Google adds are
// ignored (see `ignoreUnknownKeys` in the parser), so unknown future fields
// never break the parse.

/**
 * Forward/​backward-compatible top-level envelope. A given file populates at
 * most one of these collections; the parser inspects which is present to
 * decide how to extract points. Keeping them all optional on one class means
 * we decode the file exactly once regardless of format.
 */
@Serializable
internal data class RawRoot(
    // Phone takeout
    val semanticSegments: List<RawSegment>? = null,
    val rawSignals: List<RawSignal>? = null,
    // Takeout "Semantic Location History"
    val timelineObjects: List<RawTimelineObject>? = null,
    // Takeout "Records.json"
    val locations: List<RawLocationRecord>? = null,
)

// ----- Phone takeout: semanticSegments -----

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
    /** ISO-8601 with offset, e.g. "2025-06-09T21:58:00.000+02:00". May be absent. */
    val time: String? = null,
    /**
     * Some exports omit [time] and instead give a minute offset from the
     * segment's startTime. Stored as a string in Google's format.
     */
    val durationMinutesOffsetFromStartTime: String? = null,
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

// ----- Phone takeout: rawSignals (used as a fallback source of points) -----

@Serializable
internal data class RawSignal(
    val position: RawPosition? = null,
)

@Serializable
internal data class RawPosition(
    /** Note the capital-L key name in the JSON. Degree-string format. */
    @SerialName("LatLng") val latLng: String? = null,
    val timestamp: String? = null,
)

// ----- Takeout "Semantic Location History": timelineObjects -----

@Serializable
internal data class RawTimelineObject(
    val activitySegment: RawActivitySegment? = null,
    val placeVisit: RawPlaceVisit? = null,
)

@Serializable
internal data class RawActivitySegment(
    val startLocation: RawE7Location? = null,
    val endLocation: RawE7Location? = null,
    val duration: RawDuration? = null,
    val waypointPath: RawWaypointPath? = null,
    val simplifiedRawPath: RawSimplifiedRawPath? = null,
)

@Serializable
internal data class RawPlaceVisit(
    val location: RawE7Location? = null,
    val duration: RawDuration? = null,
)

/** E7 coordinates: integer degrees × 10^7. Two key spellings exist. */
@Serializable
internal data class RawE7Location(
    val latitudeE7: Long? = null,
    val longitudeE7: Long? = null,
    val latE7: Long? = null,
    val lngE7: Long? = null,
)

@Serializable
internal data class RawDuration(
    val startTimestamp: String? = null,
    val endTimestamp: String? = null,
    val startTimestampMs: String? = null,
    val endTimestampMs: String? = null,
)

@Serializable
internal data class RawWaypointPath(
    val waypoints: List<RawE7Location> = emptyList(),
)

@Serializable
internal data class RawSimplifiedRawPath(
    val points: List<RawSimplifiedPoint> = emptyList(),
)

@Serializable
internal data class RawSimplifiedPoint(
    val latE7: Long? = null,
    val lngE7: Long? = null,
    val latitudeE7: Long? = null,
    val longitudeE7: Long? = null,
    val timestampMs: String? = null,
    val timestamp: String? = null,
)

// ----- Takeout "Records.json": locations -----

@Serializable
internal data class RawLocationRecord(
    val latitudeE7: Long? = null,
    val longitudeE7: Long? = null,
    val timestamp: String? = null,
    val timestampMs: String? = null,
)
