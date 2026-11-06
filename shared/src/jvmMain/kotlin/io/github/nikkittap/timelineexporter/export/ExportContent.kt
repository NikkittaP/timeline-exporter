package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Which of the two things in a Timeline export the user is currently looking
 * at and about to export.
 *
 * One enum shared by the UI (the Track / Places switch in steps 3 and 4), the
 * export payload ([ExportContent]) and the result state ([ExportState]) —
 * rather than three parallel booleans that can drift out of step.
 *
 * Declaration order is the order of the switch: Track, Trips, Places.
 */
enum class ContentKind { TRACK, TRIPS, PLACES }

/**
 * What an [Exporter] is asked to write.
 *
 * Until 1.7.0 the interface took `List<PathPoint>` directly, which is why
 * places could not be exported at all: there was no way to hand an exporter
 * anything that is not a GPS track. Widening it to a sealed type keeps the
 * "one new format = one new file" property — a new format still implements a
 * single interface — while making the set of exportable *contents* an equally
 * closed, equally exhaustive `when`.
 *
 * The strings inside [Places] are already localized. Resolving a place's
 * display name needs `stringResource` (a place Google typed INFERRED_HOME is
 * "Home" in English and "Дом" in Russian) and that lives in the UI layer, so
 * the UI builds this payload and the exporters never think about language.
 */
sealed interface ExportContent {
    /** How many items this will write; drives the "exported N …" message. */
    val size: Int

    val kind: ContentKind

    val isEmpty: Boolean get() = size == 0

    /**
     * A GPS track: what every version before 1.7.0 could export.
     *
     * [colorByDay] mirrors the map's `Color by day` switch: the preview is
     * what the user expects the file to look like, and a KML that came out in
     * one colour after a preview in seven was reported as a bug. Formats that
     * can carry a line colour then write one line per local day in that day's
     * [trackDayColor]; the rest ignore it. [fallbackZone] decides the day of a
     * sample recorded without an offset, exactly as it does on the map.
     */
    data class Track(
        val points: List<PathPoint>,
        val colorByDay: Boolean = false,
        val fallbackZone: ZoneId = ZoneId.systemDefault(),
    ) : ExportContent {
        override val size: Int get() = points.size
        override val kind: ContentKind get() = ContentKind.TRACK
    }

    /** Trips in time order, each with the part of the track recorded during it. */
    data class Trips(val trips: List<ExportTrip>) : ExportContent {
        override val size: Int get() = trips.size
        override val kind: ContentKind get() = ContentKind.TRIPS
    }

    /** Distinct places, most-visited first. */
    data class Places(val places: List<ExportPlace>) : ExportContent {
        override val size: Int get() = places.size
        override val kind: ContentKind get() = ContentKind.PLACES
    }
}

/**
 * One place, flattened and labelled, ready to be written out.
 *
 * Deliberately not `PlaceEntry` itself: that type is the parser's aggregate
 * and carries a raw `semanticType` and no display name. Everything here is
 * final — the exporters copy fields, they do not decide anything.
 *
 * [name] is what the user sees in the list; [category] is Google's raw
 * `semanticType` (e.g. "INFERRED_HOME"), kept untranslated because it is data
 * for whatever reads the file, not a label for a person. Both are written:
 * the readable one for humans, the stable one for machines.
 *
 * [totalSeconds] is the summed duration of every visit, so a place can be
 * ranked by time as well as by count once something wants to.
 */
data class ExportPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val placeId: String?,
    val category: String?,
    val address: String?,
    val visits: Int,
    val firstVisit: Instant,
    val lastVisit: Instant,
    /**
     * The UTC offset in effect at [firstVisit] / [lastVisit], in minutes, or
     * null where the export carried no timezone at all.
     *
     * Two offsets rather than one per place, even though a place does not
     * move: the aggregate spans up to a year, so its first and last visit can
     * straddle a DST change or, for a place visited on two different trips,
     * disagree outright. One column would have to pick a winner and would
     * silently mislabel the other timestamp — which is precisely the bug
     * 1.6.3 fixed in the track CSV, and not one worth reintroducing here.
     */
    val firstVisitOffsetMinutes: Int?,
    val lastVisitOffsetMinutes: Int?,
    val totalSeconds: Long,
) {
    /**
     * A link that opens this place in Google Maps.
     *
     * Written into the exported file, and handed to an ACTION_VIEW intent when
     * the user taps the button in the naming dialog. The app itself never
     * fetches it: no network request is ever made by this app on a place's
     * behalf, so a places export is still built entirely from the file the
     * user picked.
     */
    val mapsUrl: String get() = googleMapsUrl(latitude, longitude, placeId)
}

/**
 * A link that opens this place in Google Maps.
 *
 * The documented Maps URLs form, not the `maps/place/?q=place_id:…` one this
 * first shipped with. That older spelling is undocumented and Maps treats it
 * as literal search text: it opened the app with `place_id:ChIJ97_m…` typed
 * into the search box and no result, which is exactly the wrong outcome for a
 * button whose whole job is telling the user which place this is.
 *
 * `query` carries the coordinates and is required on every search request;
 * `query_place_id` names the place. Google uses the query "only if Google Maps
 * cannot find the place ID", which makes the pair strictly better than either
 * alone — a stale or unresolvable ID degrades to a pin at the right spot
 * instead of an empty search. That is also why this returns a link for a place
 * with no ID at all, where the previous version returned null.
 *
 * Coordinates are formatted through [java.util.Locale.ROOT] with a fixed
 * number of decimals rather than interpolated: `Double.toString` switches to
 * scientific notation for small magnitudes, and "1.0E-5" in a URL is a broken
 * link for anyone near the equator or the prime meridian.
 */
fun googleMapsUrl(latitude: Double, longitude: Double, placeId: String?): String {
    val query = String.format(Locale.ROOT, "%.7f%%2C%.7f", latitude, longitude)
    val id = placeId?.takeIf { it.isNotEmpty() }
    val suffix = if (id == null) "" else "&query_place_id=$id"
    return "https://www.google.com/maps/search/?api=1&query=$query$suffix"
}
