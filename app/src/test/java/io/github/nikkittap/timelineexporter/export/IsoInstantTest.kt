package io.github.nikkittap.timelineexporter.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * [appendIsoInstant] exists only to be faster than
 * [DateTimeFormatter.ISO_INSTANT]; it is worth nothing if it is not also
 * identical to it. Every case here compares the two directly, so the
 * exported timestamps cannot drift away from the format every previous
 * version of the app wrote.
 */
class IsoInstantTest {

    private fun formatted(instant: Instant): String =
        buildString { appendIsoInstant(this, instant) }

    private fun assertSameAsJavaTime(instant: Instant) {
        assertEquals(
            "epochSecond=${instant.epochSecond} nano=${instant.nano}",
            DateTimeFormatter.ISO_INSTANT.format(instant),
            formatted(instant),
        )
    }

    @Test
    fun `writes a whole second the way the exporters have always written it`() {
        assertEquals("2025-06-09T19:58:00Z", formatted(Instant.parse("2025-06-09T19:58:00Z")))
    }

    @Test
    fun `agrees with java time on the boundaries that arithmetic gets wrong`() {
        listOf(
            "1970-01-01T00:00:00Z", // the epoch itself
            "1969-12-31T23:59:59Z", // one second before it, where floorDiv matters
            "1970-01-01T00:00:01Z",
            "2000-02-29T12:00:00Z", // leap day of a century that is a leap year
            "1900-03-01T00:00:00Z", // day after the leap day a century does not have
            "2024-02-29T23:59:59Z",
            "2023-12-31T23:59:59Z", // last second of a year
            "2024-01-01T00:00:00Z", // first second of the next
            "2024-03-01T00:00:00Z", // the month the era arithmetic starts from
            "1583-01-01T00:00:00Z",
            "9999-12-31T23:59:59Z", // last four-digit year
        ).forEach { assertSameAsJavaTime(Instant.parse(it)) }
    }

    @Test
    fun `agrees with java time on sub-second instants`() {
        listOf(
            "2025-06-09T19:58:00.460Z", // milliseconds, as phone exports carry
            "2025-06-09T19:58:00.000001Z",
            "2025-06-09T19:58:00.000000001Z",
            "1969-07-20T20:17:40.123456789Z",
        ).forEach { assertSameAsJavaTime(Instant.parse(it)) }
    }

    @Test
    fun `agrees with java time outside the four-digit years`() {
        listOf(
            Instant.parse("+10000-01-01T00:00:00Z"),
            Instant.parse("0999-12-31T23:59:59Z"),
            Instant.parse("0001-01-01T00:00:00Z"),
            Instant.parse("-0001-12-31T23:59:59Z"),
            Instant.MIN,
            Instant.MAX,
        ).forEach { assertSameAsJavaTime(it) }
    }

    @Test
    fun `agrees with java time across two centuries of random seconds`() {
        // Deterministic seed: a failure has to be reproducible, and the point
        // is coverage of every month length and leap rule, not novelty.
        val random = Random(20260920)
        val from = Instant.parse("1900-01-01T00:00:00Z").epochSecond
        val until = Instant.parse("2100-01-01T00:00:00Z").epochSecond
        repeat(20_000) {
            assertSameAsJavaTime(Instant.ofEpochSecond(random.nextLong(from, until)))
        }
    }
}
