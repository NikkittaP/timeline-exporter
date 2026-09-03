package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint
import java.time.Instant

/**
 * Which of the two things in a Timeline export the user is currently looking
 * at and about to export.
 *
 * One enum shared by the UI (the Track / Places switch in steps 3 and 4), the
 * export payload ([ExportContent]) and the result state ([ExportState]) —
 * rather than three parallel booleans that can drift out of step. 1.8.0 adds
 * TRIPS here and the switch grows a third segment.
 */
enum class ContentKind { TRACK, PLACES }

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

    /** A GPS track: what every version before 1.7.0 could export. */
    data class Track(val points: List<PathPoint>) : ExportContent {
        override val size: Int get() = points.size
        override val kind: ContentKind get() = ContentKind.TRACK
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
     * A link that opens this place in Google Maps, or null when the export
     * carried no place ID.
     *
     * Written into the exported file only. The app itself never fetches it —
     * see the same URL used by the list row's tap target, which fires an
     * ACTION_VIEW intent and nothing else. No network request is ever made by
     * this app on a place's behalf.
     */
    val mapsUrl: String?
        get() = placeId?.takeIf { it.isNotEmpty() }
            ?.let { "https://www.google.com/maps/place/?q=place_id:$it" }
}
