package io.github.nikkittap.timelineexporter.ui

import android.content.Context
import android.text.format.DateUtils
import io.github.nikkittap.timelineexporter.parser.MINUTES_PER_DAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * UI-friendly date/time formatting helpers.
 *
 * - Always converts the UTC [Instant] to the device's local timezone before
 *   formatting — users think in their own clock, not in UTC.
 * - Uses MEDIUM date + SHORT time styles so output is locale-appropriate:
 *     en-US  -> "Jun 9, 2025, 10:58 PM"
 *     en-GB  -> "9 Jun 2025, 22:58"
 *     ru-RU  -> "9 июн. 2025 г., 22:58"
 * - Pulls Locale.getDefault() at every call rather than caching the
 *   formatter, so if the user changes device language without an activity
 *   restart, output updates on next recomposition.
 *
 * Note: filename / GPX-trackName timestamps elsewhere in the app still use
 * raw ISO/LocalDate because those go into machine-readable artefacts where
 * locale-dependent formatting would be a bug.
 */
fun formatLocalDateTime(instant: Instant): String {
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
}

fun formatLocalDate(instant: Instant): String {
    val formatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
}

/**
 * A span of local days as one localized range that says the shared parts once:
 * "May 31 – Jun 29, 2026", "Dec 28, 2025 – Jan 4, 2026", "Jun 23 – 29, 2026".
 *
 * Two [formatLocalDate] calls joined by a dash repeat the year on both ends,
 * which is what made "Jun 9, 2025 – Jun 29, 2026" wrap mid-date in the stock
 * calendar header. DateUtils knows each locale's own range pattern.
 */
fun formatLocalDayRange(context: Context, start: Instant, endInclusive: Instant): String {
    val zone = ZoneId.systemDefault()
    return formatLocalDayRange(context, start.atZone(zone).toLocalDate(), endInclusive.atZone(zone).toLocalDate())
}

fun formatLocalDayRange(context: Context, first: LocalDate, last: LocalDate): String {
    val startMillis = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    // Noon, not midnight: DateUtils reads an end that falls exactly on midnight
    // as "up to but not including that day" and would print the day before.
    val endMillis = last.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    val range = DateUtils.formatDateRange(
        context,
        startMillis,
        endMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or
            DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_UTC,
    )
    // Each date stays whole on its line. German spells the month out, and on a
    // narrow phone "9. Juni 2025 – 29. Juni 2026" broke as "… – 29." over
    // "Juni 2026". Every space becomes a non-breaking one except the one in
    // front of the dash, which is where the line may turn. Korean joins the
    // two dates with a tilde instead, and with no space left to break at it
    // split "1일" between its digit and its syllable.
    return range.replace(' ', NBSP).replace("$NBSP–", " –").replace("$NBSP~", " ~")
}

private const val NBSP = ' '

/**
 * A time of day given as minutes past local midnight, on whatever clock the
 * reader's locale uses — 24-hour in most of the world, AM/PM in some of it.
 *
 * This one is deliberately localised where the ISO timestamps elsewhere in the
 * app are not: it is read by a person choosing a window, never written into a
 * file.
 */
fun formatMinuteOfDay(minuteOfDay: Int): String {
    val minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
    val formatter = DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
    return LocalTime.of(minute / 60, minute % 60).format(formatter)
}
