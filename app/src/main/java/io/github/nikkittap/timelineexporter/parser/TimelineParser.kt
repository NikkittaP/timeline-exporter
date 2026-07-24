package io.github.nikkittap.timelineexporter.parser

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.io.InputStream
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
    /** Pulling JSON tokens from the stream. */
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
 * The parser is fully STREAMING: it uses a Jackson pull-parser to read one
 * JSON token at a time and extract GPS points as it goes, discarding each raw
 * segment immediately. The whole file is never materialized as a String or as
 * an object tree, so peak memory is proportional to the number of extracted
 * points — not to the file size. This is what lets multi-hundred-MB exports
 * load without an OutOfMemoryError.
 *
 * Google has shipped several incompatible JSON layouts over the years; this
 * parser auto-detects and supports all of them:
 *
 *  - **Phone takeout** (`{ "semanticSegments": [...] }`): the current on-device
 *    export. Points come from `timelinePath`; `visit`/`activity` are counted.
 *    Falls back to `rawSignals` positions only if no path points exist.
 *  - **Phone takeout, array variant** (`[ {...}, {...} ]`): same segments, bare
 *    top-level array (seen on iOS exports).
 *  - **Semantic Location History** (`{ "timelineObjects": [...] }`): older
 *    Takeout monthly files, E7 integer coordinates.
 *  - **Records.json** (`{ "locations": [...] }`): the oldest Takeout format.
 *
 * Robustness rules:
 *  - Unknown keys are skipped, so new fields Google adds never break the parse.
 *  - Malformed entries (bad coords/timestamps) are skipped individually instead
 *    of failing the whole file.
 *  - Multiple coordinate encodings (degree strings, E7 integers, `geo:` URIs)
 *    and timestamp encodings (ISO-8601 with offset or `Z`, epoch millis/seconds)
 *    are all accepted.
 *
 * Returned [PathPoint]s are sorted ascending by time.
 *
 * @throws NotTimelineFileException if the file matches no known layout.
 * @throws com.fasterxml.jackson.core.JsonProcessingException if it isn't valid JSON.
 */

private const val PROGRESS_STRIDE = 100

private val jsonFactory: JsonFactory = JsonFactory()

/** Parse from an in-memory string (used by tests and small shared text). */
fun parseTimeline(jsonText: String, onProgress: (ParserStage) -> Unit = {}): ParsedTimeline =
    jsonFactory.createParser(jsonText).use { streamTimeline(it, onProgress) }

/**
 * Parse straight from a stream (used for real files). The caller owns the
 * stream's lifecycle; this only reads from it.
 */
fun parseTimeline(input: InputStream, onProgress: (ParserStage) -> Unit = {}): ParsedTimeline =
    jsonFactory.createParser(input).use { streamTimeline(it, onProgress) }

// ---------- Streaming core ----------

private class SegmentCounters {
    var path = 0
    var visit = 0
    var activity = 0
}

