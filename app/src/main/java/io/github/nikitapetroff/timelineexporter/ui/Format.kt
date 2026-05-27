package io.github.nikitapetroff.timelineexporter.ui

import java.time.Instant
import java.time.ZoneId
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
