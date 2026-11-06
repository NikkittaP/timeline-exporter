package io.github.nikkittap.timelineexporter.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The places half of every format, added in 1.7.0.
 *
 * The interesting cases are the ones where a place is not a track point: free
 * text that has to be escaped, a missing place ID, and the child-element order
 * the XML schemas insist on.
 */
class PlacesExportTest {

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(text.byteInputStream(Charsets.UTF_8))

    // ---------- GPX ----------

    @Test
    fun `GPX writes one waypoint per place, not a track`() {
        val gpx = GpxExporter.exportPlaces(
            listOf(place(name = "Home"), place(name = "Office", placeId = "office", visits = 65)),
            "Places",
        )
        val doc = parseXml(gpx)
        assertEquals(2, doc.getElementsByTagName("wpt").length)
        assertEquals("no track element belongs in a places export", 0, doc.getElementsByTagName("trk").length)
    }

    @Test
    fun `GPX waypoint carries coordinates, name and the Google Maps link`() {
        val gpx = GpxExporter.exportPlaces(listOf(place()), "Places")
        val wpt = parseXml(gpx).getElementsByTagName("wpt").item(0) as Element

        assertEquals("55.5946203", wpt.getAttribute("lat"))
        assertEquals("13.0065502", wpt.getAttribute("lon"))
        assertEquals("Home", wpt.getElementsByTagName("name").item(0).textContent)
        assertEquals("INFERRED_HOME", wpt.getElementsByTagName("type").item(0).textContent)
        assertEquals(
            "https://www.google.com/maps/search/?api=1" +
                "&query=55.5946203%2C13.0065502" +
                "&query_place_id=ChIJawW7u1uhU0YRxrEsIlFgf5Q",
            (wpt.getElementsByTagName("link").item(0) as Element).getAttribute("href"),
        )
    }

    @Test
    fun `GPX waypoint children follow the order the schema requires`() {
        // wptType is an xsd:sequence: time, name, desc, link, type. A "nicer"
        // order still parses as XML but fails schema validation, which is what
        // strict readers do before they show anything at all.
        val gpx = GpxExporter.exportPlaces(listOf(place()), "Places")
        val wpt = parseXml(gpx).getElementsByTagName("wpt").item(0) as Element
        val order = (0 until wpt.childNodes.length)
            .map { wpt.childNodes.item(it) }
            .filterIsInstance<Element>()
            .map { it.tagName }
        assertEquals(listOf("time", "name", "desc", "link", "type"), order)
    }

