package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class CsvExporterTest {

    @Test
    fun `first line is the column header`() {
        val csv = CsvExporter.export(emptyList(), "ignored")
        assertEquals("time_utc,latitude,longitude,time_local,utc_offset\r\n", csv)
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
        assertEquals("2025-06-09T19:58:00Z,41.284025,69.242256,,", lines[1])
        assertEquals("2025-06-09T20:00:00Z,41.29,69.25,,", lines[2])
    }

    @Test
    fun `local time column uses the point's own offset`() {
        val csv = CsvExporter.export(
            listOf(
                PathPoint(Instant.parse("2025-06-09T19:58:00Z"), 41.284025, 69.242256, 300),
            ),
            "T",
        )
        val row = csv.split("\r\n")[1]
        assertEquals(
            "2025-06-09T19:58:00Z,41.284025,69.242256,2025-06-10 00:58:00,+05:00",
            row,
        )
    }

    @Test
    fun `negative and zero offsets are written in fixed +HH MM form`() {
        val csv = CsvExporter.export(
            listOf(
                PathPoint(Instant.parse("2025-01-01T12:00:00Z"), 1.0, 2.0, -210),
                PathPoint(Instant.parse("2025-01-01T12:00:00Z"), 1.0, 2.0, 0),
            ),
            "T",
        )
        val lines = csv.split("\r\n")
        assertTrue(lines[1].endsWith("2025-01-01 08:30:00,-03:30"))
        assertTrue(lines[2].endsWith("2025-01-01 12:00:00,+00:00"))
    }

    @Test
    fun `unknown timezone leaves both local columns empty rather than guessing`() {
        val csv = CsvExporter.export(
            listOf(PathPoint(Instant.parse("2025-01-01T12:00:00Z"), 1.0, 2.0, null)),
            "T",
        )
        assertEquals("2025-01-01T12:00:00Z,1.0,2.0,,", csv.split("\r\n")[1])
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
    fun `output stays ASCII under a locale with its own digits`() {
        // A CSV is data, not UI. Under ar-EG a default-locale formatter would
        // emit Arabic-Indic digits and produce a file no spreadsheet can read.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val csv = CsvExporter.export(
                listOf(PathPoint(Instant.parse("2025-06-09T19:58:00Z"), 41.0, 69.0, 300)),
                "T",
            )
            assertEquals(
                "2025-06-09T19:58:00Z,41.0,69.0,2025-06-10 00:58:00,+05:00",
                csv.split("\r\n")[1],
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `Exporter contract metadata is correct`() {
        assertEquals("CSV", CsvExporter.displayName)
        assertEquals("csv", CsvExporter.fileExtension)
        assertEquals("text/csv", CsvExporter.mimeType)
    }
}
