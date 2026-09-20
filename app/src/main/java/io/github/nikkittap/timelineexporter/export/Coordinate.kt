package io.github.nikkittap.timelineexporter.export

import java.math.BigDecimal

/**
 * Append [value] to [out] as a plain decimal that reads back as the identical
 * double, without going through `Double.toString`.
 *
 * Two of these per exported point — 568,804 calls for a full phone export —
 * and after the timestamps were dealt with this was two thirds of what a save
 * still cost. `Double.toString` runs the shortest-round-trip algorithm and
 * allocates a String for every call; a coordinate, meanwhile, is a number on a
 * seven-decimal grid, because that is what both encodings Google uses carry:
 * degree strings like "55.5914387°" and `latitudeE7` integers divided by 1e7.
 * Seven decimals is about a centimetre.
 *
 * So the fast path is integer arithmetic on `value * 1e7`, taken only when
 * dividing that integer back by 1e7 returns the very double it started from —
 * which is exactly the promise that reading the text back is lossless. A
 * coordinate that is not on the grid (a value with more precision than Google
 * ever stores) fails that check and falls through to [BigDecimal], which is
 * slow and correct and almost never reached.
 *
 * The output is identical to `Double.toString` wherever that produces a plain
 * decimal, which is every coordinate of magnitude 0.001 or more — so exports
 * of real data are byte-for-byte what earlier versions wrote. Below that
 * `Double.toString` switches to scientific notation, and this deliberately
 * does not: "1.0E-5" is not a valid `xsd:decimal`, which is the type GPX
 * declares for `lat` and `lon`, so the old output was a file strict readers
 * could reject. It takes a coordinate within about a kilometre of the equator
 * *and* the prime meridian to notice.
 */
internal fun appendCoordinate(out: Appendable, value: Double) {
    if (!value.isFinite() || value < -MAX_FAST_MAGNITUDE || value > MAX_FAST_MAGNITUDE) {
        out.append(plainString(value))
        return
    }
    val units = Math.round(value * UNITS_PER_DEGREE)
    if (units / UNITS_PER_DEGREE != value) {
        out.append(plainString(value))
        return
    }

    // Not `value < 0`: negative zero is a coordinate like any other, and
    // Double.toString writes it as "-0.0".
    if (value.toRawBits() < 0) out.append('-')

    val magnitude = if (units < 0) -units else units
    appendDigits(out, magnitude / UNITS_PER_DEGREE_INT)
    out.append('.')

    // Seven decimals, trailing zeros dropped, but never down to none: a
    // coordinate reads as a coordinate only with a decimal point in it, and
    // that is what Double.toString does too.
    var fraction = (magnitude % UNITS_PER_DEGREE_INT).toInt()
    var digits = DECIMALS
    while (digits > 1 && fraction % 10 == 0) {
        fraction /= 10
        digits--
    }
    var place = POWERS_OF_TEN[digits - 1]
    while (place > 0) {
        out.append('0' + fraction / place)
        fraction %= place
        place /= 10
    }
}

private const val DECIMALS = 7
private const val UNITS_PER_DEGREE = 1e7
private const val UNITS_PER_DEGREE_INT = 10_000_000L

/**
 * Well inside the range where `value * 1e7` still rounds to a Long, and three
 * orders of magnitude past the ±180 a coordinate can actually hold.
 */
private const val MAX_FAST_MAGNITUDE = 100_000.0

private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000)

/**
 * The shortest text that reads back as [value], never in scientific notation.
 *
 * `BigDecimal.valueOf` is `Double.toString` with the exponent unpacked, so
 * this keeps the round trip and only changes how the same digits are laid out.
 * Stripping the zeros first drops the one the mantissa carries — "1.0E-12"
 * would otherwise spell out as 0.0000000000010.
 */
private fun plainString(value: Double): String =
    if (!value.isFinite()) {
        value.toString()
    } else {
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

private fun appendDigits(out: Appendable, value: Long) {
    if (value == 0L) {
        out.append('0')
        return
    }
    var place = 1L
    while (value / place >= 10) place *= 10
    var rest = value
    while (place > 0) {
        out.append('0' + (rest / place).toInt())
        rest %= place
        place /= 10
    }
}
