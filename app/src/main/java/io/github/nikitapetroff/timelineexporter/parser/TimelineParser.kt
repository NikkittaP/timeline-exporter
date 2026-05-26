package io.github.nikitapetroff.timelineexporter.parser

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Parses a Google Maps Timeline JSON export (semanticSegments format) into a
 * normalized [ParsedTimeline].
 *
 * - Extracts GPS points from `timelinePath` segments (path-style data).
 * - Counts `visit` and `activity` segments for diagnostics. Their coordinates
 *   are NOT yet exported to [ParsedTimeline.pathPoints] — that will come when
 *   we add KML / bbox-filter support.
 * - Silently skips malformed entries (bad coords, bad timestamps) instead of
 *   failing the whole parse — Timeline.json files routinely contain a handful
 *   of garbage entries.
 *
 * Returned [PathPoint]s are sorted ascending by time.
 *
 * @throws kotlinx.serialization.SerializationException if the top-level JSON
 *   structure is fundamentally invalid (not an object, missing semanticSegments,
 *   or shape mismatch beyond what we tolerate).
 */
fun parseTimeline(jsonText: String): ParsedTimeline {
    val raw = jsonParser.decodeFromString<RawTimelineFile>(jsonText)

    val points = mutableListOf<PathPoint>()
    var pathSegments = 0
    var visitSegments = 0
    var activitySegments = 0

    for (segment in raw.semanticSegments) {
        when {
            segment.timelinePath != null -> {
                pathSegments++
                for (rawPoint in segment.timelinePath) {
                    val coords = parseCoordinatePair(rawPoint.point) ?: continue
                    val time = parseIsoInstant(rawPoint.time) ?: continue
                    points += PathPoint(time, coords.first, coords.second)
                }
            }
            segment.visit != null -> visitSegments++
            segment.activity != null -> activitySegments++
        }
    }

    return ParsedTimeline(
        pathPoints = points.sortedBy { it.timeUtc },
        totalSegments = raw.semanticSegments.size,
        pathSegments = pathSegments,
        visitSegments = visitSegments,
        activitySegments = activitySegments,
    )
}

/**
 * Configured once and reused.
 * - `ignoreUnknownKeys` so the parser doesn't choke when Google adds new
 *   fields we haven't modeled (forward-compatibility).
 */
private val jsonParser = Json {
    ignoreUnknownKeys = true
}

/**
 * Strip the degree symbol and split "41.284025°, 69.242256°" into (lat, lon).
 * The "В" character covers a real-world encoding artefact seen in some files
 * (UTF-8 °  mis-decoded as cp1251 → "В°").
 */
private val DEGREE_OR_ARTIFACT = Regex("[°В]")

internal fun parseCoordinatePair(text: String): Pair<Double, Double>? {
    val cleaned = text.replace(DEGREE_OR_ARTIFACT, "")
    val parts = cleaned.split(",")
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lon = parts[1].trim().toDoubleOrNull() ?: return null
    return lat to lon
}

/**
 * Parse "2025-06-09T21:58:00.000+02:00" -> UTC Instant.
 * Returns null for unparseable strings so one bad row doesn't kill a 60 MB file.
 */
internal fun parseIsoInstant(text: String): Instant? = try {
    OffsetDateTime.parse(text).toInstant()
} catch (_: DateTimeParseException) {
    null
}