private fun streamTimeline(p: JsonParser, onProgress: (ParserStage) -> Unit): ParsedTimeline {
    onProgress(ParserStage.DecodingJson)

    val points = ArrayList<PathPoint>()
    val fallbackSignalPoints = ArrayList<PathPoint>()
    val counters = SegmentCounters()
    var total = 0

    var sawSemantic = false
    var sawTimelineObjects = false
    var sawLocations = false
    var sawRawSignals = false
    var arrayVariant = false

    fun tick() {
        if (total % PROGRESS_STRIDE == 0) onProgress(ParserStage.ExtractingSegments(total, total))
    }

    when (p.nextToken()) {
        JsonToken.START_ARRAY -> {
            // iOS phone variant: a bare top-level array of semantic segments.
            arrayVariant = true
            while (p.nextToken() != JsonToken.END_ARRAY) {
                extractSemanticSegment(p, points, counters)
                total++; tick()
            }
        }

        JsonToken.START_OBJECT -> {
            while (p.nextToken() != JsonToken.END_OBJECT) {
                val field = p.currentName()
                p.nextToken() // advance onto the field's value
                when (field) {
                    "semanticSegments" -> {
                        sawSemantic = true
                        if (p.currentToken() == JsonToken.START_ARRAY) {
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                extractSemanticSegment(p, points, counters)
                                total++; tick()
                            }
                        } else p.skipChildren()
                    }
                    "timelineObjects" -> {
                        sawTimelineObjects = true
                        if (p.currentToken() == JsonToken.START_ARRAY) {
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                extractTimelineObject(p, points, counters)
                                total++; tick()
                            }
                        } else p.skipChildren()
                    }
                    "locations" -> {
                        sawLocations = true
                        if (p.currentToken() == JsonToken.START_ARRAY) {
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                extractLocationRecord(p, points)
                                counters.path++
                                total++; tick()
                            }
                        } else p.skipChildren()
                    }
                    "rawSignals" -> {
                        sawRawSignals = true
                        // Only ever used as a fallback when nothing else yielded
                        // points. If we already have path points (the normal case)
                        // we skip each entry instead of parsing it — but still walk
                        // the array element-by-element so byte progress keeps
                        // advancing through this (often large) trailing section.
                        if (p.currentToken() == JsonToken.START_ARRAY) {
                            val collect = points.isEmpty()
                            var i = 0
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                if (collect) extractRawSignal(p, fallbackSignalPoints) else p.skipChildren()
                                i++
                                if (i % PROGRESS_STRIDE == 0) onProgress(ParserStage.ExtractingSegments(total, total))
                            }
                        } else p.skipChildren()
                    }
                    else -> p.skipChildren()
                }
            }
        }

        else -> throw NotTimelineFileException(
            "File does not begin with a JSON object or array."
        )
    }

    var pathSegments = counters.path
    val visitSegments = counters.visit
    val activitySegments = counters.activity

    val format: TimelineFormat = when {
        arrayVariant -> TimelineFormat.PHONE_TAKEOUT_ARRAY
        sawSemantic -> TimelineFormat.PHONE_TAKEOUT
        sawTimelineObjects -> TimelineFormat.SEMANTIC_LOCATION_HISTORY
        sawLocations -> TimelineFormat.RECORDS
        sawRawSignals -> TimelineFormat.PHONE_TAKEOUT
        else -> throw NotTimelineFileException(
            "Unrecognized Timeline format: none of semanticSegments, " +
                "timelineObjects, locations or rawSignals were present."
        )
    }

    // Fallback: a file whose only usable GPS lived in rawSignals.
    if (points.isEmpty() && fallbackSignalPoints.isNotEmpty()) {
        points.addAll(fallbackSignalPoints)
        if (!sawSemantic && !sawTimelineObjects && !sawLocations) {
            total = fallbackSignalPoints.size
            pathSegments = fallbackSignalPoints.size
        }
    }

    onProgress(ParserStage.ExtractingSegments(total, total))
    onProgress(ParserStage.Sorting)
    points.sortBy { it.timeUtc }

    return ParsedTimeline(
        pathPoints = points,
        totalSegments = total,
        pathSegments = pathSegments,
        visitSegments = visitSegments,
        activitySegments = activitySegments,
        format = format,
    )
}

// ---------- Per-format streaming extractors ----------
//
// Each function is entered with the parser positioned ON the element's start
// token and returns with the parser positioned ON that element's matching end
// token, so the enclosing array loop can advance with a single nextToken().

private class RawPt(val point: String?, val time: String?, val offset: String?)

