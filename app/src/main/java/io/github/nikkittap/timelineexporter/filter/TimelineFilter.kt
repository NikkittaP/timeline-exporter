package io.github.nikkittap.timelineexporter.filter

import io.github.nikkittap.timelineexporter.parser.MovementGroup
import io.github.nikkittap.timelineexporter.parser.PathPoint
import io.github.nikkittap.timelineexporter.parser.Segment
import io.github.nikkittap.timelineexporter.parser.SegmentKind
import java.time.Instant

/**
 * Specification for which points to keep.
 *
 * Every field defaults to "no constraint", so `TimelineFilter()` still means
 * "export everything" and existing behaviour is unchanged until the UI offers
 * the new switches.
 */
data class TimelineFilter(
    val dateRange: ClosedRange<Instant>? = null,

    /**
     * Keep only points recorded during one of these kinds of movement.
     *
     * null means no constraint. An empty set means "nothing matches" and is
     * treated literally — the UI should not let the user reach that state
     * without saying so.
     *
     * Applying this needs the segment list, because movement type and GPS
     * track live in *different* segments: a point carries no label of its own
     * and has to be joined to the activity whose time range contains it. Use
     * the [applyFilter] overload that takes segments; the points-only overload
     * ignores this field, since it has nothing to join against.
     */
    val movements: Set<MovementGroup>? = null,

    /**
     * Drop points recorded while stationary at a place rather than travelling.
     *
     * Worth its own switch because it is the single biggest reduction
     * available: in a year of real data 71% of track points fall inside a
     * visit, not a trip. What is left is the actual travelling.
     */
    val movingOnly: Boolean = false,

    /**
     * Collapse runs of consecutive points that share the exact same
     * coordinates, keeping the first of each run.
     *
     * 29.6% of points in the sample export repeat the previous coordinate
     * byte for byte — a phone at rest re-reporting the same fix. Geographically
     * these add nothing.
     *
     * Off by default on purpose. The duplicates do carry information — that
     * someone stayed put, and for how long — which a CSV consumer may be
     * counting on. Turning this on by default would silently shrink an export
     * people already depend on.
     */
    val dropRepeatedPoints: Boolean = false,
) {
    /** True when no constraints are set, i.e. applyFilter is a no-op. */
    val isEmpty: Boolean
        get() = dateRange == null &&
            movements == null &&
            !movingOnly &&
            !dropRepeatedPoints
}

/**
 * Return only the points in [points] that satisfy every constraint in [spec].
 * Order is preserved. Pure function — easy to unit-test, no Android types.
 *
 * This overload cannot evaluate [TimelineFilter.movements] or
 * [TimelineFilter.movingOnly]; both need the segment list. It leaves them
 * unapplied rather than guessing.
 */
fun applyFilter(points: List<PathPoint>, spec: TimelineFilter): List<PathPoint> =
    applyFilter(points, spec, emptyList())

/**
 * Return only the points satisfying [spec], using [segments] to resolve the
 * constraints that depend on what a point was recorded during.
 *
 * The join is a binary search per point over segments sorted by start time.
 * That relies on segments not overlapping, which holds in practice: across a
 * year of real data no two activity spans overlapped, and every path point
 * fell inside exactly one activity or visit. Where a point matches no segment
 * at all — a format that carries no segment structure, say — the
 * movement-based constraints let it through rather than silently deleting
 * data the app cannot classify.
 */
fun applyFilter(
    points: List<PathPoint>,
    spec: TimelineFilter,
    segments: List<Segment>,
): List<PathPoint> {
    if (spec.isEmpty) return points

    val index = if (spec.movements != null || spec.movingOnly) SegmentIndex.of(segments) else null

    val result = ArrayList<PathPoint>(points.size)
    var previous: PathPoint? = null
    for (point in points) {
        if (spec.dateRange != null && point.timeUtc !in spec.dateRange) continue

        if (index != null && index.isNotEmpty) {
            val segment = index.at(point.timeUtc)
            if (segment != null) {
                if (spec.movingOnly && segment.kind != SegmentKind.ACTIVITY) continue
                val wanted = spec.movements
                if (wanted != null) {
                    val group = segment.movement ?: continue
                    if (group !in wanted) continue
                }
            }
        }

        if (spec.dropRepeatedPoints) {
            val prev = previous
            if (prev != null &&
                prev.latitude == point.latitude &&
                prev.longitude == point.longitude
            ) {
                continue
            }
        }

        result.add(point)
        previous = point
    }
    return result
}

/**
 * Lookup structure answering "what was happening at this instant?".
 *
 * It indexes **only** activity and visit segments. Path segments are excluded
 * deliberately: their spans overlap the labelled ones heavily — 1927 overlaps
 * in a year of real data, most of them PATH against ACTIVITY or VISIT — so
 * including them would make the search land on a segment that carries no
 * movement type and quietly drop points that are perfectly classifiable.
 *
 * Restricted to activities and visits, the spans are effectively disjoint: the
 * same file has 83 overlaps left, all of them one visit inside another, which
 * is Google recording a shop and the mall around it. Either answer is a visit,
 * so which one wins does not matter.
 */
private class SegmentIndex private constructor(
    private val segments: List<Segment>,
    private val starts: List<Instant>,
    /** Longest span in the index; bounds how far back a lookup has to scan. */
    private val maxSpanSeconds: Long,
) {
    val isNotEmpty: Boolean get() = segments.isNotEmpty()

    /** The activity or visit covering [time], or null if nothing does. */
    fun at(time: Instant): Segment? {
        var i = upperBound(time)
        if (i < 0) return null
        // Walk back over the rare nested spans. The loop is bounded by the
        // longest span in the index, so a pathological file cannot turn this
        // into a linear scan per point.
        val floor = time.minusSeconds(maxSpanSeconds)
        while (i >= 0 && starts[i] >= floor) {
            val segment = segments[i]
            if (segment.covers(time)) return segment
            i--
        }
        return null
    }

    /** Index of the last segment starting at or before [time], or -1. */
    private fun upperBound(time: Instant): Int {
        var low = 0
        var high = starts.size - 1
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= time) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return candidate
    }

    companion object {
        fun of(segments: List<Segment>): SegmentIndex {
            val labelled = segments.filter {
                it.kind == SegmentKind.ACTIVITY || it.kind == SegmentKind.VISIT
            }
            var maxSpan = 0L
            for (segment in labelled) {
                val span = segment.end.epochSecond - segment.start.epochSecond
                if (span > maxSpan) maxSpan = span
            }
            return SegmentIndex(labelled, labelled.map { it.start }, maxSpan)
        }
    }
}
