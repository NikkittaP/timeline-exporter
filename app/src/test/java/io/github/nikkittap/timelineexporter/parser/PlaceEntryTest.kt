package io.github.nikkittap.timelineexporter.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Aggregation of visits into the Places list (1.7.0).
 *
 * The figures in the comments come from the reference export the feature was
 * designed against: 936 visits, 282 distinct places, 81 of the visits at
 * hierarchy level 1 and every one of those overlapping a level-0 visit.
 */
class PlaceEntryTest {

    private fun visit(
        start: String,
        end: String,
        placeId: String? = "home",
        lat: Double = 1.0,
        lon: Double = 1.0,
        semanticType: String? = null,
        hierarchyLevel: Int? = null,
        offsetMinutes: Int? = null,
    ): String {
        val fields = buildList {
            add(""""startTime": "$start"""")
            add(""""endTime": "$end"""")
            if (offsetMinutes != null) {
                add(""""startTimeTimezoneUtcOffsetMinutes": $offsetMinutes""")
                add(""""endTimeTimezoneUtcOffsetMinutes": $offsetMinutes""")
            }
            val candidate = buildList {
                if (placeId != null) add(""""placeId": "$placeId"""")
                if (semanticType != null) add(""""semanticType": "$semanticType"""")
                add(""""placeLocation": { "latLng": "$lat°, $lon°" }""")
            }.joinToString(", ")
            val visitFields = buildList {
                if (hierarchyLevel != null) add(""""hierarchyLevel": $hierarchyLevel""")
                add(""""topCandidate": { $candidate }""")
            }.joinToString(", ")
            add(""""visit": { $visitFields }""")
        }.joinToString(", ")
        return "{ $fields }"
    }

    private fun parse(vararg visits: String): ParsedTimeline =
        parseTimeline("""{ "semanticSegments": [ ${visits.joinToString(",\n")} ] }""")

    @Test
    fun `visits collapse into one row per place, most visited first`() {
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", placeId = "cafe", lat = 2.0, lon = 2.0),
            visit("2025-01-02T00:00:00Z", "2025-01-02T01:00:00Z", placeId = "home"),
            visit("2025-01-03T00:00:00Z", "2025-01-03T01:00:00Z", placeId = "home"),
            visit("2025-01-04T00:00:00Z", "2025-01-04T01:00:00Z", placeId = "home"),
        )
        val places = parsed.placeEntries()
        assertEquals(2, places.size)
        assertEquals("home", places[0].key)
        assertEquals(3, places[0].visits)
        assertEquals("cafe", places[1].key)
        assertEquals(1, places[1].visits)
    }

    @Test
    fun `a tie on visits is broken by total time spent`() {
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", placeId = "quick", lat = 2.0),
            visit("2025-01-02T00:00:00Z", "2025-01-02T09:00:00Z", placeId = "long"),
        )
        val places = parsed.placeEntries()
        assertEquals("long", places[0].key)
        assertEquals(9 * 3600L, places[0].totalSeconds)
        assertEquals(3600L, places[1].totalSeconds)
    }

    @Test
    fun `container places are excluded by default and available on request`() {
        // The mall around the shop. Both visits describe one afternoon, so
        // counting both would report two stays and twice the time.
        val parsed = parse(
            visit("2025-01-01T12:00:00Z", "2025-01-01T14:00:00Z", placeId = "shop"),
            visit("2025-01-01T12:00:00Z", "2025-01-01T14:00:00Z", placeId = "mall",
                lat = 1.0001, hierarchyLevel = 1),
        )
        assertEquals(listOf("shop"), parsed.placeEntries().map { it.key })
        assertEquals(
            setOf("shop", "mall"),
            parsed.placeEntries(includeContainers = true).map { it.key }.toSet(),
        )
    }

    @Test
    fun `the place count in the summary agrees with the list`() {
        val parsed = parse(
            visit("2025-01-01T12:00:00Z", "2025-01-01T14:00:00Z", placeId = "shop"),
            visit("2025-01-01T12:00:00Z", "2025-01-01T14:00:00Z", placeId = "mall", hierarchyLevel = 1),
        )
        assertEquals(parsed.placeEntries().size, parsed.placeCount())
        assertEquals(
            parsed.placeEntries(includeContainers = true).size,
            parsed.placeCount(includeContainers = true),
        )
    }

    @Test
    fun `the most specific semantic type across visits wins`() {
        // Google is not consistent within one place: in the reference export
        // the most-visited place is typed three different ways across its
        // visits, and "unknown" would be the one that happened to come first.
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", semanticType = "UNKNOWN"),
            visit("2025-01-02T00:00:00Z", "2025-01-02T01:00:00Z", semanticType = "SEARCHED_ADDRESS"),
            visit("2025-01-03T00:00:00Z", "2025-01-03T01:00:00Z", semanticType = "INFERRED_HOME"),
        )
        val entry = parsed.placeEntries().single()
        assertEquals("INFERRED_HOME", entry.semanticType)
        assertTrue(entry.isHome)
    }

    @Test
    fun `a place with no meaningful type keeps UNKNOWN rather than inventing one`() {
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", semanticType = "UNKNOWN"),
        )
        val entry = parsed.placeEntries().single()
        assertEquals("UNKNOWN", entry.semanticType)
        assertTrue(!entry.isHome && !entry.isWork)
        assertNull(entry.name)
    }

    @Test
    fun `coordinates come from the most recent visit`() {
        // Google restates a place's location per visit and nudges it by a few
        // metres; the newer fix is the one Maps agrees with today.
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", lat = 55.60661, lon = 13.00733),
            visit("2025-02-01T00:00:00Z", "2025-02-01T01:00:00Z", lat = 55.60662, lon = 13.00727),
        )
        val entry = parsed.placeEntries().single()
        assertEquals(55.60662, entry.latitude, 1e-7)
        assertEquals(13.00727, entry.longitude, 1e-7)
    }

    @Test
    fun `first and last visit bracket the whole run, each with its own offset`() {
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", offsetMinutes = 60),
            visit("2025-07-01T00:00:00Z", "2025-07-01T01:00:00Z", offsetMinutes = 120),
        )
        val entry = parsed.placeEntries().single()
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), entry.firstVisit)
        assertEquals(Instant.parse("2025-07-01T00:00:00Z"), entry.lastVisit)
        assertEquals(60, entry.firstVisitOffsetMinutes)
        assertEquals(120, entry.lastVisitOffsetMinutes)
    }

    @Test
    fun `a date range narrows to visits that start inside it`() {
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", placeId = "old"),
            visit("2025-06-01T00:00:00Z", "2025-06-01T01:00:00Z", placeId = "new", lat = 2.0),
        )
        val entries = parsed.placeEntries(
            range = Instant.parse("2025-05-01T00:00:00Z")..Instant.parse("2025-07-01T00:00:00Z"),
        )
        assertEquals(listOf("new"), entries.map { it.key })
    }

    @Test
    fun `visits without a place ID group on their coordinates instead of splitting`() {
        // The older Takeout layouts sometimes describe a place without
        // identifying it. Rounded to five decimals — a metre — so that
        // Google's per-visit jitter does not create a row per stay.
        val parsed = parse(
            visit("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", placeId = null, lat = 3.123456, lon = 4.0),
            visit("2025-01-02T00:00:00Z", "2025-01-02T01:00:00Z", placeId = null, lat = 3.123457, lon = 4.0),
        )
        val entry = parsed.placeEntries().single()
        assertEquals(2, entry.visits)
        assertNull("no ID means no Maps link", entry.placeId)
        assertTrue(entry.key.startsWith("@"))
    }
}
