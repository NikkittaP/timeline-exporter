package io.github.nikkittap.timelineexporter.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

/**
 * [appendCoordinate] replaces `Double.toString` in the per-point loops, so
 * what it must never do is change what a coordinate means: reading the text
 * back has to produce the double it started from.
 *
 * Its second promise is that the text stays what earlier versions of the app
 * wrote — which is `Double.toString` exactly, for every coordinate that
 * function renders as a plain decimal.
 */
class CoordinateTest {

    private fun formatted(value: Double): String =
        buildString { appendCoordinate(this, value) }

    private fun assertRoundTrips(value: Double) {
        val text = formatted(value)
        assertEquals("text=$text", value, text.toDouble(), 0.0)
    }

    private fun assertMatchesToString(value: Double) {
        assertEquals(value.toString(), formatted(value))
    }

    @Test
    fun `writes a seven-decimal coordinate the way toString does`() {
        // The degree-string encoding, as it appears in a phone export.
        listOf(55.5914387, 38.1304764, 41.284025, 69.242256, 13.0065502, -0.0, 0.0)
            .forEach { assertMatchesToString(it) }
    }

    @Test
    fun `writes an E7 integer coordinate the way toString does`() {
        // The other encoding: latitudeE7 / 1e7.
        listOf(555914387L, 381304764L, 1800000000L, -900000000L, 130065502L, 10000L)
            .forEach { assertMatchesToString(it / 1e7) }
    }

    @Test
    fun `writes a value with fewer decimals the way toString does`() {
        listOf(55.0, -13.5, 90.0, -90.0, 180.0, -180.0, 0.001)
            .forEach { assertMatchesToString(it) }
    }

    @Test
    fun `round-trips every coordinate in the usable range`() {
        val random = Random(20260920)
        repeat(50_000) {
            assertRoundTrips(random.nextLong(-1_800_000_000, 1_800_000_000) / 1e7)
        }
    }

    @Test
    fun `round-trips values that are not seven-decimal numbers at all`() {
        // Nothing promises a coordinate came from Google. A value carrying
        // more precision than seven decimals must survive intact rather than
        // be rounded onto the grid.
        listOf(1.0 / 3.0, Math.PI, 55.59143871234567, 1e-12, Double.MIN_VALUE, 1e30)
            .forEach { assertRoundTrips(it) }
    }

    @Test
    fun `never writes scientific notation`() {
        // "1.0E-5" is what Double.toString gives below 0.001, and it is not a
        // valid xsd:decimal — the type GPX declares for lat and lon. Only a
        // coordinate near both the equator and the prime meridian is affected,
        // but a file strict readers reject is still a file they reject.
        listOf(1e-5, -1e-5, 0.0000001, -0.0000001, 0.0001234567, 1e-12, 1e30)
            .forEach {
                val text = formatted(it)
                assertFalse("$it became $text", text.contains('E') || text.contains('e'))
                assertRoundTrips(it)
            }
    }

    @Test
    fun `spells out the small coordinates toString puts an exponent on`() {
        assertEquals("0.0000001", formatted(1e-7))
        assertEquals("-0.0000001", formatted(-1e-7))
        assertEquals("0.00001", formatted(1e-5))
    }

    @Test
    fun `keeps every digit of a value below the seven-decimal grid`() {
        assertEquals("0.000000000001", formatted(1e-12))
        assertEquals("0.0001234567", formatted(0.0001234567))
    }
}
