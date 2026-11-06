package io.github.nikkittap.timelineexporter.export

// Number formatting for the shared core, which cannot reach java.util.Formatter
// or java.math.BigDecimal. Both functions reproduce what the JVM calls they
// replace wrote, because that text is in every file the app has ever exported
// (NumberFormatVsJvmTest checks them against the JVM at scale).
//
// Both start from the shortest decimal digits of the double - the digits of
// `Double.toString` - and not from its exact binary value. That is what Java
// does too: `String.format("%.1f", 2.45)` is "2.5" although the double is
// 2.4500000000000001776..., because the Formatter rounds the digits "245",
// not the binary fraction. Rounding the exact value would be more "correct"
// and would change exported text.

/**
 * `String.format(Locale.ROOT, "%.<decimals>f", value)`: HALF_UP on the
 * shortest decimal digits of [value], '-' for a negative sign (kept when the
 * result rounds to zero: -0.04 is "-0.0"), no grouping, no exponent.
 * NaN and the infinities are written as Java writes them.
 */
fun formatFixed(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must not be negative: $decimals" }
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"

    val d = ShortestDigits.of(value)
    // The result is the integer round(|value| * 10^decimals) with a point put
    // in front of its last [decimals] digits. Its digits are the first
    // pointPosition + decimals shortest digits (zeros past their end) ...
    val keep = d.pointPosition + decimals
    val scaled = StringBuilder(maxOf(keep, 0) + 1)
    scaled.append('0') // room for a carry out of the first digit
    for (i in 0 until keep) scaled.append('0' + d.digitAt(i))
    // ... plus one when the first dropped digit is 5 or more (HALF_UP). With
    // keep < 0 the first dropped digit is an implicit leading zero.
    if (keep >= 0 && d.digitAt(keep) >= 5) {
        var i = scaled.length - 1
        while (scaled[i] == '9') { scaled[i] = '0'; i-- }
        scaled[i] = scaled[i] + 1
    }
    var first = 0
    while (first < scaled.length - 1 && scaled[first] == '0') first++
    val integer = scaled.substring(first) // no leading zeros, "0" for zero

    val sb = StringBuilder(integer.length + decimals + 3)
    if (d.negative) sb.append('-')
    if (integer.length > decimals) {
        sb.append(integer, 0, integer.length - decimals)
    } else {
        sb.append('0')
    }
    if (decimals > 0) {
        sb.append('.')
        repeat(decimals - integer.length) { sb.append('0') }
        sb.append(integer, maxOf(integer.length - decimals, 0), integer.length)
    }
    return sb.toString()
}

/**
 * `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()`: the
 * shortest decimal digits of [value] laid out without an exponent, trailing
 * zeros dropped, and "0" for either zero (BigDecimal has no negative zero).
 * NaN and the infinities, which BigDecimal rejects, come back as
 * `value.toString()`.
 */
fun plainDecimal(value: Double): String {
    if (!value.isFinite()) return value.toString()
    if (value == 0.0) return "0"
    val d = ShortestDigits.of(value)
    val sb = StringBuilder(d.digits.length + 4)
    if (d.negative) sb.append('-')
    val point = d.pointPosition
    when {
        point <= 0 -> {
            sb.append("0.")
            repeat(-point) { sb.append('0') }
            sb.append(d.digits)
        }
        point >= d.digits.length -> {
            sb.append(d.digits)
            repeat(point - d.digits.length) { sb.append('0') }
        }
        else -> {
            sb.append(d.digits, 0, point).append('.').append(d.digits, point, d.digits.length)
        }
    }
    return sb.toString()
}

/**
 * The digits of `value.toString()`: [digits] without leading or trailing
 * zeros (empty for zero) and the value is `0.digits * 10^pointPosition`,
 * i.e. [pointPosition] digits stand before the decimal point.
 *
 * Parsed rather than assumed, so that any spelling `toString` may choose -
 * plain or exponent, "E" or "e", "+" or not in the exponent, ".0" or not -
 * reads the same. Kotlin/Native's `Double.toString` is not the JVM's code.
 */
private class ShortestDigits(val negative: Boolean, val digits: String, val pointPosition: Int) {

    /** The digit at [index] (0 = first significant), zero past the end. */
    fun digitAt(index: Int): Int = if (index < digits.length) digits[index] - '0' else 0

    companion object {
        fun of(value: Double): ShortestDigits {
            val text = value.toString()
            var i = 0
            val negative = text[0] == '-'
            if (negative || text[0] == '+') i++
            val mantissa = StringBuilder(24)
            var pointAt = -1
            while (i < text.length && text[i] != 'E' && text[i] != 'e') {
                val c = text[i]
                if (c == '.') pointAt = mantissa.length else mantissa.append(c)
                i++
            }
            if (pointAt < 0) pointAt = mantissa.length
            var exponent = 0
            if (i < text.length) {
                i++ // 'E'
                var expNegative = false
                if (text[i] == '-' || text[i] == '+') { expNegative = text[i] == '-'; i++ }
                while (i < text.length) { exponent = exponent * 10 + (text[i] - '0'); i++ }
                if (expNegative) exponent = -exponent
            }
            // Trim leading zeros (each moves the point left) and trailing zeros.
            var start = 0
            while (start < mantissa.length && mantissa[start] == '0') start++
            var end = mantissa.length
            while (end > start && mantissa[end - 1] == '0') end--
            if (start == end) return ShortestDigits(negative, "", 0)
            return ShortestDigits(negative, mantissa.substring(start, end), pointAt - start + exponent)
        }
    }
}
