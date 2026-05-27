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
        val gpx = buildGpx(emptyList(), trackName = "My Track")
        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(gpx.contains("""<gpx version="1.1""""))
        assertTrue(gpx.contains("""xmlns="http://www.topografix.com/GPX/1/1""""))
        assertTrue(gpx.contains("<name>My Track</name>"))
    }

    @Test
    fun `serializes a point with lat, lon, and ISO-instant time`() {
        val gpx = buildGpx(
            listOf(PathPoint(Instant.parse("2025-06-09T19:58:00Z"), 41.284025, 69.242256))
        )
        assertTrue(gpx.contains("""<trkpt lat="41.284025" lon="69.242256">"""))
        assertTrue(gpx.contains("<time>2025-06-09T19:58:00Z</time>"))
    }

    @Test
    fun `escapes XML-special characters in track name and creator`() {
        val gpx = buildGpx(
            points = emptyList(),
            trackName = "Trip <Tashkent & Malmö>",
            creator = """My"App""",
        )
        assertTrue(gpx.contains("Trip &lt;Tashkent &amp; Malmö&gt;"))
        assertTrue(gpx.contains("""creator="My&quot;App""""))
        assertFalse(gpx.contains("Trip <Tashkent"))
    }

    @Test
    fun `output is well-formed XML — parseable by standard parser`() {
        // The strongest correctness check we can do without a GPX schema validator:
        // feed the result into a real XML parser and assert it doesn't throw.
        val gpx = buildGpx(
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
}
