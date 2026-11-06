package io.github.nikkittap.timelineexporter.export

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import io.github.nikkittap.timelineexporter.parser.parseTimeline
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import javax.xml.parsers.SAXParserFactory
import kotlin.system.measureTimeMillis

/**
 * `Color by day` export against the largest export on the developer's disk:
 * a merged 115 MB file, several years of track. Skipped where it is absent.
 *
 * What it guards is the size, not the shape - [ColorByDayExportTest] pins the
 * shape. A day split that is quadratic somewhere, or that assembles the whole
 * document, shows up here as minutes or an OutOfMemoryError, where twenty
 * synthetic points would never notice. Both outputs are read back with
 * streaming parsers for the same reason: loading a 100 MB file into a DOM to
 * check it would test the test's heap, not the exporter.
 *
 * Run via:  ./gradlew :app:testDebugUnitTest --tests "*ColorByDayRealFile*" -i
 */
class ColorByDayRealFileTest {

    private val realFile: Path = Path.of("F:/Timeline/Timeline_merged_big.json")
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")

    @Test
    fun `a multi-year track exports by day to kml and geojson`() {
        assumeTrue("Skipped: $realFile not present (developer-local only).", Files.exists(realFile))

        val points = Files.newInputStream(realFile).use { parseTimeline(it) }.pathPoints
        val days = trackDays(points, zone)
        // Every edge lands in exactly one day, which is the whole contract.
        val edges = days.sumOf { day -> day.runs.sumOf { it.last - it.first } }
        println("${points.size} points, ${days.size} days, ${edges} edges")
        assertEquals(points.size - 1, edges)

        val content = ExportContent.Track(points, colorByDay = true, fallbackZone = zone)
        val plain = ExportContent.Track(points)

        for (exporter in listOf(KmlExporter, GeoJsonExporter)) {
            val byDayFile = File.createTempFile("byday", ".${exporter.fileExtension}")
            val plainFile = File.createTempFile("plain", ".${exporter.fileExtension}")
            try {
                val byDayMs = measureTimeMillis {
                    byDayFile.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { exporter.export(content, "Big", it) }
                }
                val plainMs = measureTimeMillis {
                    plainFile.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { exporter.export(plain, "Big", it) }
                }
                println(
                    "${exporter.displayName}: by day ${byDayMs} ms, ${byDayFile.length() shr 20} MB; " +
                        "plain ${plainMs} ms, ${plainFile.length() shr 20} MB",
                )

                val (lines, positions) = when (exporter) {
                    KmlExporter -> countKml(byDayFile)
                    else -> countGeoJson(byDayFile)
                }
                assertEquals("${exporter.displayName} day count", days.size, lines)
                // Each run repeats the sample it shares with the run before.
                val runs = days.sumOf { it.runs.size }
                assertEquals("${exporter.displayName} positions", points.size + runs - 1, positions)
            } finally {
                byDayFile.delete()
                plainFile.delete()
            }
        }
    }

    /** Placemarks and coordinate tuples in a KML, read with SAX. */
    private fun countKml(file: File): Pair<Int, Int> {
        var placemarks = 0
        var tuples = 0
        var inCoordinates = false
        var pending = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (qName == "Placemark") placemarks++
                if (qName == "coordinates") inCoordinates = true
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (qName == "coordinates") {
                    tuples += countTuples(pending)
                    pending = StringBuilder()
                    inCoordinates = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (!inCoordinates) return
                pending.append(ch, start, length)
                // Count finished lines as they come, so the buffer never
                // holds more than one partial tuple of a huge day.
                val cut = pending.lastIndexOf("\n")
                if (cut >= 0) {
                    tuples += countTuples(pending.substring(0, cut))
                    pending = StringBuilder(pending.substring(cut + 1))
                }
            }
        }
        SAXParserFactory.newInstance().newSAXParser().parse(file, handler)
        return placemarks to tuples
    }

    private fun countTuples(text: CharSequence): Int =
        text.split('\n', ' ', '\t', '\r').count { it.isNotBlank() }

    /** Features and `[lon, lat]` positions in a GeoJSON, read with Jackson's pull parser. */
    private fun countGeoJson(file: File): Pair<Int, Int> {
        var features = 0
        var positions = 0
        JsonFactory().createParser(file).use { parser ->
            var depth = 0
            var arrayRun = 0
            while (true) {
                val token = parser.nextToken() ?: break
                when (token) {
                    JsonToken.START_OBJECT -> { depth++; arrayRun = 0 }
                    JsonToken.END_OBJECT -> depth--
                    JsonToken.START_ARRAY -> arrayRun = 0
                    JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT -> {
                        // A position is an array of exactly two numbers; the
                        // only other number in a day feature is stroke-width.
                        if (parser.parsingContext.inArray()) {
                            arrayRun++
                            if (arrayRun == 2) positions++
                        }
                    }
                    JsonToken.VALUE_STRING -> if (parser.currentName() == "type" && parser.text == "Feature") features++
                    else -> Unit
                }
            }
        }
        return features to positions
    }
}
