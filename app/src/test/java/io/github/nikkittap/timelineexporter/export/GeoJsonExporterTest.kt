package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GeoJsonExporterTest {

    @Test
    fun `output is well-formed JSON parseable by kotlinx serialization`() {
        val json = GeoJsonExporter.export(
            listOf(
                PathPoint(Instant.parse("2025-01-01T00:00:00Z"), 41.284025, 69.242256),
                PathPoint(Instant.parse("2025-01-01T00:01:00Z"), 41.290000, 69.250000),
            ),
            trackName = "My Trip",
        )
        // Parsing throws if malformed — proves well-formedness.
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("FeatureCollection", parsed["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `coordinates are emitted as lon-then-lat per RFC 7946`() {
        // Same trap as KML: the spec inverts the lat/lon order from how
        // humans usually write it. Pin it down with a test.
        val json = GeoJsonExporter.export(
            listOf(PathPoint(Instant.parse("2025-01-01T00:00:00Z"), 41.284025, 69.242256)),
            "T",
        )
        val coords = Json.parseToJsonElement(json)
            .jsonObject["features"]!!.jsonArray[0]
            .jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray

        assertEquals("longitude first", 69.242256, coords[0].jsonPrimitive.double, 0.0)
        assertEquals("latitude second", 41.284025, coords[1].jsonPrimitive.double, 0.0)
    }

    @Test
    fun `track name is round-tripped via JSON-string escaping`() {
        val json = GeoJsonExporter.export(
            points = emptyList(),
            trackName = """Trip with "quotes" and \backslashes\""",
        )
        val name = Json.parseToJsonElement(json)
            .jsonObject["features"]!!.jsonArray[0]
            .jsonObject["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("""Trip with "quotes" and \backslashes\""", name)
    }

    @Test
    fun `empty points produces an empty coordinates array`() {
        val json = GeoJsonExporter.export(emptyList(), "T")
        val coords: JsonArray = Json.parseToJsonElement(json)
            .jsonObject["features"]!!.jsonArray[0]
            .jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(0, coords.size)
    }

    @Test
    fun `Exporter contract metadata is correct`() {
        assertEquals("GeoJSON", GeoJsonExporter.displayName)
        assertEquals("geojson", GeoJsonExporter.fileExtension)
        assertEquals("application/geo+json", GeoJsonExporter.mimeType)
    }
}
