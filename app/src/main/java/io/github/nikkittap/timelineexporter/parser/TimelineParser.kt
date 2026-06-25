package io.github.nikkittap.timelineexporter.parser

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Thrown when a file is structurally readable (or even valid JSON) but is
 * clearly not a Google Timeline export — e.g. the user picked an unrelated
 * JSON file, an HTML/XML page, or a binary file. Surfaced to the UI as a
 * friendly "this isn't a Timeline file" message rather than a raw stack trace.
 */
class NotTimelineFileException(message: String) : Exception(message)

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
 * Parses a Google Maps Timeline / Location History export into a normalized
 * [ParsedTimeline].
 *
 * Google has shipped several incompatible JSON layouts over the years. This
 * parser auto-detects which one a file is and extracts GPS points from each:
 *
 *  - **Phone takeout** (`{ "semanticSegments": [...] }`): the current on-device
 *    export. Points come from `timelinePath`; `visit`/`activity` are counted.
 *    Falls back to `rawSignals` positions if no `timelinePath` points exist.
 *  - **Phone takeout, array variant** (`[ {...}, {...} ]`): same segments, but
 *    the top level is a bare array (seen on iOS exports).
 *  - **Semantic Location History** (`{ "timelineObjects": [...] }`): older
 *    Takeout monthly files. Points come from activity waypoints / raw paths and
 *    place-visit locations, using E7 integer coordinates.
 *  - **Records.json** (`{ "locations": [...] }`): the oldest Takeout format —
 *    raw GPS pings with E7 coordinates.
 *
 * Robustness rules applied throughout:
 *  - Unknown keys are ignored, so new fields Google adds never break the parse.
 *  - Malformed entries (bad coords, bad timestamps) are skipped individually
 *    instead of failing the whole file — real exports routinely contain junk.
 *  - Multiple coordinate encodings (degree strings, E7 integers, `geo:` URIs)
 *    and timestamp encodings (ISO-8601 with offset or `Z`, epoch millis/seconds)
 *    are all accepted.
 *
 * Returned [PathPoint]s are sorted ascending by time.
 *
 * @throws kotlinx.serialization.SerializationException if the file isn't valid
 *   JSON or doesn't match any known layout.
 */
fun parseTimeline(
    jsonText: String,
    onProgress: (ParserStage) -> Unit = {},
): ParsedTimeline {
    onProgress(ParserStage.DecodingJson)

    // Cheap structural sniff: a bare array can only be the iOS phone variant.
    // Everything else is an object whose populated top-level key tells us the
    // format. We still decode exactly once.
    val isTopLevelArray = jsonText.firstNonWhitespaceChar() == '['

    if (isTopLevelArray) {
        val segments = jsonParser.decodeFromString<List<RawSegment>>(jsonText)
        return extractFromSemanticSegments(
            segments = segments,
            rawSignals = null,
            format = TimelineFormat.PHONE_TAKEOUT_ARRAY,
            onProgress = onProgress,
        )
    }

    val root = jsonParser.decodeFromString<RawRoot>(jsonText)
    return when {
        root.semanticSegments != null -> extractFromSemanticSegments(
            segments = root.semanticSegments,
            rawSignals = root.rawSignals,
            format = TimelineFormat.PHONE_TAKEOUT,
            onProgress = onProgress,
        )

        root.timelineObjects != null ->
            extractFromTimelineObjects(root.timelineObjects, onProgress)

        root.locations != null ->
            extractFromLocations(root.locations, onProgress)

        // Phone export that somehow only carried rawSignals.
        root.rawSignals != null ->
            extractFromRawSignals(root.rawSignals, onProgress)

        else -> throw NotTimelineFileException(
            "Unrecognized Timeline format: none of semanticSegments, " +
                "timelineObjects, locations or rawSignals were present."
        )
    }
}

private const val PROGRESS_STRIDE = 100

/**
 * Configured once and reused.
 * - `ignoreUnknownKeys` so the parser doesn't choke when Google adds new
 *   fields we haven't modeled (forward-compatibility).
 * - `isLenient` to tolerate minor JSON quirks across export versions.
 */
private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// ---------- Per-format extractors ----------

/**
 * Phone-takeout extraction. Identical in behavior to the original parser for
 * the common case (points from `timelinePath`), with two additions: a per-point
 * time can be derived from `durationMinutesOffsetFromStartTime`, and if a file
 * yields no path points at all we fall back to `rawSignals` positions.
 */
