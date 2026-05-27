package io.github.nikitapetroff.timelineexporter.parser

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Stages emitted by [parseTimeline] via its optional onProgress callback.
 * The UI (or test) decides what to do with them. Parsers stay UI-agnostic.
 */
sealed interface ParserStage {
    /** Tree-decoding the raw JSON. Opaque from our side — no sub-progress. */
    data object DecodingJson : ParserStage

    /** Walking the decoded segments. [done] of [total] processed so far. */
    data class ExtractingSegments(val done: Int, val total: Int) : ParserStage

    /** Final sort of path points by time. Usually fast. */
    data object Sorting : ParserStage
}

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
 * - Reports progress via [onProgress] at stage transitions and periodically
 *   inside the extraction loop. Default is a no-op.
 *
 * Returned [PathPoint]s are sorted ascending by time.
 *
 * @throws kotlinx.serialization.SerializationException if the top-level JSON
 *   structure is fundamentally invalid.
 */
fun parseTimeline(
    jsonText: String,
    onProgress: (ParserStage) -> Unit = {},
): ParsedTimeline {
    onProgress(ParserStage.DecodingJson)
    val raw = jsonParser.decodeFromString<RawTimelineFile>(jsonText)

    val totalSegments = raw.semanticSegments.size
    val points = mutableListOf<PathPoint>()
    var pathSegments = 0
    var visitSegments = 0
    var activitySegments = 0

    raw.semanticSegments.forEachIndexed { index, segment ->
        // Report every PROGRESS_STRIDE segments so we don't spam the UI thread.
        if (index % PROGRESS_STRIDE == 0) {
            onProgress(ParserStage.ExtractingSegments(done = index, total = totalSegments))
        }
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
    // Final 100% tick so the bar visibly completes before moving to Sorting.
    onProgress(ParserStage.ExtractingSegments(done = totalSegments, total = totalSegments))

    onProgress(ParserStage.Sorting)
    val sortedPoints = points.sortedBy { it.timeUtc }

    return ParsedTimeline(
        pathPoints = sortedPoints,
        totalSegments = totalSegments,
        pathSegments = pathSegments,
        visitSegments = visitSegments,
        activitySegments = activitySegments,
    )
}

private const val PROGRESS_STRIDE = 100

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
