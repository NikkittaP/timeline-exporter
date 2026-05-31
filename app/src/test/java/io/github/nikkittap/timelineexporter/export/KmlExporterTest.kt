package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class KmlExporterTest {

    @Test
    fun `emits KML root element and document name`() {
        val kml = KmlExporter.export(emptyList(), "My Trip")
        assertTrue(kml.contains("""<kml xmlns="http://www.opengis.net/kml/2.2">"""))
        assertTrue(kml.contains("<name>My Trip</name>"))
    }

    @Test
    fun `coordinates are emitted in lon,lat,alt order`() {
        // KML reverses the GPX order. This is the #1 reason KML files render
        // "in the wrong country" — test guards against accidental flip.
        val kml = KmlExporter.export(
            listOf(PathPoint(Instant.parse("2025-01-01T00:00:00Z"), 41.284025, 69.242256)),
            "T",
        )
        assertTrue(
            "Expected lon,lat,alt — got: ${kml.lines().firstOrNull { it.contains("69.242") }}",
            kml.contains("69.242256,41.284025,0"),
        )
    }

    @Test
    fun `multiple points are separated by whitespace, not commas`() {
        val kml = KmlExporter.export(
            listOf(
                PathPoint(Instant.parse("2025-01-01T00:00:00Z"), 10.0, 20.0),
                PathPoint(Instant.parse("2025-01-01T00:01:00Z"), 11.0, 21.0),
            ),
            "T",
        )
        // Adjacent points should appear on different lines (or at least with
        // whitespace between them), not as "20.0,10.0,0,21.0,11.0,0".
        assertTrue(kml.contains("20.0,10.0,0"))
        assertTrue(kml.contains("21.0,11.0,0"))
        // No "...,0,21.0..." back-to-back without whitespace.
        assertTrue(!kml.contains("0,21.0,11.0,0"))
    }

    @Test
    fun `output is well-formed XML`() {
        val kml = KmlExporter.export(
            listOf(PathPoint(Instant.parse("2025-01-01T00:00:00Z"), 10.0, 20.0)),
            "Sample",
        )
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(kml.byteInputStream(Charsets.UTF_8))
        assertEquals("kml", doc.documentElement.tagName)
    }

    @Test
    fun `Exporter contract metadata is correct`() {
        assertEquals("KML", KmlExporter.displayName)
        assertEquals("kml", KmlExporter.fileExtension)
        assertEquals("application/vnd.google-earth.kml+xml", KmlExporter.mimeType)
    }
}