private fun extractFromSemanticSegments(
    segments: List<RawSegment>,
    rawSignals: List<RawSignal>?,
    format: TimelineFormat,
    onProgress: (ParserStage) -> Unit,
): ParsedTimeline {
    val total = segments.size
    val points = mutableListOf<PathPoint>()
    var pathSegments = 0
    var visitSegments = 0
    var activitySegments = 0

    segments.forEachIndexed { index, segment ->
        if (index % PROGRESS_STRIDE == 0) {
            onProgress(ParserStage.ExtractingSegments(done = index, total = total))
        }
        val segmentStart = parseTimestamp(segment.startTime)
        when {
            segment.timelinePath != null -> {
                pathSegments++
                for (rawPoint in segment.timelinePath) {
                    val coords = parseCoordinatePair(rawPoint.point) ?: continue
                    val time = pointTime(rawPoint, segmentStart) ?: continue
                    points += PathPoint(time, coords.first, coords.second)
                }
            }
            segment.visit != null -> visitSegments++
            segment.activity != null -> activitySegments++
        }
    }
    onProgress(ParserStage.ExtractingSegments(done = total, total = total))

    // Fallback: a file with only raw signals (no semantic path) still has GPS.
    if (points.isEmpty() && rawSignals != null) {
        for (signal in rawSignals) {
            val pos = signal.position ?: continue
            val coords = pos.latLng?.let { parseCoordinatePair(it) } ?: continue
            val time = parseTimestamp(pos.timestamp) ?: continue
            points += PathPoint(time, coords.first, coords.second)
        }
    }

    return finalize(
        points = points,
        totalSegments = total,
        pathSegments = pathSegments,
        visitSegments = visitSegments,
        activitySegments = activitySegments,
        format = format,
        onProgress = onProgress,
    )
}

/** Resolve a timeline point's instant from either an explicit time or an offset. */
private fun pointTime(rawPoint: RawPathPoint, segmentStart: Instant?): Instant? {
    parseTimestamp(rawPoint.time)?.let { return it }
    val offsetMin = rawPoint.durationMinutesOffsetFromStartTime?.trim()?.toLongOrNull()
    if (offsetMin != null && segmentStart != null) {
        return segmentStart.plusSeconds(offsetMin * 60)
    }
    return null
}

/**
 * Takeout "Semantic Location History" extraction. Each timelineObject is either
 * an activitySegment (movement, with a waypoint/raw path) or a placeVisit
 * (a stationary location). All coordinates are E7 integers.
 */
private fun extractFromTimelineObjects(
    objects: List<RawTimelineObject>,
    onProgress: (ParserStage) -> Unit,
): ParsedTimeline {
    val total = objects.size
    val points = mutableListOf<PathPoint>()
    var pathSegments = 0
    var visitSegments = 0

    objects.forEachIndexed { index, obj ->
        if (index % PROGRESS_STRIDE == 0) {
            onProgress(ParserStage.ExtractingSegments(done = index, total = total))
        }
        obj.activitySegment?.let { seg ->
            pathSegments++
            val start = parseTimestamp(seg.duration?.startTimestamp ?: seg.duration?.startTimestampMs)
            val end = parseTimestamp(seg.duration?.endTimestamp ?: seg.duration?.endTimestampMs)

            // Prefer the timestamped raw path; fall back to waypoints + endpoints.
            val rawPathPoints = seg.simplifiedRawPath?.points.orEmpty()
            if (rawPathPoints.isNotEmpty()) {
                for (p in rawPathPoints) {
                    val coords = e7Pair(p.latitudeE7 ?: p.latE7, p.longitudeE7 ?: p.lngE7) ?: continue
                    val time = parseTimestamp(p.timestamp ?: p.timestampMs) ?: start ?: continue
                    points += PathPoint(time, coords.first, coords.second)
                }
            } else {
                start?.let { startLoc(seg.startLocation, it, points) }
                val waypoints = seg.waypointPath?.waypoints.orEmpty()
                val mid = start ?: end
                if (mid != null) {
                    for (w in waypoints) {
                        val coords = e7Pair(w.latitudeE7 ?: w.latE7, w.longitudeE7 ?: w.lngE7) ?: continue
                        points += PathPoint(mid, coords.first, coords.second)
                    }
                }
                end?.let { startLoc(seg.endLocation, it, points) }
            }
        }
        obj.placeVisit?.let { visit ->
            visitSegments++
            val time = parseTimestamp(visit.duration?.startTimestamp ?: visit.duration?.startTimestampMs)
            if (time != null) startLoc(visit.location, time, points)
        }
    }
    onProgress(ParserStage.ExtractingSegments(done = total, total = total))

    return finalize(
        points = points,
        totalSegments = total,
        pathSegments = pathSegments,
        visitSegments = visitSegments,
        activitySegments = 0,
        format = TimelineFormat.SEMANTIC_LOCATION_HISTORY,
        onProgress = onProgress,
    )
}

/** Add a single E7 location to [out] at [time] if its coordinates are valid. */
private fun startLoc(loc: RawE7Location?, time: Instant, out: MutableList<PathPoint>) {
    val coords = e7Pair(loc?.latitudeE7 ?: loc?.latE7, loc?.longitudeE7 ?: loc?.lngE7) ?: return
    out += PathPoint(time, coords.first, coords.second)
}

/**
 * Takeout "Records.json" extraction: a flat array of raw GPS pings, each with
 * E7 coordinates and a timestamp.
 */
