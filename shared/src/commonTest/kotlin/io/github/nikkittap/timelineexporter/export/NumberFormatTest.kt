package io.github.nikkittap.timelineexporter.export

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pinned expectations for [formatFixed] and [plainDecimal]. Every string here
 * was produced once by the JVM reference (`String.format(Locale.ROOT, ...)`,
 * `BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()` on JDK 21) and
 * then hard-coded, so that this test also runs on iOS, where there is no JVM
 * to ask and `Double.toString` is a different implementation.
 * NumberFormatVsJvmTest compares against the JVM directly, at scale.
 */
class NumberFormatTest {

    @Test fun fixed() {
        assertEquals("0.1", formatFixed(0.05, 1)); assertEquals("2.5", formatFixed(2.45, 1))
        assertEquals("13", formatFixed(12.5, 0)); assertEquals("-0.0", formatFixed(-0.04, 1))
        assertEquals("48.1234568", formatFixed(48.12345675, 7)); assertEquals("0.0000001", formatFixed(1e-7, 7))
    }

    @Test fun plain() {
        assertEquals("48.123", plainDecimal(48.123)); assertEquals("0.0000001", plainDecimal(1e-7))
        assertEquals("12300000", plainDecimal(1.23e7)); assertEquals("0", plainDecimal(0.0)); assertEquals("-13.5", plainDecimal(-13.5))
    }

    @Test fun `fixed rounds half up on the shortest decimal digits`() {
        assertEquals("0.13", formatFixed(0.125, 2))
        assertEquals("1.01", formatFixed(1.005, 2))
        assertEquals("0.05", formatFixed(0.045, 2))
        assertEquals("0.2", formatFixed(0.15, 1))
        assertEquals("4.4", formatFixed(4.35, 1))
        assertEquals("3", formatFixed(2.5, 0))
        assertEquals("-3", formatFixed(-2.5, 0))
        assertEquals("1", formatFixed(0.5, 0))
        assertEquals("-1", formatFixed(-0.5, 0))
        assertEquals("1", formatFixed(0.95, 0))
    }

    @Test fun `fixed carries through every digit`() {
        assertEquals("10.0", formatFixed(9.95, 1))
        assertEquals("100", formatFixed(99.5, 0))
        assertEquals("123456789.13", formatFixed(123456789.125, 2))
        assertEquals("123456789.012", formatFixed(1.2345678901234567E8, 3))
    }

    @Test fun `fixed keeps the sign of a negative that rounds to zero`() {
        assertEquals("-0.0", formatFixed(-0.0, 1))
        assertEquals("-0.000", formatFixed(-1e-9, 3))
        assertEquals("0", formatFixed(0.0, 0))
        assertEquals("0.0", formatFixed(1e-7, 1))
    }

    @Test fun `fixed expands exponent notation`() {
        assertEquals("0.00001", formatFixed(1.0E-5, 5))
        assertEquals("10000000.0", formatFixed(1e7, 1))
        assertEquals("10000000000000000000000.0", formatFixed(1e22, 1))
        assertEquals("0.00", formatFixed(5e-324, 2))
    }

    @Test fun `fixed writes non-finite values like Java`() {
        assertEquals("NaN", formatFixed(Double.NaN, 1))
        assertEquals("Infinity", formatFixed(Double.POSITIVE_INFINITY, 1))
        assertEquals("-Infinity", formatFixed(Double.NEGATIVE_INFINITY, 1))
    }

    @Test fun `plain expands exponents and drops trailing zeros`() {
        assertEquals("0", plainDecimal(-0.0))
        assertEquals("10000000000000000000000", plainDecimal(1e22))
        assertEquals("0.00001", plainDecimal(1.0E-5))
        assertEquals("123456", plainDecimal(123456.0))
        assertEquals("100", plainDecimal(100.0))
        assertEquals("0.001", plainDecimal(0.001))
        assertEquals("55.5914387", plainDecimal(55.5914387))
        assertEquals("-0.0000123", plainDecimal(-1.23e-5))
    }
}
