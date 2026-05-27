package io.github.nikitapetroff.timelineexporter.export

import io.github.nikitapetroff.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class GpxExporterTest {

    @Test
    fun `emits XML declaration, root gpx element, and the expected metadata`() {
        val gpx = GpxExporter.export(emptyList(), trackName = "My Track")
        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(gpx.contains("""<gpx version="1.1""""))
        assertTrue(gpx.contains("""xmlns="http://www.topografix.com/GPX/1/1""""))
        assertTrue(gpx.contains("<name>My Track</name>"))
    }

    @Test
    fun `serializes a point with lat, lon, and ISO-instant time`() {
        val gpx = GpxExporter.export(
            listOf(PathPoint(Instant.parse("2025-06-09T19:58:00Z"), 41.284025, 69.242256)),
            trackName = "T",
        )
        assertTrue(gpx.contains("""<trkpt lat="41.284025" lon="69.242256">"""))
        assertTrue(gpx.contains("<time>2025-06-09T19:58:00Z</time>"))
    }

    @Test
    fun `escapes XML-special characters in track name`() {
        val gpx = GpxExporter.export(
            points = emptyList(),
            trackName = "Trip <Tashkent & Malmö>",
        )
        assertTrue(gpx.contains("Trip &lt;Tashkent &amp; Malmö&gt;"))
        assertFalse(gpx.contains("Trip <Tashkent"))
    }

    @Test
    fun `output is well-formed XML — parseable by standard parser`() {
        val gpx = GpxExporter.export(
            listOf(
                PathPoint(Instant.parse("2025-06-09T19:58:00Z"), 41.284, 69.242),
                PathPoint(Instant.parse("2025-06-09T20:00:00Z"), 41.290, 69.250),
            ),
            trackName = "Sample",
        )
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(gpx.byteInputStream(Charsets.UTF_8))

        assertEquals("gpx", doc.documentElement.tagName)
        assertEquals(2, doc.getElementsByTagName("trkpt").length)
    }

    @Test
    fun `Exporter contract metadata is correct`() {
        assertEquals("GPX", GpxExporter.displayName)
        assertEquals("gpx", GpxExporter.fileExtension)
        assertEquals("application/gpx+xml", GpxExporter.mimeType)
    }
}
