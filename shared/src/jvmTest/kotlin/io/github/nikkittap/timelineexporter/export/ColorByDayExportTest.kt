package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A track exported with the map's `Color by day` switch on: KML and GeoJSON
 * write one line per local day, in the colour the preview drew it.
 *
 * Reported from a real user: the preview showed each day in its own colour
 * and the KML came out as one orange line.
 */
class ColorByDayExportTest {

    private fun point(time: String, lat: Double, lon: Double, offset: Int? = 0) =
        PathPoint(Instant.parse(time), lat, lon, offset)

    /** Three days, crossing two midnights, UTC offsets on every sample. */
    private val threeDays = listOf(
        point("2026-09-08T10:00:00Z", 55.00, 13.00),
        point("2026-09-08T23:50:00Z", 55.01, 13.01),
        point("2026-09-09T00:10:00Z", 55.02, 13.02),
        point("2026-09-09T12:00:00Z", 55.03, 13.03),
        point("2026-09-10T00:05:00Z", 55.04, 13.04),
        point("2026-09-10T09:00:00Z", 55.05, 13.05),
    )

    private fun Exporter.byDay(points: List<PathPoint>, name: String = "Trip"): String =
        buildString { export(ExportContent.Track(points, colorByDay = true, fallbackZone = ZoneOffset.UTC), name, this) }

    private fun kmlDoc(kml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(kml.byteInputStream(Charsets.UTF_8))

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return List(nodes.length) { nodes.item(it) as Element }
    }

    private fun Element.text(tag: String): String = children(tag).first().textContent.trim()

    /** Every `lon,lat` pair of one KML `<coordinates>` element, in order. */
    private fun Element.coordinates(): List<Pair<Double, Double>> =
        textContent.trim().split(Regex("\\s+")).map {
            val (lon, lat) = it.split(',')
            lon.toDouble() to lat.toDouble()
        }

    private fun PathPoint.lonLat() = longitude to latitude

    // ---------- KML ----------

    @Test fun `kml with the switch off is still one orange line`() {
        val kml = KmlExporter.exportTrack(threeDays, "Trip")
        val doc = kmlDoc(kml)
        assertEquals(1, doc.getElementsByTagName("Placemark").length)
        assertTrue(kml.contains("<color>ff2257ff</color>"))
    }

    @Test fun `kml writes one placemark per day, named by date, in the preview colour`() {
        val doc = kmlDoc(KmlExporter.byDay(threeDays)).documentElement
        val placemarks = doc.children("Placemark")
        val dates = listOf("2026-09-08", "2026-09-09", "2026-09-10")
        assertEquals(dates, placemarks.map { it.text("name") })

        val styles = doc.children("Style").associate { it.getAttribute("id") to it.text("color") }
        for ((placemark, date) in placemarks.zip(dates)) {
            val styleId = placemark.text("styleUrl").removePrefix("#")
            assertEquals(kmlColor(trackDayColor(LocalDate.parse(date))), styles[styleId])
        }
    }

    @Test fun `kml days together carry every edge exactly once`() {
        val placemarks = kmlDoc(KmlExporter.byDay(threeDays)).documentElement.children("Placemark")
        val edges = placemarks.flatMap { it.children("coordinates").single().coordinates().zipWithNext() }
        assertEquals(threeDays.map { it.lonLat() }.zipWithNext(), edges)
    }

    @Test fun `kml day time span runs from its first sample to its last`() {
        val placemarks = kmlDoc(KmlExporter.byDay(threeDays)).documentElement.children("Placemark")
        val middle = placemarks[1]
        // The edge across midnight belongs to the day it started on, so the
        // day ends on the next day's first sample - the one the two lines
        // share - and begins on the sample the previous day ended on.
        assertEquals("2026-09-09T00:10:00Z", middle.text("begin"))
        assertEquals("2026-09-10T00:05:00Z", middle.text("end"))
    }

    @Test fun `kml writes one style per colour, not one per day`() {
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val sixtyDays = List(120) { i ->
            PathPoint(start.plusSeconds(i * 43_200L), 55.0 + i * 1e-3, 13.0, 0)
        }
        val doc = kmlDoc(KmlExporter.byDay(sixtyDays)).documentElement
        assertEquals(60, doc.children("Placemark").size)
        assertEquals(21, doc.children("Style").size)
    }

