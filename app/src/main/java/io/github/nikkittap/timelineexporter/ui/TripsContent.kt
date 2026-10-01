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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
 * The trips half of the preview step.
 *
 * One tap selects a trip, which the map then shows alone; the selected row
 * offers "Only this trip", which sets step 2's dates to the trip's. Narrowing
 * the dates rather than keeping a separate selection is deliberate: the date
 * range is already what every count, the map and every export follow, so one
 * trip exported is just one trip's dates, with no second filter to explain.
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
    onTripClick: (Trip) -> Unit,
    onOnlyThis: (Trip) -> Unit,
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // A plain Column for the same reason as the places list: it sits inside
        // the screen's own vertical scroll.
        Column {
            inlineTrips(trips, selectedKey).forEachIndexed { index, trip ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TripRow(
                    trip = trip,
                    selected = trip.key == selectedKey,
                    onClick = { onTripClick(trip) },
                    onOnlyThis = { onOnlyThis(trip) },
                )
            }
        }
    }

    if (trips.size > INLINE_TRIP_LIMIT) {
        TextButton(onClick = onShowAll) {
            Text(stringResource(R.string.places_show_all, "%,d".format(trips.size)))
        }
    }
}

/** The newest [INLINE_TRIP_LIMIT], plus the selected trip pinned in front if it is older. */
private fun inlineTrips(trips: List<Trip>, selectedKey: Long?): List<Trip> {
    val head = trips.take(INLINE_TRIP_LIMIT)
    if (selectedKey == null || head.any { it.key == selectedKey }) return head
    val selected = trips.firstOrNull { it.key == selectedKey } ?: return head
    return listOf(selected) + head
}

/**
 * One trip. A trip of a day or more gets two lines and the distance travelled
 * on the right; a day trip is one quiet line, so a year of Sunday outings does
 * not bury the holidays.
 */
@Composable
private fun TripRow(
    trip: Trip,
    selected: Boolean,
    onClick: () -> Unit,
    onOnlyThis: () -> Unit,
) {
    val dates = tripDates(trip)
    val details = listOf(
        formatTripLength(trip),
        stringResource(R.string.trip_far_from_home, formatTripKm(trip.maxDistanceMeters)),
    ) + if (trip.isDayTrip) emptyList() else listOf(stringResource(R.string.places_count, "%,d".format(trip.placeCount)))
    val flags = listOfNotNull(
        if (trip.openStart) stringResource(R.string.trip_open_start) else null,
        if (trip.openEnd) stringResource(R.string.trip_open_end) else null,
        if (trip.longAbsence) stringResource(R.string.trip_long_absence) else null,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (trip.isDayTrip) 6.dp else 10.dp),
    ) {
        if (trip.isDayTrip) {
            Text(
                text = (listOf(dates) + details).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // Icons beside the dates, which rarely fill their line,
                    // rather than beside the distance, where they squeezed
                    // the details onto a second line.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dates,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
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
                    Text(
                        text = stringResource(R.string.trip_travelled_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (flags.isNotEmpty()) {
            Text(
                text = flags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (selected) {
            TextButton(onClick = onOnlyThis, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.trip_only_this))
            }
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

/** Fullscreen trips list, the counterpart to the expanded places list. */
@Composable
fun ExpandedTrips(
    trips: List<Trip>,
    selectedKey: Long?,
    onTripClick: (Trip) -> Unit,
    onOnlyThis: (Trip) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedKey, trips) {
        val index = trips.indexOfFirst { it.key == selectedKey }
        if (index >= 0) listState.animateScrollToItem(index)
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
                    items(trips, key = { it.key }) { trip ->
                        TripRow(
                            trip = trip,
                            selected = trip.key == selectedKey,
                            onClick = { onTripClick(trip) },
                            onOnlyThis = { onOnlyThis(trip) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
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
