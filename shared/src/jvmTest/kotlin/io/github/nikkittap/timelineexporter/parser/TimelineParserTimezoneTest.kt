package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The timezone plumbing added in v1.5.0: every PathPoint carries the UTC offset
 * that was in effect where it was recorded, so CSV can print a local-time
 * column. The rule under test throughout: an offset is only reported when the
 * file actually states one. Guessing would be worse than an empty cell.
 */
class TimelineParserTimezoneTest {

    @Test
    fun `timelinePath points take the offset from their own ISO timestamp`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-10T07:00:00.000+02:00",
                  "timelinePath": [
                    { "point": "41.28°, 69.24°", "time": "2025-06-10T07:04:00.000+02:00" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(120, result.pathPoints[0].tzOffsetMinutes)
    }

    @Test
    fun `segment offset field applies to points whose own time carries none`() {
        // durationMinutesOffsetFromStartTime instead of an absolute "time":
        // the point has no timestamp of its own to read an offset from.
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-10T07:00:00.000Z",
                  "startTimeTimezoneUtcOffsetMinutes": 300,
                  "timelinePath": [
                    { "point": "41.28°, 69.24°", "durationMinutesOffsetFromStartTime": "4" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(300, result.pathPoints[0].tzOffsetMinutes)
    }

    @Test
    fun `UTC-only exports report no offset instead of pretending it is zero`() {
        val json = """
            {
              "locations": [
                { "latitudeE7": 412840250, "longitudeE7": 692422560,
                  "timestamp": "2025-06-09T19:58:00Z" },
                { "latitudeE7": 412900000, "longitudeE7": 692500000,
                  "timestampMs": "1749499080000" }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(2, result.pathPoints.size)
        result.pathPoints.forEach { assertNull(it.tzOffsetMinutes) }
    }

    @Test
    fun `parseOffsetMinutes reads real offsets and rejects everything else`() {
        assertEquals(120, parseOffsetMinutes("2025-06-10T07:04:00.000+02:00"))
        assertEquals(-210, parseOffsetMinutes("2025-06-10T07:04:00.000-03:30"))
        // A genuine +00:00 in a phone export was recorded and really is zero.
        assertEquals(0, parseOffsetMinutes("2025-06-10T07:04:00.000+00:00"))
        // "Z" and epoch numbers state an instant, not a local clock.
        assertNull(parseOffsetMinutes("2025-06-10T07:04:00Z"))
        assertNull(parseOffsetMinutes("1749499080000"))
        assertNull(parseOffsetMinutes("not a timestamp"))
        assertNull(parseOffsetMinutes(null))
        assertNull(parseOffsetMinutes(""))
    }

    @Test
    fun `absurd offsets are discarded rather than exported`() {
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-10T07:00:00.000Z",
                  "startTimeTimezoneUtcOffsetMinutes": 99999,
                  "timelinePath": [
                    { "point": "41.28°, 69.24°", "durationMinutesOffsetFromStartTime": "4" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertNull(result.pathPoints[0].tzOffsetMinutes)
    }

    // ---------- historical offset back-fill ----------
    //
    // A timelinePath segment never states its timezone: not one of the 2872 in
    // the reference export carries startTimeTimezoneUtcOffsetMinutes. All its
    // points have is the offset baked into the ISO timestamp, and that turns
    // out to be the exporting phone's home offset rather than local time where
    // the point was taken. Visit and activity segments do state it, so the
    // parser corrects the path points against them once the file is read.

    @Test
    fun `a path point inside a visit takes the visit's stated offset`() {
        // Straight from the reference export: a point recorded in Tashkent,
        // stamped +02:00 because the phone lives in Stockholm, wrapped in a
        // visit that states the real +05:00.
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-09T21:00:00.000+02:00",
                  "endTime": "2025-06-09T23:00:00.000+02:00",
                  "timelinePath": [
                    { "point": "41.284025°, 69.242256°",
                      "time": "2025-06-09T21:58:00.000+02:00" }
                  ]
                },
                {
                  "startTime": "2025-06-09T21:57:46.000+02:00",
                  "endTime": "2025-06-10T07:03:44.000+02:00",
                  "startTimeTimezoneUtcOffsetMinutes": 300,
                  "visit": {
                    "hierarchyLevel": 0,
                    "topCandidate": {
                      "placeId": "ChIJhaL9pVWNrjgR-ngIJC_6jrQ",
                      "semanticType": "UNKNOWN",
                      "placeLocation": { "latLng": "41.2839476°, 69.2421459°" }
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        // Not the 120 the timestamp claims.
        assertEquals(300, result.pathPoints[0].tzOffsetMinutes)
    }

    @Test
    fun `a path point in a gap borrows from the nearest stated offset`() {
        // The point falls after the visit ends, with nothing else around it.
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-09T08:00:00.000Z",
                  "endTime": "2025-06-09T10:00:00.000Z",
                  "startTimeTimezoneUtcOffsetMinutes": 300,
                  "visit": {
                    "topCandidate": {
                      "placeId": "x",
                      "placeLocation": { "latLng": "41.28°, 69.24°" }
                    }
                  }
                },
                {
                  "startTime": "2025-06-09T11:00:00.000+02:00",
                  "endTime": "2025-06-09T12:00:00.000+02:00",
                  "timelinePath": [
                    { "point": "41.30°, 69.25°",
                      "time": "2025-06-09T11:00:00.000+02:00" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(300, result.pathPoints[0].tzOffsetMinutes)
    }

    @Test
    fun `a path point far from any stated offset keeps the one it had`() {
        // Six days from the only anchor. At that distance the visit says
        // nothing about where the point was, so guessing would be worse than
        // leaving the timestamp's own offset alone.
        val json = """
            {
              "semanticSegments": [
                {
                  "startTime": "2025-06-15T08:00:00.000Z",
                  "endTime": "2025-06-15T10:00:00.000Z",
                  "startTimeTimezoneUtcOffsetMinutes": 300,
                  "visit": {
                    "topCandidate": {
                      "placeId": "x",
                      "placeLocation": { "latLng": "41.28°, 69.24°" }
                    }
                  }
                },
                {
                  "startTime": "2025-06-09T11:00:00.000+02:00",
                  "endTime": "2025-06-09T12:00:00.000+02:00",
                  "timelinePath": [
                    { "point": "41.30°, 69.25°",
                      "time": "2025-06-09T11:00:00.000+02:00" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(120, result.pathPoints[0].tzOffsetMinutes)
    }

    @Test
    fun `an export that states no offsets anywhere is left untouched`() {
        // The older Takeout layouts have no startTimeTimezoneUtcOffsetMinutes
        // at all, and their ISO offsets are genuinely local. Nothing to correct
        // against, and nothing that should be corrected.
        val json = """
            {
              "timelineObjects": [
                {
                  "placeVisit": {
                    "location": { "latitudeE7": 412840250, "longitudeE7": 692422560 },
                    "duration": {
                      "startTimestamp": "2025-06-09T21:57:46.000+05:00",
                      "endTimestamp": "2025-06-10T07:03:44.000+05:00"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(1, result.pathPoints.size)
        assertEquals(300, result.pathPoints[0].tzOffsetMinutes)
    }
}
