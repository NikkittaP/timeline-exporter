package io.github.nikkittap.timelineexporter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikkittap.timelineexporter.R
import io.github.nikkittap.timelineexporter.export.localDate
import io.github.nikkittap.timelineexporter.trips.TransportMode
import io.github.nikkittap.timelineexporter.trips.Trip
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Rows shown in step 3 before "Show all"; same budget as the places list. */
private const val INLINE_TRIP_LIMIT = 8

/**
 * The trips that touch [range], newest first. All of them when no range is
 * set. "Touch", not "start inside": a two-week holiday that began the day
 * before the chosen range is still the holiday the user is looking at.
 */
fun tripsIn(trips: List<Trip>, range: ClosedRange<Instant>?): List<Trip> =
    trips.filter { range == null || (it.end >= range.start && it.start <= range.endInclusive) }
        .sortedByDescending { it.start }

// ---------- step 3 content ----------

/**
 * The trips half of the preview step, under the map and the selected trip's
 * card (see [SelectedTripCard]).
 *
 * Two separate choices live here. A tap on a row shows that trip on the map.
 * Tapping never lets go of a trip: someone who just scrolled through a
 * hundred rows to find one should not lose it to a second tap, so going back
 * is the card's own button. The selected trip leaves this list for the card,
 * which is where it always is.
 *
 * The checkbox on each row is the other choice: which trips step 4 writes.
 * None ticked means all of them, so nothing changes for someone who never
 * touches a box. Once any are ticked, a line above the list says how many of
 * how many and offers to clear them, so a narrowed export is never silent.
 * It used to be "Use only this trip", which narrowed step 2's dates: one
 * trip at most, and nothing on this card said how to get the rest back.
 *
 * [noHome] says the file has no home to measure from, which is a different
 * thing to say than "no trips".
 */
@Composable
fun TripsPreview(
    trips: List<Trip>,
    noHome: Boolean,
    dateRangeActive: Boolean,
    selectedKey: Long?,
    checkedKeys: Set<Long>,
    onTripClick: (Trip) -> Unit,
    onCheckedChange: (Trip, Boolean) -> Unit,
    onClearChecked: () -> Unit,
    onShowAll: () -> Unit,
) {
    if (trips.isEmpty()) {
        Text(
            text = stringResource(
                when {
                    noHome -> R.string.trips_no_home
                    dateRangeActive -> R.string.trips_none_filtered
                    else -> R.string.trips_none
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        stringResource(R.string.trips_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Always there, at the button's height, ticks or none. When it appeared
    // only with the first tick, it pushed the list down under the finger and
    // the next tap ticked the row below the one aimed at.
    val checkedCount = trips.count { it.key in checkedKeys }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = if (checkedCount > 0) {
                stringResource(R.string.trips_checked, "%,d".format(checkedCount), "%,d".format(trips.size))
            } else {
                stringResource(R.string.trips_checked_none)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (checkedCount > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (checkedCount > 0) {
            TextButton(onClick = onClearChecked) {
                Text(stringResource(R.string.filter_clear_button))
            }
        }
    }

    val rest = trips.filter { it.key != selectedKey }
    if (rest.isNotEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // A plain Column for the same reason as the places list: it sits inside
            // the screen's own vertical scroll.
            Column {
                rest.take(INLINE_TRIP_LIMIT).forEachIndexed { index, trip ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TripRow(
                        trip = trip,
                        highlighted = false,
                        checked = trip.key in checkedKeys,
                        onCheckedChange = { onCheckedChange(trip, it) },
                        onClick = { onTripClick(trip) },
                    )
                }
            }
        }
    }

    if (rest.size > INLINE_TRIP_LIMIT) {
        TextButton(onClick = onShowAll) {
            Text(stringResource(R.string.places_show_all, "%,d".format(trips.size)))
        }
    }
}

/**
 * The trip the map is showing, directly under the map, with everything known
 * about it: the place count and the notes on how complete it is, which the
 * rows leave out to stay scannable.
 *
 * Its checkbox is the same one the trip had in the list, in the same place,
 * since the trip left the list for this card. The one button lets go of the
 * trip: back to everything step 4 will write, which is every trip, or the
 * ticked ones once there are any, and the label says which.
 */
@Composable
fun SelectedTripCard(
    trip: Trip,
    checked: Boolean,
    anyChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onShowAllOnMap: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(top = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                ExportCheckbox(checked, onCheckedChange)
                Column(modifier = Modifier.weight(1f)) {
                    TripSummary(trip, detailed = true)
                }
            }
            // The button keeps its own 12 dp of padding, so the pressed
            // shape has room around the label, and that padding is what
            // lines the label up with the row above.
            TextButton(
                onClick = onShowAllOnMap,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.padding(start = CHECKBOX_WIDTH - 12.dp, bottom = 2.dp),
            ) {
                Text(
                    stringResource(
                        if (anyChecked) R.string.trip_show_checked_on_map
                        else R.string.trip_show_all_on_map
                    )
                )
            }
        }
    }
}

/**
 * One trip in a list: its export checkbox, then the summary, tappable, tinted
 * when it is the one on the map. The box and the row are separate targets:
 * ticking a trip does not move the map, and showing one does not tick it.
 */
@Composable
private fun TripRow(
    trip: Trip,
    highlighted: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            // A day trip's single line sits inside the checkbox's 40 dp, so
            // it needs no padding of its own to keep the rows apart.
            .padding(end = 12.dp, top = if (trip.isDayTrip) 0.dp else 10.dp, bottom = if (trip.isDayTrip) 0.dp else 10.dp),
    ) {
        ExportCheckbox(checked, onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            TripSummary(trip, detailed = false)
        }
    }
}

/** Width the checkbox takes at the start of a row, touch area included. */
private val CHECKBOX_WIDTH = 40.dp

/**
 * The box that puts a trip in the export. 40 dp to touch rather than the
 * usual 48: at 48 every one-line day trip would be half as tall again, and a
 * year of them is most of the list. 40 is still a comfortable target.
 */
@Composable
private fun ExportCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.trip_export_checkbox)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides CHECKBOX_WIDTH) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * What a trip is, at two depths.
 *
 * In a list, only what tells one trip from the next: the dates, how it was
 * travelled, how long, how far from home (the nearest thing to a destination
 * until trips have names) and the distance covered. A day trip is one quiet
 * line, so a year of Sunday outings does not bury the holidays.
 *
 * [detailed], for the selected trip's card, adds the place count, labels the
 * distance, and says when the trip is cut off by the file or looks like a
 * move.
 */
