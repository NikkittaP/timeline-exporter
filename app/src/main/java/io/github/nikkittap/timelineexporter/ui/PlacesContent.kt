package io.github.nikkittap.timelineexporter.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.export.ExportPlace
import io.github.nikkittap.timelineexporter.export.googleMapsUrl
import io.github.nikkittap.timelineexporter.parser.PlaceEntry
import java.util.Locale

/**
 * How many places the inline list in step 3 shows before offering "show all".
 *
 * Eight, because that is where the data stops being interesting: in the
 * reference export the eight most-visited places account for 512 of 936 visits
 * (55%), and the tail is a long run of one-visit coordinates. Showing eight
 * also keeps the card roughly the height of the map it replaces, so switching
 * between Track and Places doesn't make the page jump.
 */
private const val INLINE_PLACE_LIMIT = 8

// ---------- naming ----------

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

/** [resolvePlaceName] with the two translated words looked up for you. */
@Composable
fun placeDisplayName(entry: PlaceEntry, label: String?): String = resolvePlaceName(
    entry = entry,
    label = label,
    homeLabel = stringResource(R.string.place_home),
    workLabel = stringResource(R.string.place_work),
)

/**
 * "55.59462, 13.00655" — five decimals, about a metre.
 *
 * Locale.ROOT on purpose even though this is UI: under a locale that writes
 * decimals with a comma, a locale-formatted pair reads as "55,59462, 13,00655"
 * — four numbers separated by commas, and no way to tell which pairs with
 * which. A dot here is the lesser inconsistency.
 */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

/** Total time at a place: hours up to two days, whole days beyond that. */
@Composable
fun formatPlaceDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600.0
    return if (hours < 48) {
        stringResource(R.string.place_duration_hours, String.format(Locale.getDefault(), "%.1f", hours))
    } else {
        stringResource(R.string.place_duration_days, String.format(Locale.getDefault(), "%,.0f", hours / 24))
    }
}

/**
 * Turn the aggregate into the export payload, resolving every display name in
 * the current locale first. Called from the composition, which is the only
 * place `stringResource` is available — see [ExportPlace] for why the
 * exporters must not be asked to do this themselves.
 */
@Composable
fun exportPlaces(entries: List<PlaceEntry>, labels: PlaceLabelState): List<ExportPlace> {
    // Resolved once for the whole list rather than once per place: this runs
    // on every recomposition of the screen, and a `stringResource` lookup per
    // row would be three hundred of them per frame for nothing.
    val home = stringResource(R.string.place_home)
    val work = stringResource(R.string.place_work)
    return entries.map { entry ->
        ExportPlace(
            name = resolvePlaceName(entry, labels[entry.key], home, work),
            latitude = entry.latitude,
            longitude = entry.longitude,
            placeId = entry.placeId,
            category = entry.semanticType,
            address = entry.address,
            visits = entry.visits,
            firstVisit = entry.firstVisit,
            lastVisit = entry.lastVisit,
            firstVisitOffsetMinutes = entry.firstVisitOffsetMinutes,
            lastVisitOffsetMinutes = entry.lastVisitOffsetMinutes,
            totalSeconds = entry.totalSeconds,
        )
    }
}

// ---------- step 3 content ----------

