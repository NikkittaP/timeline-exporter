package io.github.nikkittap.timelineexporter.parser

import io.github.nikkittap.timelineexporter.export.formatFixed

/**
 * What to call a place, in order of how much it is worth trusting.
 *
 * The user's own label wins over everything — they typed it precisely because
 * nothing else was right. Then a real name, which only the older Takeout
 * layout ever supplies. Then Google's semantic type, but only for HOME and
 * WORK: those are the two it infers rather than guesses, and they are the two
 * a person recognises instantly. SEARCHED_ADDRESS deliberately falls through —
 * "searched address" is a fact about how Google matched the place, not a name
 * for it, and showing it would push the coordinates a user needs out of view.
 *
 * The fallback is the coordinate pair. That is not a failure state, it is the
 * normal one — 271 of 282 places in the reference export land here — which is
 * why the row is tappable and the dialog behind it leads with a Maps link.
 */
fun resolvePlaceName(
    entry: PlaceEntry,
    label: String?,
    homeLabel: String,
    workLabel: String,
): String {
    if (!label.isNullOrBlank()) return label
    entry.name?.takeIf { it.isNotBlank() }?.let { return it }
    return when {
        entry.isHome -> homeLabel
        entry.isWork -> workLabel
        else -> formatCoordinates(entry.latitude, entry.longitude)
    }
}

/**
 * "55.59462, 13.00655" — five decimals, about a metre.
 *
 * A fixed dot on purpose even though this is UI: under a locale that writes
 * decimals with a comma, a locale-formatted pair reads as "55,59462, 13,00655"
 * — four numbers separated by commas, and no way to tell which pairs with
 * which. A dot here is the lesser inconsistency.
 */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    "${formatFixed(latitude, 5)}, ${formatFixed(longitude, 5)}"
