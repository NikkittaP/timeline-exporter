package io.github.nikkittap.timelineexporter.export

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Append [instant] to [out] exactly as [DateTimeFormatter.ISO_INSTANT] would,
 * without building a String to do it.
 *
 * Every exported point carries a timestamp, so this runs once per row —
 * 284,402 times for a full phone export, in the loop that dominated the five
 * seconds a save used to take. `ISO_INSTANT.format` walks a chain of printer
 * parsers and allocates a StringBuilder and a String for each call; the common
 * case here is a whole second in a four-digit year, which is nothing but
 * integer arithmetic and twenty characters.
 *
 * Anything that is not that common case — a sub-second instant, a year outside
 * 1000..9999 — falls through to `ISO_INSTANT` itself. Those are rare enough
 * that speed does not matter, and delegating is the only way to be sure the
 * output is identical rather than merely similar; [IsoInstantTest] checks the
 * two against each other either way.
 */
internal fun appendIsoInstant(out: Appendable, instant: Instant) {
    val epochSecond = instant.epochSecond
    if (instant.nano != 0 || epochSecond < MIN_FOUR_DIGIT_SECOND || epochSecond > MAX_FOUR_DIGIT_SECOND) {
        out.append(DateTimeFormatter.ISO_INSTANT.format(instant))
        return
    }

    val day = Math.floorDiv(epochSecond, SECONDS_PER_DAY)
    val secondOfDay = Math.floorMod(epochSecond, SECONDS_PER_DAY).toInt()

    // Civil date from a day number, counting eras of 400 years that begin on
    // 1 March — which is what makes the leap rule fall out of the arithmetic
    // instead of needing a branch, because the leap day lands at the end of
    // the year rather than in the middle of it.
    val z = day + DAYS_FROM_0000_03_01_TO_EPOCH
    val era = Math.floorDiv(z, DAYS_PER_ERA)
    val dayOfEra = z - era * DAYS_PER_ERA                                  // [0, 146096]
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // [0, 365]
    val marchMonth = (5 * dayOfYear + 2) / 153                             // [0, 11], 0 = March
    val dayOfMonth = (dayOfYear - (153 * marchMonth + 2) / 5 + 1).toInt()  // [1, 31]
    val month = (if (marchMonth < 10) marchMonth + 3 else marchMonth - 9).toInt()
    val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()

    appendFourDigits(out, year)
    out.append('-')
    appendTwoDigits(out, month)
    out.append('-')
    appendTwoDigits(out, dayOfMonth)
    out.append('T')
    appendTwoDigits(out, secondOfDay / 3600)
    out.append(':')
    appendTwoDigits(out, (secondOfDay / 60) % 60)
    out.append(':')
    appendTwoDigits(out, secondOfDay % 60)
    out.append('Z')
}

private const val SECONDS_PER_DAY = 86_400L

/** Days in the 400-year Gregorian cycle: 400 * 365 + 97 leap days. */
private const val DAYS_PER_ERA = 146_097L

/** Days from 0000-03-01 (the era's first day) to 1970-01-01. */
private const val DAYS_FROM_0000_03_01_TO_EPOCH = 719_468L

/** 1000-01-01T00:00:00Z — below this, ISO_INSTANT pads or signs the year. */
private const val MIN_FOUR_DIGIT_SECOND = -30_610_224_000L

/** 9999-12-31T23:59:59Z — above it, ISO_INSTANT writes a leading '+'. */
private const val MAX_FOUR_DIGIT_SECOND = 253_402_300_799L

private fun appendTwoDigits(out: Appendable, value: Int) {
    out.append(('0' + value / 10))
    out.append(('0' + value % 10))
}

private fun appendFourDigits(out: Appendable, value: Int) {
    out.append(('0' + value / 1000))
    out.append(('0' + (value / 100) % 10))
    out.append(('0' + (value / 10) % 10))
    out.append(('0' + value % 10))
}