/** Phone-takeout semantic segment: timelinePath points, or visit/activity. */
private fun extractSemanticSegment(
    p: JsonParser,
    out: MutableList<PathPoint>,
    counters: SegmentCounters,
) {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return }

    var startTime: Instant? = null
    // Timezone in effect for this segment. Google states it explicitly in
    // `startTimeTimezoneUtcOffsetMinutes`; the ISO `startTime` usually carries
    // the same offset, and serves as a fallback when the field is absent.
    var segmentOffset: Int? = null
    var startTimeOffset: Int? = null
    var hadTimelinePath = false
    var hadVisit = false
    var hadActivity = false
    // timelinePath points are buffered (a segment is small) so the per-point
    // time can resolve against startTime regardless of key order in the JSON.
    var pending: ArrayList<RawPt>? = null

    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "startTime" -> {
                val raw = p.valueAsStringOrNull()
                startTime = parseTimestamp(raw)
                startTimeOffset = parseOffsetMinutes(raw)
            }
            "startTimeTimezoneUtcOffsetMinutes" ->
                segmentOffset = p.longOrNull()?.toInt()?.takeIf { it in VALID_OFFSET_RANGE }
            "timelinePath" -> {
                hadTimelinePath = true
                if (p.currentToken() == JsonToken.START_ARRAY) {
                    val list = pending ?: ArrayList<RawPt>().also { pending = it }
                    while (p.nextToken() != JsonToken.END_ARRAY) list.add(readTimelinePoint(p))
                } else p.skipChildren()
            }
            "visit" -> { hadVisit = true; p.skipChildren() }
            "activity" -> { hadActivity = true; p.skipChildren() }
            else -> p.skipChildren()
        }
    }

    pending?.forEach { rp ->
        val coords = rp.point?.let { parseCoordinatePair(it) } ?: return@forEach
        val time = rp.time?.let { parseTimestamp(it) }
            ?: rp.offset?.trim()?.toLongOrNull()?.let { off -> startTime?.plusSeconds(off * 60) }
            ?: return@forEach
        // Per-point offset wins when the point's own timestamp carries one;
        // otherwise the segment's timezone applies to every point in it.
        val tz = rp.time?.let { parseOffsetMinutes(it) } ?: segmentOffset ?: startTimeOffset
        out.add(PathPoint(time, coords.first, coords.second, tz))
    }

    when {
        hadTimelinePath -> counters.path++
        hadVisit -> counters.visit++
        hadActivity -> counters.activity++
    }
}

private fun readTimelinePoint(p: JsonParser): RawPt {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return RawPt(null, null, null) }
    var point: String? = null
    var time: String? = null
    var offset: String? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "point" -> point = p.valueAsStringOrNull()
            "time" -> time = p.valueAsStringOrNull()
            "durationMinutesOffsetFromStartTime" -> offset = p.valueAsStringOrNull()
            else -> p.skipChildren()
        }
    }
    return RawPt(point, time, offset)
}

/** Takeout "Semantic Location History" object: activitySegment / placeVisit. */
private fun extractTimelineObject(
    p: JsonParser,
    out: MutableList<PathPoint>,
    counters: SegmentCounters,
) {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return }
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "activitySegment" -> {
                if (p.currentToken() == JsonToken.START_OBJECT) {
                    counters.path++
                    readActivitySegment(p, out)
                } else p.skipChildren()
            }
            "placeVisit" -> {
                if (p.currentToken() == JsonToken.START_OBJECT) {
                    counters.visit++
                    readPlaceVisit(p, out)
                } else p.skipChildren()
            }
            else -> p.skipChildren()
        }
    }
}

private fun readActivitySegment(p: JsonParser, out: MutableList<PathPoint>) {
    var durStart: String? = null
    var durEnd: String? = null
    var startLoc: Pair<Double, Double>? = null
    var endLoc: Pair<Double, Double>? = null
    var waypoints: ArrayList<Pair<Double, Double>>? = null
    var rawPath: ArrayList<Pair<Pair<Double, Double>, String?>>? = null

    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "duration" -> { val (s, e) = readDuration(p); durStart = s; durEnd = e }
            "startLocation" -> startLoc = readE7Location(p)
            "endLocation" -> endLoc = readE7Location(p)
            "waypointPath" -> waypoints = readWaypointPath(p)
            "simplifiedRawPath" -> rawPath = readSimplifiedRawPath(p)
            else -> p.skipChildren()
        }
    }

    val start = parseTimestamp(durStart)
    val end = parseTimestamp(durEnd)
    val startTz = parseOffsetMinutes(durStart)
    val endTz = parseOffsetMinutes(durEnd)
    if (rawPath != null && rawPath.isNotEmpty()) {
        for ((coords, ts) in rawPath) {
            val time = parseTimestamp(ts) ?: start ?: continue
            val tz = parseOffsetMinutes(ts) ?: startTz
            out.add(PathPoint(time, coords.first, coords.second, tz))
        }
    } else {
        if (start != null && startLoc != null) {
            out.add(PathPoint(start, startLoc.first, startLoc.second, startTz))
        }
        val mid = start ?: end
        val midTz = startTz ?: endTz
        if (mid != null && waypoints != null) {
            for (w in waypoints) out.add(PathPoint(mid, w.first, w.second, midTz))
        }
        if (end != null && endLoc != null) {
            out.add(PathPoint(end, endLoc.first, endLoc.second, endTz))
        }
    }
}

