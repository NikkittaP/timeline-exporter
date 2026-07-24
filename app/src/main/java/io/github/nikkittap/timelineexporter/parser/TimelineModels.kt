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
