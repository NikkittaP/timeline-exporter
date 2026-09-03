package io.github.nikkittap.timelineexporter.parser

import java.time.Instant
import kotlin.math.roundToLong

/**
 * One distinct place, with every visit to it folded into a single row.
 *
 * This is what the Places list shows and what the Places export writes. It is
 * an aggregate over [Segment]s of kind [SegmentKind.VISIT]: the reference
 * export records 936 visits against 282 places, so listing visits raw would
 * show the same home address 292 times.
 *
 * [key] is the identity visits were grouped on, and the key a user's own label
 * is stored under. It is the Google place ID wherever there is one — which, in
 * a phone takeout, is every single visit (0 of 936 lacked one) — and a rounded
 * coordinate otherwise, so the older Takeout layouts, which sometimes name a
 * place without identifying it, still aggregate instead of producing one row
 * per stay.
 */
data class PlaceEntry(
    val key: String,
    val placeId: String?,
    val latitude: Double,
    val longitude: Double,
    /**
     * Google's `semanticType` — INFERRED_HOME, INFERRED_WORK,
     * SEARCHED_ADDRESS or UNKNOWN — chosen across all visits rather than taken
     * from the first, because Google is not consistent within one place: in
     * the reference export the most-visited place is typed INFERRED_HOME on
     * some visits, INFERRED_WORK and SEARCHED_ADDRESS on others. Picking the
     * most specific type seen is what makes "Home" appear at the top of the
     * list instead of a coordinate.
     */
    val semanticType: String?,
    /** Set only by the older Takeout layout; null in a phone export. */
    val name: String?,
    val address: String?,
    val hierarchyLevel: Int,
    val visits: Int,
    val firstVisit: Instant,
    val lastVisit: Instant,
    val firstVisitOffsetMinutes: Int?,
    val lastVisitOffsetMinutes: Int?,
    val totalSeconds: Long,
) {
    /** True when Google typed this as the user's home. */
    val isHome: Boolean get() = semanticType == SEMANTIC_HOME

    /** True when Google typed this as the user's workplace. */
    val isWork: Boolean get() = semanticType == SEMANTIC_WORK

    companion object {
        const val SEMANTIC_HOME = "INFERRED_HOME"
        const val SEMANTIC_WORK = "INFERRED_WORK"
        const val SEMANTIC_SEARCHED = "SEARCHED_ADDRESS"
    }
}

/**
 * Most specific first. A place carrying several types across its visits is
 * described by the most informative one — "home" tells the user more than
 * "searched address", which tells them more than "unknown".
 */
private val SEMANTIC_PRIORITY = listOf(
    PlaceEntry.SEMANTIC_HOME,
    PlaceEntry.SEMANTIC_WORK,
    PlaceEntry.SEMANTIC_SEARCHED,
)

/**
 * Distinct places, most-visited first, ties broken by total time spent.
 *
 * [range] narrows to visits that *start* inside it, matching how
 * `movementBreakdown` and `placeCount` already treat a date range, so the
 * three summaries in the UI always describe the same set of segments.
 *
 * [includeContainers] controls Google's `hierarchyLevel`. Level 1 is the thing
 * a level-0 place sits inside — a shop's mall, a terminal's airport — and it
 * is not an extra place the user went to: measured on the reference export,
 * all 81 level-1 visits overlap a level-0 visit in time, 35 of them to the
 * very second. Counting both would report two stays for one afternoon and
 * double the time spent, so containers are excluded by default. They are still
 * offered as a toggle because 46 distinct places appear *only* at level 1, and
 * for a user who shopped at a mall without Google pinning the shop, that
 * container is the only record that they were there at all.
 *
 * Visits are matched to places by `placeId` where present and by rounded
 * coordinates otherwise; a visit that carries neither a place nor coordinates
 * cannot be identified and is dropped.
 */
fun ParsedTimeline.placeEntries(
    range: ClosedRange<Instant>? = null,
    includeContainers: Boolean = false,
): List<PlaceEntry> {
    val grouped = LinkedHashMap<String, MutableList<Segment>>()
    for (seg in segments) {
        if (seg.kind != SegmentKind.VISIT) continue
        val place = seg.place ?: continue
        if (range != null && seg.start !in range) continue
        if (!includeContainers && (place.hierarchyLevel ?: 0) > 0) continue
        grouped.getOrPut(placeKey(place)) { mutableListOf() }.add(seg)
    }
    return grouped.map { (key, visits) -> fold(key, visits) }
        .sortedWith(compareByDescending<PlaceEntry> { it.visits }.thenByDescending { it.totalSeconds })
}

/**
 * The identity two visits have to share to be the same place.
 *
 * Coordinates are rounded to five decimals — about a metre — before being used
 * as a fallback key. Google restates a place's location per visit and the
 * value is not bit-stable: two of the 282 places in the reference export moved
 * by roughly five metres between visits, which at full precision would split
 * them into two rows.
 */
private fun placeKey(place: Place): String {
    val id = place.placeId
    if (!id.isNullOrEmpty()) return id
    val lat = (place.latitude * 1e5).roundToLong()
    val lon = (place.longitude * 1e5).roundToLong()
    return "@$lat,$lon"
}

private fun fold(key: String, visits: List<Segment>): PlaceEntry {
    // Segments arrive sorted by start time, and the grouping above preserves
    // that order, so first and last are the ends of the range without a scan.
    val first = visits.first()
    val last = visits.last()
    val places = visits.mapNotNull { it.place }

    val types = places.mapNotNull { it.semanticType }.filter { it.isNotEmpty() }
    val semanticType = SEMANTIC_PRIORITY.firstOrNull { it in types }
        ?: types.firstOrNull { it != "UNKNOWN" }
        ?: types.firstOrNull()

    // The most recent statement of where the place is: Google occasionally
    // nudges a place's coordinates between visits, and the newer fix is the
    // one more likely to match what Maps shows today.
    val located = last.place ?: first.place!!

    return PlaceEntry(
        key = key,
        placeId = located.placeId?.takeIf { it.isNotEmpty() },
        latitude = located.latitude,
        longitude = located.longitude,
        semanticType = semanticType,
        name = places.firstNotNullOfOrNull { it.name?.takeIf { n -> n.isNotBlank() } },
        address = places.firstNotNullOfOrNull { it.address?.takeIf { a -> a.isNotBlank() } },
        hierarchyLevel = located.hierarchyLevel ?: 0,
        visits = visits.size,
        firstVisit = first.start,
        lastVisit = last.start,
        firstVisitOffsetMinutes = first.tzOffsetMinutes,
        lastVisitOffsetMinutes = last.tzOffsetMinutes,
        totalSeconds = visits.sumOf { it.end.epochSecond - it.start.epochSecond },
    )
}
