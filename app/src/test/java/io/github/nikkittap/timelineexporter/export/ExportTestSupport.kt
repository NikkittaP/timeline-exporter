package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.Instant

/**
 * Shorthands so the format tests read as "export these points" / "export these
 * places" rather than repeating the [ExportContent] wrapper on every call.
 */
internal fun Exporter.exportTrack(points: List<PathPoint>, name: String): String =
    export(ExportContent.Track(points), name)

internal fun Exporter.exportPlaces(places: List<ExportPlace>, name: String): String =
    export(ExportContent.Places(places), name)

/**
 * A place with plausible defaults, so each test states only the field it is
 * actually about.
 */
internal fun place(
    name: String = "Home",
    latitude: Double = 55.5946203,
    longitude: Double = 13.0065502,
    placeId: String? = "ChIJawW7u1uhU0YRxrEsIlFgf5Q",
    category: String? = "INFERRED_HOME",
    address: String? = null,
    visits: Int = 292,
    firstVisit: Instant = Instant.parse("2025-06-09T19:58:00Z"),
    lastVisit: Instant = Instant.parse("2026-05-21T07:00:00Z"),
    firstVisitOffsetMinutes: Int? = 120,
    lastVisitOffsetMinutes: Int? = 120,
    totalSeconds: Long = 21_357_360L,
) = ExportPlace(
    name = name,
    latitude = latitude,
    longitude = longitude,
    placeId = placeId,
    category = category,
    address = address,
    visits = visits,
    firstVisit = firstVisit,
    lastVisit = lastVisit,
    firstVisitOffsetMinutes = firstVisitOffsetMinutes,
    lastVisitOffsetMinutes = lastVisitOffsetMinutes,
    totalSeconds = totalSeconds,
)
