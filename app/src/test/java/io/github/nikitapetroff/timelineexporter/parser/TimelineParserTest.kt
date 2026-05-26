package io.github.nikitapetroff.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineParserTest {

    @Test
    fun `extracts path points and counts segment types`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-10T07:00:00.000+02:00",
                  "endTime":   "2025-06-10T09:00:00.000+02:00",
                  "timelinePath": [
                    { "point": "41.2836881°, 69.2418352°", "time": "2025-06-10T07:04:00.000+02:00" },
                    { "point": "41.2924773°, 69.2792992°", "time": "2025-06-10T07:21:00.000+02:00" }
                  ]
                },
                {
                  "startTime": "2025-06-10T09:00:00.000+02:00",
                  "endTime":   "2025-06-10T09:30:00.000+02:00",
                  "visit": {
                    "probability": 0.74,
                    "topCandidate": {
                      "placeId": "ChIJxxx",
                      "placeLocation": { "latLng": "41.2839°, 69.2421°" }
                    }
                  }
                },
                {
                  "startTime": "2025-06-10T09:30:00.000+02:00",
                  "endTime":   "2025-06-10T10:00:00.000+02:00",
                  "activity": {
                    "start": { "latLng": "41.28°, 69.24°" },
                    "end":   { "latLng": "41.62°, 69.93°" },
                    "distanceMeters": 73958.46,
                    "topCandidate": { "type": "IN_PASSENGER_VEHICLE", "probability": 0.85 }
                  }
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)

        assertEquals(3, result.totalSegments)
        assertEquals(1, result.pathSegments)
        assertEquals(1, result.visitSegments)
        assertEquals(1, result.activitySegments)
        assertEquals(2, result.pathPoints.size)
    }

    @Test
    fun `path points are sorted by time even when JSON is unordered`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-01-01T00:00:00Z",
                  "endTime":   "2025-01-01T01:00:00Z",
                  "timelinePath": [
                    { "point": "10.0°, 20.0°", "time": "2025-01-01T00:30:00Z" },
                    { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" },
                    { "point": "10.0°, 20.0°", "time": "2025-01-01T00:20:00Z" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val times = parseTimeline(json).pathPoints.map { it.timeUtc }

        assertEquals(
            listOf(
                Instant.parse("2025-01-01T00:10:00Z"),
                Instant.parse("2025-01-01T00:20:00Z"),
                Instant.parse("2025-01-01T00:30:00Z"),
            ),
            times,
        )
    }

    @Test
    fun `tolerates malformed entries by skipping them`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-01-01T00:00:00Z",
                  "endTime":   "2025-01-01T01:00:00Z",
                  "timelinePath": [
                    { "point": "GARBAGE",        "time": "2025-01-01T00:10:00Z" },
                    { "point": "10.0°, 20.0°",   "time": "NOT A DATE" },
                    { "point": "10.0°, 20.0°",   "time": "2025-01-01T00:20:00Z" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)

        // Only the third row is fully valid; the first two are silently dropped.
        assertEquals(1, result.pathPoints.size)
        assertEquals(10.0, result.pathPoints[0].latitude, 0.0)
        assertEquals(20.0, result.pathPoints[0].longitude, 0.0)
    }

    @Test
    fun `tolerates unknown fields without crashing`() {
        // Simulates Google adding a new field we haven't modeled yet.
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-01-01T00:00:00Z",
                  "endTime":   "2025-01-01T01:00:00Z",
                  "futureFeature": { "answer": 42, "nested": ["one", "two"] },
                  "timelinePath": [
                    { "point": "10.0°, 20.0°", "time": "2025-01-01T00:30:00Z", "speed": 5.2 }
                  ]
                }
              ],
              "topLevelExtra": "ignored"
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
    }

    @Test
    fun `empty file produces empty result`() {
        val result = parseTimeline("""{ "semanticSegments": [] }""")
        assertEquals(0, result.totalSegments)
        assertEquals(0, result.pathPoints.size)
        assertTrue(result.isEmpty)
    }

    // ----- helpers unit-tested directly -----

    @Test
    fun `parseCoordinatePair handles plain degree symbol`() {
        val coords = parseCoordinatePair("41.284025°, 69.242256°")
        assertNotNull(coords)
        assertEquals(41.284025, coords!!.first, 0.0)
        assertEquals(69.242256, coords.second, 0.0)
    }

    @Test
    fun `parseCoordinatePair handles 'В' encoding artefact`() {
        // Simulates a "В°" sequence from a misencoded file.
        val coords = parseCoordinatePair("41.284025В°, 69.242256В°")
        assertNotNull(coords)
        assertEquals(41.284025, coords!!.first, 1e-9)
    }

    @Test
    fun `parseCoordinatePair rejects malformed input`() {
        assertNull(parseCoordinatePair("not a coord"))
        assertNull(parseCoordinatePair("41.0"))             // missing longitude
        assertNull(parseCoordinatePair("41.0, abc"))        // longitude not a number
        assertNull(parseCoordinatePair("41.0, 20.0, 30.0")) // too many parts
    }

    @Test
    fun `parseIsoInstant normalizes timezone offsets to UTC`() {
        val instant = parseIsoInstant("2025-06-09T21:58:00.000+02:00")
        assertEquals(Instant.parse("2025-06-09T19:58:00Z"), instant)
    }
}