/** The places half of the preview step: a short list, and a way to see it all. */
@Composable
fun PlacesPreview(
    entries: List<PlaceEntry>,
    labels: PlaceLabelState,
    dateRangeActive: Boolean,
    onPlaceClick: (PlaceEntry) -> Unit,
    onShowAll: () -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            text = stringResource(
                if (dateRangeActive) R.string.places_none_filtered else R.string.places_none
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // A hint, not an empty state: the list is full of coordinates and the user
    // has no reason to guess that tapping one does anything.
    if (entries.any { labels[it.key] == null }) {
        Text(
            stringResource(R.string.place_unnamed_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val shape = RoundedCornerShape(8.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // A plain Column, not a LazyColumn: this sits inside the screen's own
        // vertical scroll, and nesting a scrollable of the same orientation
        // gives it an unbounded height constraint and crashes. Eight rows is
        // cheap to compose anyway; the fullscreen list is where laziness pays.
        Column {
            entries.take(INLINE_PLACE_LIMIT).forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PlaceRow(entry, labels[entry.key], onClick = { onPlaceClick(entry) })
            }
        }
    }

    if (entries.size > INLINE_PLACE_LIMIT) {
        TextButton(onClick = onShowAll) {
            Text(stringResource(R.string.places_show_all, formatPlaceCount(entries.size)))
        }
    }
}

/** One place: name and details on the left, visit count on the right. */
@Composable
private fun PlaceRow(
    entry: PlaceEntry,
    label: String?,
    onClick: () -> Unit,
) {
    val name = placeDisplayName(entry, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatPlaceDuration(entry.totalSeconds)} · " +
                    stringResource(R.string.place_last_visit, formatLocalDate(entry.lastVisit)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatPlaceCount(entry.visits),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.places_visits_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Fullscreen places list, the counterpart to the expanded map. */
@Composable
fun ExpandedPlaces(
    entries: List<PlaceEntry>,
    labels: PlaceLabelState,
    onPlaceClick: (PlaceEntry) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.content_places),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.preview_close))
                }
            }
            val shape = RoundedCornerShape(8.dp)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape,
                // weight, not fillMaxSize: a Column child asking for the full
                // height ignores the header row above it and pushes the last
                // list items off the bottom of the screen.
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn {
                    items(entries, key = { it.key }) { entry ->
                        PlaceRow(entry, labels[entry.key], onClick = { onPlaceClick(entry) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// ---------- rename dialog ----------

/**
 * Name a place — and, first, work out which place it even is.
 *
 * The Maps button is the reason this is a dialog rather than an inline edit.
 * A user looking at "55.59632, 13.00554 — 94 visits" cannot name it from
 * memory; they need to see it on a map, and then they need the text field
 * right there while they still remember. Opening Maps is an ACTION_VIEW intent
 * carrying a place ID the file already contained — the app makes no request of
 * its own, and nothing about the user's history leaves the device unless they
 * press that button.
 */
@Composable
fun PlaceLabelDialog(
    entry: PlaceEntry,
    currentLabel: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(entry.key) { mutableStateOf(currentLabel.orEmpty()) }
    val fallbackName = placeDisplayName(entry, null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.place_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = fallbackName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${formatPlaceCount(entry.visits)} " +
                        stringResource(R.string.places_visits_label) +
                        " · ${formatPlaceDuration(entry.totalSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.track_name_date_range,
                        formatLocalDate(entry.firstVisit),
                        formatLocalDate(entry.lastVisit),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.address?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.place_dialog_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Offered for every place, not only the ones Google gave an
                // ID: the link carries the coordinates too, so a place with no
                // ID still opens as a pin in the right spot.
                TextButton(
                    onClick = { openInMaps(context, entry) },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(stringResource(R.string.place_dialog_maps))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.place_dialog_save))
            }
        },
        dismissButton = {
            Row {
                if (!currentLabel.isNullOrBlank()) {
                    TextButton(onClick = { onSave(null) }) {
                        Text(stringResource(R.string.place_dialog_remove))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.date_dialog_cancel))
                }
            }
        },
    )
}

/**
 * Hand the place to whatever maps app the user has.
 *
 * The place ID resolves to the business or building Google matched, so the
 * user sees a name rather than a pin in the middle of a street — which is the
 * whole point of the button, since the place they are being asked to name is
 * usually one they cannot recognise from its coordinates. The coordinates ride
 * along in the same link as the fallback Maps uses when the ID resolves to
 * nothing; see [googleMapsUrl].
 */
private fun openInMaps(context: android.content.Context, entry: PlaceEntry) {
    val url = googleMapsUrl(entry.latitude, entry.longitude, entry.placeId)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.place_toast_no_maps, Toast.LENGTH_SHORT).show()
    }
}

/** Grouped thousands, matching the point counts elsewhere on the screen. */
private fun formatPlaceCount(n: Int): String = "%,d".format(n)