    @Test fun `kml date revisited across the date line is one placemark with two lines`() {
        val points = listOf(
            point("2026-09-09T08:00:00Z", -14.0, -171.0, -660),
            point("2026-09-09T09:00:00Z", -14.1, -171.1, -660),  // Sep 8 locally
            point("2026-09-09T10:00:00Z", -17.0, 178.0, 780),     // Sep 9 locally
            point("2026-09-09T11:00:00Z", -17.1, 178.1, 780),
            point("2026-09-09T12:00:00Z", -14.2, -171.2, -660),  // back to Sep 9, -11
            point("2026-09-10T12:30:00Z", -14.3, -171.3, -660),
        )
        val placemarks = kmlDoc(KmlExporter.byDay(points)).documentElement.children("Placemark")
        val names = placemarks.map { it.text("name") }
        assertEquals(names.distinct(), names)
        val revisited = placemarks.single { it.children("LineString").size > 1 }
        assertEquals(1, revisited.children("MultiGeometry").size)
        val edges = placemarks.flatMap { pm ->
            pm.children("coordinates").flatMap { it.coordinates().zipWithNext() }
        }
        assertEquals(points.map { it.lonLat() }.zipWithNext().toSet(), edges.toSet())
        assertEquals(points.size - 1, edges.size)
    }

    @Test fun `kml with a single sample falls back to the plain track`() {
        val kml = KmlExporter.byDay(threeDays.take(1))
        assertEquals(1, kmlDoc(kml).getElementsByTagName("Placemark").length)
        assertTrue(kml.contains("#trackStyle"))
    }

    @Test fun `kml escapes the document name in day mode`() {
        val doc = kmlDoc(KmlExporter.byDay(threeDays, name = "Tom & Jerry <3"))
        assertEquals("Tom & Jerry <3", (doc.documentElement.children("Document").single()).text("name"))
    }

    @Test fun `kml colour is alpha then blue green red`() {
        assertEquals("ff2257ff", kmlColor("#FF5722"))
        assertEquals("ffb27200", kmlColor("#0072B2"))
    }

    // ---------- GeoJSON ----------

    private fun features(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.map { it.jsonObject }

    private fun JsonArray.lonLats(): List<Pair<Double, Double>> =
        map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double }

    @Test fun `geojson with the switch off is still one feature`() {
        assertEquals(1, features(GeoJsonExporter.exportTrack(threeDays, "Trip")).size)
    }

    @Test fun `geojson writes one feature per day with simplestyle stroke`() {
        val json = GeoJsonExporter.byDay(threeDays, name = "Trip \"one\"")
        assertEquals("Trip \"one\"", Json.parseToJsonElement(json).jsonObject["name"]!!.jsonPrimitive.content)
        val features = features(json)
        assertEquals(listOf("2026-09-08", "2026-09-09", "2026-09-10"), features.map {
            it["properties"]!!.jsonObject["date"]!!.jsonPrimitive.content
        })
        for (feature in features) {
            val props = feature["properties"]!!.jsonObject
            val date = LocalDate.parse(props["date"]!!.jsonPrimitive.content)
            assertEquals(trackDayColor(date), props["stroke"]!!.jsonPrimitive.content)
            assertEquals(4, props["stroke-width"]!!.jsonPrimitive.int)
            assertEquals("LineString", feature["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        val edges = features.flatMap {
            it["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.lonLats().zipWithNext()
        }
        assertEquals(threeDays.map { it.lonLat() }.zipWithNext(), edges)
    }

    @Test fun `geojson date revisited across the date line is a multilinestring`() {
        val points = listOf(
            point("2026-09-09T09:00:00Z", -14.1, -171.1, -660),
            point("2026-09-09T10:00:00Z", -17.0, 178.0, 780),
            point("2026-09-09T11:00:00Z", -17.1, 178.1, 780),
            point("2026-09-09T12:00:00Z", -14.2, -171.2, -660),
            point("2026-09-09T13:00:00Z", -14.3, -171.3, -660),
        )
        val features = features(GeoJsonExporter.byDay(points))
        val dates = features.map { it["properties"]!!.jsonObject["date"]!!.jsonPrimitive.content }
        assertEquals(dates.distinct(), dates)
        val multi = features.single {
            it["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content == "MultiLineString"
        }
        assertEquals(2, multi["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.size)
    }

    // ---------- Formats with no line colour ----------

    @Test fun `gpx and csv ignore the switch`() {
        for (exporter in listOf(GpxExporter, CsvExporter)) {
            assertEquals(exporter.displayName, exporter.exportTrack(threeDays, "Trip"), exporter.byDay(threeDays))
        }
    }
}
