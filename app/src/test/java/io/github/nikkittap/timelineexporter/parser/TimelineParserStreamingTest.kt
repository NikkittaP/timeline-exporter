package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests for the streaming parser: the InputStream entry point, the rawSignals
 * fallback semantics (must NOT be double-counted when path points exist), and
 * a large synthetic input to exercise the streaming path at scale.
 */
class TimelineParserStreamingTest {

    @Test
    fun `InputStream overload matches the String overload`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z",
                  "timelinePath": [
                    { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" },
                    { "point": "11.0°, 21.0°", "time": "2025-01-01T00:20:00Z" }
                  ] }
            ] }
        """.trimIndent()

        val fromString = parseTimeline(json)
        val fromStream = parseTimeline(json.byteInputStream())

        assertEquals(TimelineFormat.PHONE_TAKEOUT, fromStream.format)
        assertEquals(fromString.pathPoints, fromStream.pathPoints)
        assertEquals(2, fromStream.pathPoints.size)
    }

    @Test
    fun `rawSignals are ignored when timelinePath points exist (segments first)`() {
        // The real-world merged-file case: a file has both. rawSignals must be a
        // pure fallback, never an additional source — otherwise points double up.
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z",
                  "timelinePath": [ { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" } ] }
              ],
              "rawSignals": [
                { "position": { "LatLng": "48.8°, 2.2°", "timestamp": "2025-01-01T00:11:00Z" } }
              ] }
        """.trimIndent()

        val result = parseTimeline(json.byteInputStream())
        assertEquals(1, result.pathPoints.size)
        assertEquals(10.0, result.pathPoints[0].latitude, 0.0)
    }

    @Test
    fun `rawSignals still ignored when they appear before semanticSegments`() {
        val json = """
            { "rawSignals": [
                { "position": { "LatLng": "48.8°, 2.2°", "timestamp": "2025-01-01T00:11:00Z" } }
              ],
              "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z",
                  "timelinePath": [ { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" } ] }
              ] }
        """.trimIndent()

        val result = parseTimeline(json.byteInputStream())
        assertEquals(1, result.pathPoints.size)
        assertEquals(10.0, result.pathPoints[0].latitude, 0.0)
    }

    @Test
    fun `rawSignals are used only when there are no path points`() {
        val json = """
            { "semanticSegments": [],
              "rawSignals": [
                { "position": { "LatLng": "48.833657°, 2.256223°", "timestamp": "2024-06-06T11:44:37Z" } }
              ] }
        """.trimIndent()

        val result = parseTimeline(json.byteInputStream())
        assertEquals(1, result.pathPoints.size)
        assertEquals(48.833657, result.pathPoints[0].latitude, 1e-7)
    }

    @Test
    fun `streams a large input and sorts correctly`() {
        // 5,000 segments, one point each, emitted in DESCENDING time order so we
        // also confirm the final sort works across the whole stream.
        val sb = StringBuilder()
        sb.append("{ \"semanticSegments\": [")
        val n = 5_000
        val epoch = Instant.parse("2025-01-01T00:00:00Z")
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            // Minute-spaced and descending. Built by Instant arithmetic rather
            // than formatting minute/60 as the hour: past i = 1440 that rolls
            // past hour 23 and yields timestamps like "T83:20:00Z", which the
            // parser rightly drops — the test then measured the drop, not the
            // stream.
            val ts = epoch.plusSeconds((n - i) * 60L).toString()
            sb.append(
                "{ \"startTime\": \"$ts\", \"timelinePath\": [ { \"point\": \"10.0°, 20.0°\", \"time\": \"$ts\" } ] }"
            )
        }
        sb.append("] }")

        val result = parseTimeline(sb.toString().byteInputStream())

        assertEquals(n, result.totalSegments)
        assertEquals(n, result.pathSegments)
        assertEquals(n, result.pathPoints.size)
        // Sorted ascending after the stream completes.
        val times = result.pathPoints.map { it.timeUtc }
        assertTrue(times.zipWithNext().all { (a, b) -> !a.isAfter(b) })
    }

    @Test
    fun `reports byte-independent progress stages on the stream path`() {
        val json = """[ { "startTime": "2025-01-01T00:00:00Z",
            "timelinePath": [ { "point": "10.0°, 20.0°", "time": "2025-01-01T00:30:00Z" } ] } ]"""
        val stages = mutableListOf<ParserStage>()
        parseTimeline(json.byteInputStream()) { stages += it }
        assertEquals(ParserStage.DecodingJson, stages.first())
        assertEquals(ParserStage.Sorting, stages.last())
        assertTrue(stages.any { it is ParserStage.ExtractingSegments })
    }
}