private fun readPlaceVisit(p: JsonParser, out: MutableList<PathPoint>) {
    var loc: Pair<Double, Double>? = null
    var durStart: String? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "location" -> loc = readE7Location(p)
            "duration" -> durStart = readDuration(p).first
            else -> p.skipChildren()
        }
    }
    val time = parseTimestamp(durStart) ?: return
    if (loc != null) out.add(PathPoint(time, loc.first, loc.second, parseOffsetMinutes(durStart)))
}

/** Returns (startTimestamp ?: startTimestampMs, endTimestamp ?: endTimestampMs). */
private fun readDuration(p: JsonParser): Pair<String?, String?> {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return null to null }
    var ts: String? = null
    var tsMs: String? = null
    var es: String? = null
    var esMs: String? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "startTimestamp" -> ts = p.valueAsStringOrNull()
            "startTimestampMs" -> tsMs = p.valueAsStringOrNull()
            "endTimestamp" -> es = p.valueAsStringOrNull()
            "endTimestampMs" -> esMs = p.valueAsStringOrNull()
            else -> p.skipChildren()
        }
    }
    return (ts ?: tsMs) to (es ?: esMs)
}

private fun readWaypointPath(p: JsonParser): ArrayList<Pair<Double, Double>>? {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return null }
    var result: ArrayList<Pair<Double, Double>>? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        if (f == "waypoints" && p.currentToken() == JsonToken.START_ARRAY) {
            val list = ArrayList<Pair<Double, Double>>()
            while (p.nextToken() != JsonToken.END_ARRAY) readE7Location(p)?.let { list.add(it) }
            result = list
        } else p.skipChildren()
    }
    return result
}

private fun readSimplifiedRawPath(p: JsonParser): ArrayList<Pair<Pair<Double, Double>, String?>>? {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return null }
    var result: ArrayList<Pair<Pair<Double, Double>, String?>>? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        if (f == "points" && p.currentToken() == JsonToken.START_ARRAY) {
            val list = ArrayList<Pair<Pair<Double, Double>, String?>>()
            while (p.nextToken() != JsonToken.END_ARRAY) readSimplifiedPoint(p)?.let { list.add(it) }
            result = list
        } else p.skipChildren()
    }
    return result
}

private fun readSimplifiedPoint(p: JsonParser): Pair<Pair<Double, Double>, String?>? {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return null }
    var latP: Long? = null; var latA: Long? = null
    var lonP: Long? = null; var lonA: Long? = null
    var ts: String? = null; var tsMs: String? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "latitudeE7" -> latP = p.longOrNull()
            "latE7" -> latA = p.longOrNull()
            "longitudeE7" -> lonP = p.longOrNull()
            "lngE7" -> lonA = p.longOrNull()
            "timestamp" -> ts = p.valueAsStringOrNull()
            "timestampMs" -> tsMs = p.valueAsStringOrNull()
            else -> p.skipChildren()
        }
    }
    val coords = e7Pair(latP ?: latA, lonP ?: lonA) ?: return null
    return coords to (ts ?: tsMs)
}

/** E7 location object with either latitudeE7/longitudeE7 or latE7/lngE7. */
private fun readE7Location(p: JsonParser): Pair<Double, Double>? {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return null }
    var latP: Long? = null; var latA: Long? = null
    var lonP: Long? = null; var lonA: Long? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "latitudeE7" -> latP = p.longOrNull()
            "latE7" -> latA = p.longOrNull()
            "longitudeE7" -> lonP = p.longOrNull()
            "lngE7" -> lonA = p.longOrNull()
            else -> p.skipChildren()
        }
    }
    return e7Pair(latP ?: latA, lonP ?: lonA)
}

