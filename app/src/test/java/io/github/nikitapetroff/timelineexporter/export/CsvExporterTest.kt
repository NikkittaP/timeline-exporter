package io.github.nikitapetroff.timelineexporter.export

import io.github.nikitapetroff.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CsvExporterTest {

    @Test
    fun `first line is the column header`() {
        val csv = CsvExporter.export(emptyList(), "ignored")
        assertEquals("time_utc,latitude,longitude\r\n", csv)
    }

    @Test
    fun `each point produces one CRLF-terminated row`() {
        val csv = CsvExporter.export(
            listOf(
                PathPoint(Instant.parse("2025-06-09T19:58:00Z"), 41.284025, 69.242256),
                PathPoint(Instant.parse("2025-06-09T20:00:00Z"), 41.290000, 69.250000),
            ),
            "T",
        )
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(3, lines.size) // header + 2 data rows
        assertEquals("2025-06-09T19:58:00Z,41.284025,69.242256", lines[1])
        assertEquals("2025-06-09T20:00:00Z,41.29,69.25", lines[2])
    }

    @Test
    fun `uses CRLF line endings per RFC 4180`() {
        val csv = CsvExporter.export(
            listOf(PathPoint(Instant.parse("2025-01-01T00:00:00Z"), 1.0, 2.0)),
            "T",
        )
        assertTrue("Expected CRLF line endings", csv.contains("\r\n"))
        // No bare LF anywhere.
        val withoutCrlf = csv.replace("\r\n", "")
        assertTrue("Found bare LF in output", !withoutCrlf.contains("\n"))
    }

    @Test
    fun `track name is intentionally ignored — CSV has no metadata channel`() {
        val withName = CsvExporter.export(emptyList(), "Some Trip")
        val withoutName = CsvExporter.export(emptyList(), "")
        assertEquals(withName, withoutName)
    }

    @Test
    fun `Exporter contract metadata is correct`() {
        assertEquals("CSV", CsvExporter.displayName)
        assertEquals("csv", CsvExporter.fileExtension)
        assertEquals("text/csv", CsvExporter.mimeType)
    }
}
