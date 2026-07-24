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
}
