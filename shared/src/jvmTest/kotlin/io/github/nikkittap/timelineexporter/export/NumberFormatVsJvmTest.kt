package io.github.nikkittap.timelineexporter.export

import java.math.BigDecimal
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [formatFixed] and [plainDecimal] against the JVM calls they replace, which
 * stay the reference: whatever `String.format` and `BigDecimal` write is what
 * every export before the move to common code wrote.
 */
class NumberFormatVsJvmTest {

    private fun jvmFixed(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun jvmPlain(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun check(value: Double) {
        for (decimals in 0..7) {
            assertEquals(jvmFixed(value, decimals), formatFixed(value, decimals), "formatFixed($value, $decimals)")
        }
        assertEquals(jvmPlain(value), plainDecimal(value), "plainDecimal($value)")
    }

    @Test fun `random coordinates`() {
        val random = Random(20261106)
        repeat(100_000) {
            // Both encodings Google uses: E7 integers and free doubles.
            check(random.nextLong(-1_800_000_000, 1_800_000_001) / 1e7)
            check(random.nextDouble(-180.0, 180.0))
        }
    }

    @Test fun `random distances and durations`() {
        val random = Random(6110262)
        repeat(100_000) {
            check(random.nextDouble(0.0, 20_000_000.0) / 1000)  // metres -> km
            check(random.nextDouble(0.0, 1_000.0))
            check(random.nextLong(0, 10_000_000) / 3600.0)        // seconds -> hours
            check(random.nextLong(0, 100_000_000) / 86_400.0)     // seconds -> days
        }
    }

    @Test fun `exact halves at every scale`() {
        for (n in -2_000..2_000) {
            for (scale in listOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1e6, 1e7)) {
                check((n + 0.5) / scale)
                check((n * 10 + 5) / (scale * 10))
            }
        }
    }

    @Test fun `edge values`() {
        listOf(
            0.0, -0.0, 1e-7, -1e-7, 1e-9, -1e-9, -0.04, -0.004, -0.0000004, 0.05, 2.45, 12.5,
            48.12345675, 1e-3, 9.99e-4, 1e-5, 1.0E-12, 1e7, 1.23e7, 1e22, 1e23, 9.999999999999999E22,
            1.7976931348623157E308, Double.MIN_VALUE, 4.9E-324, 2.2250738585072014E-308,
            123456789.125, 1.2345678901234567E8, 0.1 + 0.2, 1.0 / 3, 2.0 / 3, -13.5, 9.95, 99.5,
            0.95, 0.995, 9.9999995, 0.49999999999999994, 4503599627370496.5, 9007199254740993.0,
        ).forEach { check(it); check(-it) }
    }

    @Test fun `random doubles over the whole exponent range`() {
        val random = Random(42)
        repeat(20_000) {
            val bits = random.nextLong()
            val value = Double.fromBits(bits)
            if (value.isFinite() && kotlin.math.abs(value) < 1e30 && kotlin.math.abs(value) > 1e-30) check(value)
        }
    }

    @Test fun `non-finite values`() {
        for (decimals in 0..7) {
            for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertEquals(jvmFixed(v, decimals), formatFixed(v, decimals))
            }
        }
    }
}