private fun extractFromLocations(
    locations: List<RawLocationRecord>,
    onProgress: (ParserStage) -> Unit,
): ParsedTimeline {
    val total = locations.size
    val points = ArrayList<PathPoint>(total)

    locations.forEachIndexed { index, loc ->
        if (index % PROGRESS_STRIDE == 0) {
            onProgress(ParserStage.ExtractingSegments(done = index, total = total))
        }
        val coords = e7Pair(loc.latitudeE7, loc.longitudeE7) ?: return@forEachIndexed
        val time = parseTimestamp(loc.timestamp ?: loc.timestampMs) ?: return@forEachIndexed
        points += PathPoint(time, coords.first, coords.second)
    }
    onProgress(ParserStage.ExtractingSegments(done = total, total = total))

    return finalize(
        points = points,
        totalSegments = total,
        pathSegments = total,
        visitSegments = 0,
        activitySegments = 0,
        format = TimelineFormat.RECORDS,
        onProgress = onProgress,
    )
}

/** Phone export that only carried raw position signals. */
private fun extractFromRawSignals(
    signals: List<RawSignal>,
    onProgress: (ParserStage) -> Unit,
): ParsedTimeline {
    val total = signals.size
    val points = ArrayList<PathPoint>(total)

    signals.forEachIndexed { index, signal ->
        if (index % PROGRESS_STRIDE == 0) {
            onProgress(ParserStage.ExtractingSegments(done = index, total = total))
        }
        val pos = signal.position ?: return@forEachIndexed
        val coords = pos.latLng?.let { parseCoordinatePair(it) } ?: return@forEachIndexed
        val time = parseTimestamp(pos.timestamp) ?: return@forEachIndexed
        points += PathPoint(time, coords.first, coords.second)
    }
    onProgress(ParserStage.ExtractingSegments(done = total, total = total))

    return finalize(
        points = points,
        totalSegments = total,
        pathSegments = total,
        visitSegments = 0,
        activitySegments = 0,
        format = TimelineFormat.PHONE_TAKEOUT,
        onProgress = onProgress,
    )
}

/** Sort by time and assemble the [ParsedTimeline]. */
private fun finalize(
    points: MutableList<PathPoint>,
    totalSegments: Int,
    pathSegments: Int,
    visitSegments: Int,
    activitySegments: Int,
    format: TimelineFormat,
    onProgress: (ParserStage) -> Unit,
): ParsedTimeline {
    onProgress(ParserStage.Sorting)
    val sorted = points.sortedBy { it.timeUtc }
    return ParsedTimeline(
        pathPoints = sorted,
        totalSegments = totalSegments,
        pathSegments = pathSegments,
        visitSegments = visitSegments,
        activitySegments = activitySegments,
        format = format,
    )
}

// ---------- Coordinate / timestamp helpers ----------

/**
 * Strip the degree symbol (and an optional "geo:" URI prefix) and split
 * "41.284025°, 69.242256°" into (lat, lon). The "В" character covers a
 * real-world encoding artefact seen in some files (UTF-8 °  mis-decoded as
 * cp1251 → "В°").
 */
private val DEGREE_OR_ARTIFACT = Regex("[°В]")

internal fun parseCoordinatePair(text: String): Pair<Double, Double>? {
    val cleaned = text
        .removePrefix("geo:")
        .replace(DEGREE_OR_ARTIFACT, "")
    val parts = cleaned.split(",")
    if (parts.size != 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lon = parts[1].trim().toDoubleOrNull() ?: return null
    return lat to lon
}

/** Convert a pair of E7 integer coordinates to decimal degrees. */
internal fun e7Pair(latE7: Long?, lonE7: Long?): Pair<Double, Double>? {
    if (latE7 == null || lonE7 == null) return null
    return (latE7 / 1e7) to (lonE7 / 1e7)
}

/**
 * Parse a timestamp in any of the encodings Google has used:
 *  - ISO-8601 with offset, e.g. "2025-06-09T21:58:00.000+02:00"
 *  - ISO-8601 in UTC, e.g. "2024-06-15T14:21:29.460Z"
 *  - epoch milliseconds as a numeric string, e.g. "1718460089460"
 *  - epoch seconds as a numeric string, e.g. "1718460089"
 *
 * Returns null for unparseable strings so one bad row doesn't kill a big file.
 */
internal fun parseTimestamp(text: String?): Instant? {
    val t = text?.trim()
    if (t.isNullOrEmpty()) return null

    // Numeric epoch: ≥12 digits → milliseconds, otherwise seconds.
    t.toLongOrNull()?.let { num ->
        return if (t.length >= 12) Instant.ofEpochMilli(num) else Instant.ofEpochSecond(num)
    }

    try {
        return OffsetDateTime.parse(t).toInstant()
    } catch (_: DateTimeParseException) {
    }
    try {
        return Instant.parse(t)
    } catch (_: DateTimeParseException) {
    }
    return null
}

/** Kept for source/test compatibility; delegates to [parseTimestamp]. */
internal fun parseIsoInstant(text: String): Instant? = parseTimestamp(text)

private fun String.firstNonWhitespaceChar(): Char? {
    for (c in this) if (!c.isWhitespace()) return c
    return null
}
