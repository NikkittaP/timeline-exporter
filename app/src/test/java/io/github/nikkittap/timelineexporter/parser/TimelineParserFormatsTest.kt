package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Coverage for the multi-format detection added so the app keeps working if
 * Google ships (or has already shipped) a different export layout.
 */
class TimelineParserFormatsTest {

    @Test
    fun `detects phone takeout object format`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z",
                  "timelinePath": [ { "point": "10.0°, 20.0°", "time": "2025-01-01T00:30:00Z" } ] }
            ] }
        """.trimIndent()
        val result = parseTimeline(json)
        assertEquals(TimelineFormat.PHONE_TAKEOUT, result.format)
        assertEquals(1, result.pathPoints.size)
    }

    @Test
    fun `parses phone takeout iOS array variant`() {
        // Top level is a bare array of segments, not wrapped in an object.
        val json = """
            [
              { "startTime": "2025-01-01T00:00:00Z",
                "timelinePath": [
                  { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" },
                  { "point": "11.0°, 21.0°", "time": "2025-01-01T00:20:00Z" }
                ] },
              { "startTime": "2025-01-01T01:00:00Z",
                "visit": { "topCandidate": { "placeLocation": { "latLng": "12.0°, 22.0°" } } } }
            ]
        """.trimIndent()
        val result = parseTimeline(json)
        assertEquals(TimelineFormat.PHONE_TAKEOUT_ARRAY, result.format)
        assertEquals(2, result.totalSegments)
        assertEquals(2, result.pathPoints.size)
        assertEquals(1, result.visitSegments)
    }

    @Test
    fun `derives point time from minute offset when time is absent`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z",
                  "timelinePath": [
                    { "point": "10.0°, 20.0°", "durationMinutesOffsetFromStartTime": "15" }
                  ] }
            ] }
        """.trimIndent()
        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(Instant.parse("2025-01-01T00:15:00Z"), result.pathPoints[0].timeUtc)
    }

    @Test
    fun `parses semantic location history timelineObjects with E7 coords`() {
        val json = """
            { "timelineObjects": [
                { "activitySegment": {
                    "duration": { "startTimestamp": "2024-03-15T07:05:14Z",
                                  "endTimestamp": "2024-03-15T07:11:13Z" },
                    "simplifiedRawPath": { "points": [
                        { "latE7": 533410301, "lngE7": 837051010, "timestampMs": "1710486314222" }
                    ] } } },
                { "placeVisit": {
                    "location": { "latitudeE7": 533690550, "longitudeE7": 836950010 },
                    "duration": { "startTimestamp": "2024-03-15T08:00:00Z" } } }
            ] }
        """.trimIndent()
        val result = parseTimeline(json)
        assertEquals(TimelineFormat.SEMANTIC_LOCATION_HISTORY, result.format)
        assertEquals(2, result.totalSegments)
        assertEquals(1, result.pathSegments)
        assertEquals(1, result.visitSegments)
        assertEquals(2, result.pathPoints.size)
        // E7 53.3690550 / 83.6950010
        val visit = result.pathPoints.first { it.timeUtc == Instant.parse("2024-03-15T08:00:00Z") }
        assertEquals(53.3690550, visit.latitude, 1e-7)
        assertEquals(83.6950010, visit.longitude, 1e-7)
    }

    @Test
    fun `falls back to waypoints when no raw path is present`() {
        val json = """
            { "timelineObjects": [
                { "activitySegment": {
                    "startLocation": { "latitudeE7": 100000000, "longitudeE7": 200000000 },
                    "endLocation":   { "latitudeE7": 110000000, "longitudeE7": 210000000 },
                    "duration": { "startTimestamp": "2024-03-15T07:00:00Z",
                                  "endTimestamp": "2024-03-15T07:30:00Z" },
                    "waypointPath": { "waypoints": [
                        { "latE7": 105000000, "lngE7": 205000000 }
                    ] } } }
            ] }
        """.trimIndent()
        val result = parseTimeline(json)
        // start + waypoint + end = 3 points
        assertEquals(3, result.pathPoints.size)
    }

    @Test
    fun `parses records json locations with epoch millis`() {
        val json = """
            { "locations": [
                { "latitudeE7": 533690550, "longitudeE7": 836950010, "timestampMs": "1718460089460" },
                { "latitudeE7": 533690560, "longitudeE7": 836950020, "timestamp": "2024-06-15T14:25:00Z" }
            ] }
        """.trimIndent()
        val result = parseTimeline(json)
        assertEquals(TimelineFormat.RECORDS, result.format)
        assertEquals(2, result.pathPoints.size)
        assertEquals(53.3690550, result.pathPoints[0].latitude, 1e-7)
    }

    @Test
    fun `falls back to rawSignals positions when no semantic path exists`() {
        val json = """
            { "rawSignals": [
                { "position": { "LatLng": "48.833657°, 2.256223°",
                                "timestamp": "2024-06-06T11:44:37.000+01:00" } }
            ] }
        """.trimIndent()
        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(48.833657, result.pathPoints[0].latitude, 1e-7)
    }

    @Test(expected = NotTimelineFileException::class)
    fun `unknown format throws`() {
        parseTimeline("""{ "somethingElse": [] }""")
    }

    // ----- low-level helpers -----

    @Test
    fun `parseTimestamp handles epoch seconds and millis`() {
        assertEquals(Instant.ofEpochSecond(1718460089), parseTimestamp("1718460089"))
        assertEquals(Instant.ofEpochMilli(1718460089460), parseTimestamp("1718460089460"))
    }

    @Test
    fun `parseTimestamp handles Z and offset`() {
        assertEquals(Instant.parse("2024-06-15T14:21:29Z"), parseTimestamp("2024-06-15T14:21:29Z"))
        assertEquals(Instant.parse("2025-06-09T19:58:00Z"), parseTimestamp("2025-06-09T21:58:00.000+02:00"))
    }

    @Test
    fun `parseTimestamp rejects junk`() {
        assertNull(parseTimestamp("not a date"))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp(null))
    }

    @Test
    fun `e7Pair converts integer degrees`() {
        val coords = e7Pair(533690550, 836950010)
        assertNotNull(coords)
        assertEquals(53.3690550, coords!!.first, 1e-9)
        assertEquals(83.6950010, coords.second, 1e-9)
        assertNull(e7Pair(null, 1))
    }

    @Test
    fun `parseCoordinatePair strips geo prefix`() {
        val coords = parseCoordinatePair("geo:37.7749,-122.4194")
        assertNotNull(coords)
        assertEquals(37.7749, coords!!.first, 1e-9)
        assertEquals(-122.4194, coords.second, 1e-9)
    }

    @Test
    fun `array variant still reports stages correctly`() {
        val json = """[ { "startTime": "2025-01-01T00:00:00Z",
            "timelinePath": [ { "point": "10.0°, 20.0°", "time": "2025-01-01T00:30:00Z" } ] } ]"""
        val stages = mutableListOf<ParserStage>()
        parseTimeline(json) { stages += it }
        assertEquals(ParserStage.DecodingJson, stages.first())
        assertEquals(ParserStage.Sorting, stages.last())
        assertTrue(stages.any { it is ParserStage.ExtractingSegments })
    }
}
