package io.github.nikkittap.timelineexporter.golden

import io.github.nikkittap.timelineexporter.export.AllExporters
import io.github.nikkittap.timelineexporter.export.ExportContent
import io.github.nikkittap.timelineexporter.export.ExportPlace
import io.github.nikkittap.timelineexporter.export.exportTrips
import io.github.nikkittap.timelineexporter.parser.ParsedTimeline
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import io.github.nikkittap.timelineexporter.parser.placeEntries
import io.github.nikkittap.timelineexporter.parser.resolvePlaceName
import io.github.nikkittap.timelineexporter.trips.TripDetection
import io.github.nikkittap.timelineexporter.trips.detectTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.ZoneId

/**
 * Pins every export, byte for byte, so the stage-0 move of the core code into
 * the shared module can prove it changed nothing.
 *
 * Each line of `golden/export-hashes.txt` is `input|format|content|sha256`.
 * Record with `-Dgolden.record=true`; otherwise compare. Developer-local
 * inputs that are missing are skipped, never failed.
 */
class GoldenExportTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private class Input(val name: String, val load: () -> ParsedTimeline)

    private val phoneObject = """
        { "semanticSegments": [
            { "startTime": "2025-01-01T00:00:00Z",
              "timelinePath": [ { "point": "10.0\u00b0, 20.0\u00b0", "time": "2025-01-01T00:30:00Z" } ] }
        ] }
    """.trimIndent()

    private val phoneArray = """
        [
          { "startTime": "2025-01-01T00:00:00Z",
            "timelinePath": [
              { "point": "10.0\u00b0, 20.0\u00b0", "time": "2025-01-01T00:10:00Z" },
              { "point": "11.0\u00b0, 21.0\u00b0", "time": "2025-01-01T00:20:00Z" }
            ] },
          { "startTime": "2025-01-01T01:00:00Z",
            "visit": { "topCandidate": { "placeLocation": { "latLng": "12.0\u00b0, 22.0\u00b0" } } } }
        ]
    """.trimIndent()

    private val minuteOffset = """
        { "semanticSegments": [
            { "startTime": "2025-01-01T00:00:00Z",
              "timelinePath": [
                { "point": "10.0\u00b0, 20.0\u00b0", "durationMinutesOffsetFromStartTime": "15" }
              ] }
        ] }
    """.trimIndent()

    private val localFiles = listOf(
        "Timeline", "Timeline_1", "Timeline_2", "Timeline_merged",
        "Timeline_merged_compact", "Timeline_merged_nosignals", "Timeline_merged_big",
    )

    private fun inputs(): List<Input> {
        val list = mutableListOf(
            Input("inline-phone-object") { parseTimeline(phoneObject) },
            Input("inline-phone-array") { parseTimeline(phoneArray) },
            Input("inline-minute-offset") { parseTimeline(minuteOffset) },
        )
        for (n in localFiles) {
            val path = Path.of("F:/Timeline/$n.json")
            if (Files.exists(path)) {
                list += Input(n) { BufferedInputStream(Files.newInputStream(path)).use { parseTimeline(it) } }
            }
        }
        return list
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // Mirrors ui/PlacesContent.kt `exportPlaces`, with the English "Home" and
    // "Work" words and no user labels (what the app shows when nothing is
    // named). resolvePlaceName lives in parser/PlaceNames.kt.
    private fun places(parsed: ParsedTimeline): List<ExportPlace> =
        parsed.placeEntries(fallbackZone = zone).map { entry ->
            ExportPlace(
                name = resolvePlaceName(entry, null, "Home", "Work"),
                latitude = entry.latitude,
                longitude = entry.longitude,
                placeId = entry.placeId,
                category = entry.semanticType,
                address = entry.address,
                visits = entry.visits,
                firstVisit = entry.firstVisit,
                lastVisit = entry.lastVisit,
                firstVisitOffsetMinutes = entry.firstVisitOffsetMinutes,
                lastVisitOffsetMinutes = entry.lastVisitOffsetMinutes,
                totalSeconds = entry.totalSeconds,
            )
        }

    private fun hashesFor(input: Input): List<String> {
        val parsed = input.load()
        val points = parsed.pathPoints
        val contents = mutableListOf<Pair<String, ExportContent>>()
        contents += "track" to ExportContent.Track(points, colorByDay = false, fallbackZone = zone)
        contents += "track-byday" to ExportContent.Track(points, colorByDay = true, fallbackZone = zone)
        val found = detectTrips(parsed, zone)
        if (found is TripDetection.Found) {
            contents += "trips" to ExportContent.Trips(exportTrips(found.trips, points, zone))
        }
        contents += "places" to ExportContent.Places(places(parsed))
        val lines = mutableListOf<String>()
        for (exporter in AllExporters) {
            for ((label, content) in contents) {
                val sb = StringBuilder()
                exporter.export(content, "golden", sb)
                lines += "${input.name}|${exporter.fileExtension}|$label|${sha256(sb.toString())}"
            }
        }
        return lines
    }

    @Test
    fun `exports match the golden hashes`() {
        val file = Path.of("src/jvmTest/resources/golden/export-hashes.txt").toAbsolutePath()
        val all = inputs()
        val produced = all.flatMap { hashesFor(it) }

        if (System.getProperty("golden.record") == "true") {
            Files.createDirectories(file.parent)
            Files.writeString(file, produced.sorted().joinToString("\n", postfix = "\n"))
            println("Recorded ${produced.size} golden hashes to $file")
            return
        }

        val expected = Files.readAllLines(file).filter { it.isNotBlank() }
        fun key(line: String) = line.substringBeforeLast('|')
        val producedMap = produced.associateBy { key(it) }
        val presentInputs = all.map { it.name }.toSet()

        for (line in expected) {
            if (line.substringBefore('|') !in presentInputs) continue // local file missing here
            val got = producedMap[key(line)]
            assertTrue("Golden line no longer produced: ${key(line)}", got != null)
            assertEquals("Export changed for ${key(line)}", line, got)
        }
        val expectedKeys = expected.map { key(it) }.toSet()
        for (k in producedMap.keys) {
            assertTrue("Produced but not in golden file: $k (re-record with -Dgolden.record=true)", k in expectedKeys)
        }
    }
}
