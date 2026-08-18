package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the segment structure the parser now keeps instead of throwing
 * away: movement type, Google's own distance, and the place a visit matched.
 *
 * Field names and shapes below are copied from a real phone export, including
 * the degree symbols in `latLng` and the nesting under `topCandidate`.
 */
class TimelineSegmentsTest {

    @Test
    fun `activity segment keeps type, distance and confidence`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-06-10T07:03:44.000+02:00",
                  "endTime": "2025-06-10T08:41:07.000+02:00",
                  "startTimeTimezoneUtcOffsetMinutes": 300,
                  "activity": {
                    "start": { "latLng": "41.2836881°, 69.2418352°" },
                    "end": { "latLng": "41.622922°, 69.9346999°" },
                    "distanceMeters": 73958.4609375,
                    "probability": 0.993340790271759,
                    "topCandidate": {
                      "type": "IN_PASSENGER_VEHICLE",
                      "probability": 0.8569924831390381
                    }
                  } }
            ] }
        """.trimIndent()

        val result = parseTimeline(json)
        val activity = result.activities.single()

        assertEquals("IN_PASSENGER_VEHICLE", activity.activityType)
        assertEquals(MovementGroup.DRIVING, activity.movement)
        assertEquals(73958.46, activity.distanceMeters!!, 0.01)
        assertEquals(0.857, activity.activityProbability!!, 0.001)
        assertEquals(300, activity.tzOffsetMinutes)
        assertTrue("an activity carries no track of its own", activity.points.isEmpty())
    }

    @Test
    fun `visit segment keeps place id, semantic type and coordinates`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-06-09T21:57:46.000+02:00",
                  "endTime": "2025-06-10T07:03:44.000+02:00",
                  "visit": {
                    "hierarchyLevel": 0,
                    "probability": 0.7404090762138367,
                    "topCandidate": {
                      "placeId": "ChIJhaL9pVWNrjgR-ngIJC_6jrQ",
                      "semanticType": "INFERRED_HOME",
                      "probability": 0.9460377097129822,
                      "placeLocation": { "latLng": "41.2839476°, 69.2421459°" }
                    }
                  } }
            ] }
        """.trimIndent()

        val place = parseTimeline(json).visits.single().place
        assertNotNull(place)
        assertEquals("ChIJhaL9pVWNrjgR-ngIJC_6jrQ", place!!.placeId)
        assertEquals("INFERRED_HOME", place.semanticType)
        assertEquals(41.2839476, place.latitude, 1e-7)
        assertEquals(69.2421459, place.longitude, 1e-7)
        assertEquals(0.740, place.probability!!, 0.001)
        assertEquals(0, place.hierarchyLevel)
    }

    @Test
    fun `path segment points are the same objects as the flat list`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z",
                  "endTime": "2025-01-01T01:00:00Z",
                  "timelinePath": [
                    { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" },
                    { "point": "11.0°, 21.0°", "time": "2025-01-01T00:20:00Z" }
                  ] }
            ] }
        """.trimIndent()

        val result = parseTimeline(json)
        val segment = result.segments.single()

        assertEquals(SegmentKind.PATH, segment.kind)
        assertEquals(2, segment.points.size)
        assertEquals(result.pathPoints, segment.points)
        // Identity, not just equality: the flat list must not be a copy, or the
        // memory argument in ParsedTimeline's docs stops being true.
        assertTrue(result.pathPoints[0] === segment.points[0])
    }

    @Test
    fun `unknown activity type falls into OTHER rather than being dropped`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z", "endTime": "2025-01-01T01:00:00Z",
                  "activity": { "distanceMeters": 100.0,
                                "topCandidate": { "type": "SOMETHING_GOOGLE_ADDED_LATER" } } }
            ] }
        """.trimIndent()

        val activity = parseTimeline(json).activities.single()
        assertEquals("SOMETHING_GOOGLE_ADDED_LATER", activity.activityType)
        assertEquals(MovementGroup.OTHER, activity.movement)
    }

    @Test
    fun `distance per movement group sums Google's own metres`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z", "endTime": "2025-01-01T01:00:00Z",
                  "activity": { "distanceMeters": 1000.0, "topCandidate": { "type": "WALKING" } } },
                { "startTime": "2025-01-01T02:00:00Z", "endTime": "2025-01-01T03:00:00Z",
                  "activity": { "distanceMeters": 500.0, "topCandidate": { "type": "RUNNING" } } },
                { "startTime": "2025-01-01T04:00:00Z", "endTime": "2025-01-01T05:00:00Z",
                  "activity": { "distanceMeters": 20000.0, "topCandidate": { "type": "IN_TRAIN" } } },
                { "startTime": "2025-01-01T06:00:00Z", "endTime": "2025-01-01T07:00:00Z",
                  "activity": { "topCandidate": { "type": "CYCLING" } } }
            ] }
        """.trimIndent()

        val byGroup = parseTimeline(json).distanceByMovement()

        // RUNNING buckets with WALKING, so the two add up.
        assertEquals(1500.0, byGroup[MovementGroup.WALKING]!!, 0.01)
        assertEquals(20000.0, byGroup[MovementGroup.TRANSIT]!!, 0.01)
        // An activity with no distance contributes nothing rather than a zero.
        assertNull(byGroup[MovementGroup.CYCLING])
    }

    @Test
    fun `places are deduplicated by place id and counted`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z", "endTime": "2025-01-01T01:00:00Z",
                  "visit": { "topCandidate": { "placeId": "home",
                             "placeLocation": { "latLng": "1.0°, 1.0°" } } } },
                { "startTime": "2025-01-02T00:00:00Z", "endTime": "2025-01-02T01:00:00Z",
                  "visit": { "topCandidate": { "placeId": "home",
                             "placeLocation": { "latLng": "1.0°, 1.0°" } } } },
                { "startTime": "2025-01-03T00:00:00Z", "endTime": "2025-01-03T01:00:00Z",
                  "visit": { "topCandidate": { "placeId": "cafe",
                             "placeLocation": { "latLng": "2.0°, 2.0°" } } } },
                { "startTime": "2025-01-04T00:00:00Z", "endTime": "2025-01-04T01:00:00Z",
                  "visit": { "topCandidate": { "placeLocation": { "latLng": "3.0°, 3.0°" } } } }
            ] }
        """.trimIndent()

        val places = parseTimeline(json).placesByVisitCount()

        // Four visits, two identifiable places; the unmatched one is dropped
        // because there is no identity to group it on.
        assertEquals(2, places.size)
        assertEquals("home", places[0].first.placeId)
        assertEquals(2, places[0].second)
        assertEquals("cafe", places[1].first.placeId)
        assertEquals(1, places[1].second)
    }

    @Test
    fun `segments come back sorted by start time even when the file is not`() {
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-03-01T00:00:00Z", "endTime": "2025-03-01T01:00:00Z",
                  "visit": { "topCandidate": { "placeId": "b",
                             "placeLocation": { "latLng": "2.0°, 2.0°" } } } },
                { "startTime": "2025-01-01T00:00:00Z", "endTime": "2025-01-01T01:00:00Z",
                  "visit": { "topCandidate": { "placeId": "a",
                             "placeLocation": { "latLng": "1.0°, 1.0°" } } } }
            ] }
        """.trimIndent()

        val segments = parseTimeline(json).segments
        assertEquals(listOf("a", "b"), segments.map { it.place?.placeId })
    }

    @Test
    fun `counters and point extraction are unchanged by segment recording`() {
        // Guards the promise that this is additive: the numbers the UI already
        // shows must come out exactly as they did before.
        val json = """
            { "semanticSegments": [
                { "startTime": "2025-01-01T00:00:00Z", "endTime": "2025-01-01T01:00:00Z",
                  "timelinePath": [ { "point": "10.0°, 20.0°", "time": "2025-01-01T00:10:00Z" } ] },
                { "startTime": "2025-01-01T02:00:00Z", "endTime": "2025-01-01T03:00:00Z",
                  "activity": { "distanceMeters": 5.0, "topCandidate": { "type": "WALKING" } } },
                { "startTime": "2025-01-01T04:00:00Z", "endTime": "2025-01-01T05:00:00Z",
                  "visit": { "topCandidate": { "placeId": "x",
                             "placeLocation": { "latLng": "1.0°, 1.0°" } } } }
            ] }
        """.trimIndent()

        val result = parseTimeline(json)
        assertEquals(3, result.totalSegments)
        assertEquals(1, result.pathSegments)
        assertEquals(1, result.activitySegments)
        assertEquals(1, result.visitSegments)
        // Only the timelinePath contributes to the exported track.
        assertEquals(1, result.pathPoints.size)
    }
}