@Composable
private fun TripSummary(trip: Trip, detailed: Boolean) {
    val dates = tripDates(trip)
    // Each figure held together with no-break spaces, so a line that runs out
    // of room breaks between "866 km from home" and "21 places", never inside
    // one of them.
    val details = (
        listOf(
            formatTripLength(trip),
            stringResource(R.string.trip_far_from_home, formatTripKm(trip.maxDistanceMeters)),
        ) + if (detailed) listOf(pluralStringResource(R.plurals.places_count, trip.placeCount, "%,d".format(trip.placeCount))) else emptyList()
        ).map { it.replace(' ', ' ') }

    if (trip.isDayTrip && !detailed) {
        Text(
            text = (listOf(dates) + details).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            // Icons beside the dates, which rarely fill their line,
            // rather than beside the distance, where they squeezed
            // the details onto a second line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The card has room for a second line and nothing to save
                // it for, so its dates turn at the dash (see
                // formatLocalDayRange) instead of losing the year, as
                // "28 сент. – 20 нояб. 202…" did in Russian.
                Text(
                    text = dates,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = if (detailed) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trip.transport.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TransportIcons(trip.transport)
                }
            }
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                // "≈" and nothing more when the distance is partly
                // measured off the track or the file cuts the trip
                // off: a hint that it is not Google's full figure,
                // without turning the row into a warning.
                text = (if (trip.travelledEstimated) "≈ " else "") + formatTripKm(trip.travelledMeters),
                style = MaterialTheme.typography.titleMedium,
            )
            if (detailed) {
                Text(
                    text = stringResource(R.string.trip_travelled_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (detailed) {
        val flags = listOfNotNull(
            if (trip.openStart) stringResource(R.string.trip_open_start) else null,
            if (trip.openEnd) stringResource(R.string.trip_open_end) else null,
            if (trip.longAbsence) stringResource(R.string.trip_long_absence) else null,
        )
        if (flags.isNotEmpty()) {
            Text(
                text = flags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * The one or two ways the trip was mostly travelled, as small icons. Chosen by
 * `transportOf`, which leaves them out whenever the data is too thin to say;
 * so no icons is a normal state, not a gap to explain.
 *
 * Each icon is labelled for TalkBack with the movement group the filter
 * already names, so a screen reader hears "Flights, Driving" and no new text
 * had to be translated.
 */
@Composable
private fun TransportIcons(modes: List<TransportMode>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (mode in modes) {
            Icon(
                painter = painterResource(
                    when (mode) {
                        TransportMode.FLIGHT -> R.drawable.ic_transport_flight
                        TransportMode.CAR -> R.drawable.ic_transport_car
                        TransportMode.TRAIN -> R.drawable.ic_transport_train
                        TransportMode.BUS -> R.drawable.ic_transport_bus
                        TransportMode.BOAT -> R.drawable.ic_transport_boat
                        TransportMode.BICYCLE -> R.drawable.ic_transport_bicycle
                        TransportMode.WALK -> R.drawable.ic_transport_walk
                    }
                ),
                contentDescription = stringResource(
                    when (mode) {
                        TransportMode.FLIGHT -> R.string.movement_flying
                        TransportMode.CAR -> R.string.movement_driving
                        TransportMode.TRAIN, TransportMode.BUS, TransportMode.BOAT -> R.string.movement_transit
                        TransportMode.BICYCLE -> R.string.movement_cycling
                        TransportMode.WALK -> R.string.movement_walking
                    }
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Fullscreen trips list, the counterpart to the expanded places list.
 *
 * Picking a trip here is picking it to look at, and the map is behind this
 * list, so a tap selects and closes in one: [onTripClick] is expected to do
 * both. The trips are grouped under sticky year headers, which is how a
 * hundred-odd holidays and outings are easiest to find again.
 */
@Composable
fun ExpandedTrips(
    trips: List<Trip>,
    selectedKey: Long?,
    checkedKeys: Set<Long>,
    onTripClick: (Trip) -> Unit,
    onCheckedChange: (Trip, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    // Newest first in, newest year first out: groupBy keeps first-seen order.
    val byYear = remember(trips) {
        val zone = ZoneId.systemDefault()
        trips.groupBy { localDate(it.start, it.startOffsetMinutes, zone).year }
    }
    val listState = rememberLazyListState()
    val headerOffset = with(LocalDensity.current) { -YEAR_HEADER_HEIGHT.roundToPx() }
    // Open on the trip the map is showing, below its year's header rather
    // than under it.
    LaunchedEffect(Unit) {
        var index = 0
        for (yearTrips in byYear.values) {
            index++ // the header
            val at = yearTrips.indexOfFirst { it.key == selectedKey }
            if (at >= 0) {
                listState.scrollToItem(index + at, headerOffset)
                break
            }
            index += yearTrips.size
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stringResource(R.string.content_trips)} · ${"%,d".format(trips.size)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.preview_close))
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(state = listState) {
                    for ((year, yearTrips) in byYear) {
                        stickyHeader(key = "year-$year") {
                            YearHeader(year, yearTrips.size)
                        }
                        items(yearTrips, key = { it.key }) { trip ->
                            TripRow(
                                trip = trip,
                                highlighted = trip.key == selectedKey,
                                checked = trip.key in checkedKeys,
                                // Ticking stays in the list, so several
                                // trips can be ticked in one visit.
                                onCheckedChange = { onCheckedChange(trip, it) },
                                onClick = { onTripClick(trip) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

private val YEAR_HEADER_HEIGHT = 36.dp

/**
 * "2025 · 14 trips", opaque so the rows scroll under it. The year in the
 * accent colour and a step up in weight, the count quieter beside it: the
 * header has to stand out from rows that use the same type sizes, and colour
 * plus weight does that without a band of solid colour across the list.
 */
@Composable
private fun YearHeader(year: Int, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(YEAR_HEADER_HEIGHT).padding(horizontal = 12.dp),
        ) {
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = pluralStringResource(R.plurals.trips_count, count, "%,d".format(count)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- formatting ----------

/** "19 Jul – 1 Aug 2025", in the local dates where the trip began and ended. */
@Composable
private fun tripDates(trip: Trip): String {
    val zone = ZoneId.systemDefault()
    return formatLocalDayRange(
        LocalContext.current,
        localDate(trip.start, trip.startOffsetMinutes, zone),
        localDate(trip.end, trip.endOffsetMinutes, zone),
    )
}

/**
 * Hours for a day trip; for anything longer, the calendar days it touches,
 * "19 July – 1 August" being 14 days. That is how people count a holiday,
 * and it is the figure the map's day list shows for the same trip.
 */
@Composable
private fun formatTripLength(trip: Trip): String {
    if (trip.isDayTrip) {
        val hours = trip.durationSeconds / 3600.0
        return stringResource(R.string.place_duration_hours, String.format(Locale.getDefault(), "%.0f", hours))
    }
    val zone = ZoneId.systemDefault()
    val days = (
        localDate(trip.end, trip.endOffsetMinutes, zone).toEpochDay() -
            localDate(trip.start, trip.startOffsetMinutes, zone).toEpochDay() + 1
        ).toInt()
    return pluralStringResource(R.plurals.map_day_count, days, days)
}

/** Same rule as the movement breakdown: whole km from 10 km up. */
private fun formatTripKm(meters: Double): String {
    val km = meters / 1000.0
    return if (km >= 10) "%,.0f km".format(km) else "%.1f km".format(km)
}
