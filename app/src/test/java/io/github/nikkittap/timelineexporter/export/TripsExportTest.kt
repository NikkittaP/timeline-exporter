package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.trips.Trip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.time.Instant
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/** The trips content in every format, added in 1.9.0. */
class TripsExportTest {

    private val t0 = Instant.parse("2025-07-19T10:00:00Z")
    private fun h(hours: Long) = t0.plusSeconds(hours * 3600)

    private fun trip(fromH: Long, toH: Long) = Trip(
        start = h(fromH),
        end = h(toH),
        startOffsetMinutes = 120,
        endOffsetMinutes = 120,
        maxDistanceMeters = 719_000.0,
        farthestLatitude = 61.08,
        farthestLongitude = 6.45,
        travelledMeters = 2_317_000.0,
        travelledEstimated = fromH > 0,
        placeCount = 56,
        movements = listOf(MovementGroup.DRIVING to 2_000_000.0, MovementGroup.WALKING to 317_000.0),
        openStart = false,
        openEnd = false,
    )

    // Points at hours 0..400: the first trip (hours 0..312) gets 313 of them,
    // the second (hours 500..510) none at all.
    private val points = (0L..400L).map { PathPoint(h(it), 59.0 + it / 1000.0, 10.0) }
    private val trips = exportTrips(listOf(trip(0, 312), trip(500, 510)), points, ZoneOffset.UTC)
    private val content = ExportContent.Trips(trips)

    private fun export(exporter: Exporter) = StringBuilder().also { exporter.export(content, "Trips", it) }.toString()

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(text.byteInputStream(Charsets.UTF_8))

    @Test
    fun `each trip gets its slice of the track and a dated name`() {
        assertEquals(313, trips[0].points.size)
        assertEquals(0, trips[1].points.size)
        assertEquals("2025-07-19 – 2025-08-01", trips[0].name)
    }

    @Test
    fun `GPX writes one trk per trip that has points`() {
        val doc = parseXml(export(GpxExporter))
        val trks = doc.getElementsByTagNameNS("*", "trk")
        assertEquals(1, trks.length)
        val trk = trks.item(0) as Element
        assertEquals("2025-07-19 – 2025-08-01", trk.getElementsByTagNameNS("*", "name").item(0).textContent)
        assertEquals(313, trk.getElementsByTagNameNS("*", "trkpt").length)
        assertTrue(trk.getElementsByTagNameNS("*", "desc").item(0).textContent.contains("719 km from home"))
    }

    @Test
    fun `KML writes one placemark per trip with a time span`() {
        val doc = parseXml(export(KmlExporter))
        val marks = doc.getElementsByTagNameNS("*", "Placemark")
        assertEquals(1, marks.length)
        assertEquals(1, (marks.item(0) as Element).getElementsByTagNameNS("*", "TimeSpan").length)
    }

    @Test
    fun `GeoJSON writes one feature per trip with its figures`() {
        val json = Json.parseToJsonElement(export(GeoJsonExporter)).jsonObject
        val features = json["features"]!!.jsonArray
        assertEquals(1, features.size)
        val props = features[0].jsonObject["properties"]!!.jsonObject
        assertEquals(56, props["places"]!!.jsonPrimitive.int)
        assertEquals("DRIVING;WALKING", props["movements"]!!.jsonPrimitive.content)
        assertEquals(313, features[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.size)
    }

    @Test
    fun `CSV lists every trip, including one with no points left`() {
        val lines = export(CsvExporter).trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("name,start_utc,start_local,start_offset,end_utc"))
        assertTrue(lines[0].contains(",travelled_km,travelled_estimated,places,"))
        assertTrue(lines[1].contains(",2317.0,false,56,"))
        assertTrue(lines[2].contains(",2317.0,true,56,"))
        assertTrue(lines[1].startsWith("2025-07-19 – 2025-08-01,2025-07-19T10:00:00Z,2025-07-19 12:00:00,+02:00,"))
        assertTrue(lines[1].endsWith(",false,false,313"))
        assertTrue(lines[2].endsWith(",0"))
    }
}
