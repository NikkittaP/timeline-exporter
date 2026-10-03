package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.export.ContentKind
import io.github.nikkittap.timelineexporter.export.CsvExporter
import io.github.nikkittap.timelineexporter.export.GeoJsonExporter
import io.github.nikkittap.timelineexporter.export.GpxExporter
import io.github.nikkittap.timelineexporter.export.KmlExporter
import io.github.nikkittap.timelineexporter.filter.TimelineFilter
import io.github.nikkittap.timelineexporter.trips.Trip
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The names a trips export suggests: every trip keeps the plain name, one
 * ticked trip is named by its dates, several by their count. The same stem
 * goes on every format.
 */
class SuggestFilenameTest {

    private val formats = listOf(GpxExporter, KmlExporter, GeoJsonExporter, CsvExporter)

    private fun trip(start: String, end: String) = Trip(
        start = Instant.parse(start),
        end = Instant.parse(end),
        // UTC+2: the first trip below starts on the 18th in UTC but the 19th locally.
        startOffsetMinutes = 120,
        endOffsetMinutes = 120,
        maxDistanceMeters = 0.0,
        farthestLatitude = 0.0,
        farthestLongitude = 0.0,
        travelledMeters = 0.0,
        travelledEstimated = false,
        placeCount = 0,
        movements = emptyList(),
        openStart = false,
        openEnd = false,
    )

    private val summer = trip("2025-07-18T23:30:00Z", "2025-08-01T18:00:00Z")
    private val autumn = trip("2025-10-04T07:00:00Z", "2025-10-05T19:00:00Z")

    private fun names(checked: List<Trip>) =
        formats.map { suggestFilename(TimelineFilter(), it, ContentKind.TRIPS, checked) }

    @Test
    fun `every trip keeps the plain name`() {
        assertEquals(
            listOf("Timeline_trips.gpx", "Timeline_trips.kml", "Timeline_trips.geojson", "Timeline_trips.csv"),
            names(emptyList()),
        )
    }

    @Test
    fun `one trip is named by its local dates`() {
        assertEquals(
            listOf("gpx", "kml", "geojson", "csv").map { "Timeline_trip_2025-07-19_2025-08-01.$it" },
            names(listOf(summer)),
        )
    }

    @Test
    fun `several trips are named by their count`() {
        assertEquals(
            listOf("gpx", "kml", "geojson", "csv").map { "Timeline_trips_2.$it" },
            names(listOf(summer, autumn)),
        )
    }
}
