package io.github.nikitapetroff.timelineexporter.filter

import io.github.nikitapetroff.timelineexporter.parser.PathPoint
import java.time.Instant

/**
 * Specification for which points to keep.
 *
 * v1 only has date-range filtering. Add fields here (and matching predicates
 * in [applyFilter]) as we grow the feature set:
 *   - val activityTypes: Set<String>? = null
 *   - val boundingBox: BoundingBox? = null
 *   - val minVisitProbability: Double? = null
 */
data class TimelineFilter(
    val dateRange: ClosedRange<Instant>? = null,
) {
    /** True when no constraints are set, i.e. applyFilter is a no-op. */
    val isEmpty: Boolean get() = dateRange == null
}

/**
 * Return only the points in [points] that satisfy every constraint in [spec].
 * Order is preserved. Pure function — easy to unit-test, no Android types.
 */
fun applyFilter(points: List<PathPoint>, spec: TimelineFilter): List<PathPoint> {
    if (spec.isEmpty) return points
    return points.filter { point ->
        if (spec.dateRange != null && point.timeUtc !in spec.dateRange) return@filter false
        true
    }
}