    @Test
    fun `GPX still links a place with no ID, by its coordinates alone`() {
        // Maps uses the query when it cannot resolve the place ID, so dropping
        // to coordinates costs the place's name and nothing else. The older
        // behaviour — no link at all — left the one waypoint the user is least
        // likely to recognise as the only one they could not look up.
        val gpx = GpxExporter.exportPlaces(listOf(place(placeId = null)), "Places")
        val link = parseXml(gpx).getElementsByTagName("link").item(0) as Element
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=55.5946203%2C13.0065502",
            link.getAttribute("href"),
        )
    }

    @Test
    fun `a link never falls back to scientific notation near zero`() {
        // Double.toString writes 1.0E-5, which is not a coordinate any map
        // will parse — and the places it would break are on the equator and
        // the prime meridian, not in some unreachable corner.
        val url = googleMapsUrl(latitude = 0.00001, longitude = -0.000002, placeId = null)
        assertFalse(url, url.contains("E-"))
        assertTrue(url, url.contains("query=0.0000100%2C-0.0000020"))
    }

    @Test
    fun `GPX stamps the waypoint with the last visit, not the first`() {
        val gpx = GpxExporter.exportPlaces(listOf(place()), "Places")
        val wpt = parseXml(gpx).getElementsByTagName("wpt").item(0) as Element
        assertEquals("2026-05-21T07:00:00Z", wpt.getElementsByTagName("time").item(0).textContent)
    }

    // ---------- KML ----------

    @Test
    fun `KML writes a Point placemark per place in lon-lat-alt order`() {
        val kml = KmlExporter.exportPlaces(listOf(place()), "Places")
        val doc = parseXml(kml)
        assertEquals(1, doc.getElementsByTagName("Placemark").length)
        assertEquals(0, doc.getElementsByTagName("LineString").length)
        assertEquals(
            "13.0065502,55.5946203,0",
            doc.getElementsByTagName("coordinates").item(0).textContent,
        )
    }

    @Test
    fun `KML spans each place from its first visit to its last`() {
        val kml = KmlExporter.exportPlaces(listOf(place()), "Places")
        val doc = parseXml(kml)
        assertEquals("2025-06-09T19:58:00Z", doc.getElementsByTagName("begin").item(0).textContent)
        assertEquals("2026-05-21T07:00:00Z", doc.getElementsByTagName("end").item(0).textContent)
    }

    // ---------- GeoJSON ----------

    @Test
    fun `GeoJSON writes one Point feature per place with the aggregate in properties`() {
        val json = GeoJsonExporter.exportPlaces(
            listOf(place(address = "Storgatan 1", visits = 292, totalSeconds = 21_357_360L)),
            "Places",
        )
        val features = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray
        assertEquals(1, features.size)
        val feature = features[0].jsonObject
        assertEquals("Point", feature["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val coords = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals("longitude first", 13.0065502, coords[0].jsonPrimitive.double, 0.0)
        assertEquals("latitude second", 55.5946203, coords[1].jsonPrimitive.double, 0.0)

        val props = feature["properties"]!!.jsonObject
        assertEquals("Home", props["name"]!!.jsonPrimitive.content)
        assertEquals(292, props["visits"]!!.jsonPrimitive.int)
        assertEquals(21_357_360L, props["total_seconds"]!!.jsonPrimitive.long)
        assertEquals("Storgatan 1", props["address"]!!.jsonPrimitive.content)
        assertEquals("2025-06-09T19:58:00Z", props["first_visit"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GeoJSON keeps missing values as null so every feature has the same keys`() {
        // A table built from these features must not gain and lose columns
        // row by row — QGIS and pandas both read the first feature's keys.
        val json = GeoJsonExporter.exportPlaces(
            listOf(place(placeId = null, address = null, category = null)),
            "Places",
        )
        val props = Json.parseToJsonElement(json)
            .jsonObject["features"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject
        for (key in listOf("place_id", "address", "category")) {
            assertTrue("$key must be present", props.containsKey(key))
            assertNull("$key must be null, not absent", props[key]!!.jsonPrimitive.contentOrNull)
        }
        // The one field that is never null: it needs no place ID.
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=55.5946203%2C13.0065502",
            props["google_maps_url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `GeoJSON escapes quotes and backslashes in a user's own place name`() {
        val json = GeoJsonExporter.exportPlaces(
            listOf(place(name = """The "Old" Mill \ Annexe""")),
            "Places",
        )
        val name = Json.parseToJsonElement(json)
            .jsonObject["features"]!!.jsonArray[0]
            .jsonObject["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("""The "Old" Mill \ Annexe""", name)
    }

    // ---------- CSV ----------

    @Test
    fun `CSV header names every column`() {
        val csv = CsvExporter.exportPlaces(emptyList(), "ignored")
        assertEquals(
            "name,latitude,longitude,visits,total_seconds," +
                "first_visit_utc,first_visit_local,first_visit_offset," +
                "last_visit_utc,last_visit_local,last_visit_offset," +
                "category,address,place_id,google_maps_url\r\n",
            csv,
        )
    }

    @Test
    fun `CSV quotes a name containing a comma or a quote, per RFC 4180`() {
        // The realistic case: the user typed the label themselves, and Google
        // addresses contain commas as a matter of course. Unquoted, one row
        // becomes several and every column after it shifts.
        val csv = CsvExporter.exportPlaces(
            listOf(place(name = """Café "Central", Malmö""", address = "Storgatan 1, Malmö")),
            "ignored",
        )
        val row = csv.split("\r\n")[1]
        assertTrue(row.startsWith(""""Café ""Central"", Malmö","""))
        assertTrue(row.contains(""""Storgatan 1, Malmö""""))
    }

    @Test
    fun `CSV leaves a name that needs no quoting unquoted`() {
        val csv = CsvExporter.exportPlaces(listOf(place(name = "Home")), "ignored")
        assertTrue(csv.split("\r\n")[1].startsWith("Home,55.5946203,13.0065502,292,"))
    }

    @Test
    fun `CSV writes each visit timestamp in UTC, local and offset`() {
        val csv = CsvExporter.exportPlaces(
            listOf(
                place(
                    firstVisit = Instant.parse("2025-06-09T19:58:00Z"),
                    firstVisitOffsetMinutes = 300,
                    lastVisit = Instant.parse("2026-01-02T10:00:00Z"),
                    lastVisitOffsetMinutes = 60,
                )
            ),
            "ignored",
        )
        val row = csv.split("\r\n")[1]
        assertTrue(row.contains("2025-06-09T19:58:00Z,2025-06-10 00:58:00,+05:00"))
        assertTrue(row.contains("2026-01-02T10:00:00Z,2026-01-02 11:00:00,+01:00"))
    }

    @Test
    fun `CSV leaves the local columns empty when the export carried no timezone`() {
        val csv = CsvExporter.exportPlaces(
            listOf(place(firstVisitOffsetMinutes = null, lastVisitOffsetMinutes = null)),
            "ignored",
        )
        val row = csv.split("\r\n")[1]
        assertTrue(row.contains("2025-06-09T19:58:00Z,,"))
        assertFalse("must not invent a local time", row.contains("00:58:00"))
    }
}