/** Takeout "Records.json" entry: E7 coords + timestamp. */
private fun extractLocationRecord(p: JsonParser, out: MutableList<PathPoint>) {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return }
    var latE7: Long? = null; var lonE7: Long? = null
    var ts: String? = null; var tsMs: String? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        when (f) {
            "latitudeE7" -> latE7 = p.longOrNull()
            "longitudeE7" -> lonE7 = p.longOrNull()
            "timestamp" -> ts = p.valueAsStringOrNull()
            "timestampMs" -> tsMs = p.valueAsStringOrNull()
            else -> p.skipChildren()
        }
    }
    val coords = e7Pair(latE7, lonE7) ?: return
    val raw = ts ?: tsMs
    val time = parseTimestamp(raw) ?: return
    out.add(PathPoint(time, coords.first, coords.second, parseOffsetMinutes(raw)))
}

/** Phone export rawSignals fallback: position.LatLng + position.timestamp. */
private fun extractRawSignal(p: JsonParser, out: MutableList<PathPoint>) {
    if (p.currentToken() != JsonToken.START_OBJECT) { p.skipChildren(); return }
    var latLng: String? = null
    var ts: String? = null
    while (p.nextToken() != JsonToken.END_OBJECT) {
        val f = p.currentName()
        p.nextToken()
        if (f == "position" && p.currentToken() == JsonToken.START_OBJECT) {
            while (p.nextToken() != JsonToken.END_OBJECT) {
                val pf = p.currentName()
                p.nextToken()
                when (pf) {
                    "LatLng" -> latLng = p.valueAsStringOrNull()
                    "timestamp" -> ts = p.valueAsStringOrNull()
                    else -> p.skipChildren()
                }
            }
        } else p.skipChildren()
    }
    val coords = latLng?.let { parseCoordinatePair(it) } ?: return
    val time = parseTimestamp(ts) ?: return
    out.add(PathPoint(time, coords.first, coords.second, parseOffsetMinutes(ts)))
}

// ---------- Token helpers ----------

/** Text of the current scalar value, or null for null / nested structures. */
private fun JsonParser.valueAsStringOrNull(): String? = when (currentToken()) {
    JsonToken.VALUE_NULL -> null
    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> { skipChildren(); null }
    else -> valueAsString
}

/** Long value of the current token (int or numeric string), else null. */
private fun JsonParser.longOrNull(): Long? = when (currentToken()) {
    JsonToken.VALUE_NUMBER_INT -> longValue
    JsonToken.VALUE_STRING -> valueAsString?.trim()?.toLongOrNull()
    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> { skipChildren(); null }
    else -> null
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

/** Sanity bounds for a UTC offset in minutes (-12:00 … +14:00). */
private val VALID_OFFSET_RANGE = -12 * 60..14 * 60

/**
 * The UTC offset a timestamp was written in, in minutes (+02:00 -> 120), or
 * null when the encoding doesn't carry one.
 *
 * Deliberately returns null for `Z` timestamps and epoch numbers. Those say
 * "this instant in UTC" and say nothing about the traveller's local clock —
 * treating them as offset 0 would print a confident, wrong local time for
 * everyone outside Greenwich. A genuine "+00:00" in a phone export is kept,
 * because there the offset really was recorded and really is zero.
 */
internal fun parseOffsetMinutes(text: String?): Int? {
    val t = text?.trim()
    if (t.isNullOrEmpty()) return null
    if (t.toLongOrNull() != null) return null
    if (t.endsWith("Z") || t.endsWith("z")) return null
    return try {
        val minutes = OffsetDateTime.parse(t).offset.totalSeconds / 60
        minutes.takeIf { it in VALID_OFFSET_RANGE }
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Kept for source/test compatibility; delegates to [parseTimestamp]. */
internal fun parseIsoInstant(text: String): Instant? = parseTimestamp(text)
