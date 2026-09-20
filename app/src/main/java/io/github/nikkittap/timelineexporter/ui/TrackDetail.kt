package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlin.math.cos
import kotlin.math.floor

/**
 * How much of a track the map is asked to draw at a given camera.
 *
 * A full phone export is 284,402 samples, and a world view is about a
 * thousand pixels across. Handing every one of them to MapLibre means
 * building a 6.8-million-character GeoJSON document, parsing it again on the
 * other side of JNI, and tiling it — all so that several hundred points can
 * be drawn into the same pixel. What the user sees is identical either way.
 *
 * So the geometry is thinned to what the current zoom can actually show, and
 * thinned less as the camera comes down, until at street level nothing is
 * dropped at all. The full list is never discarded — it is what every export
 * is written from, and what the next zoom level is re-thinned from — so
 * zooming in always has the real data to go back to.
 */

/**
 * The quantised detail step for a camera at [zoom].
 *
 * The render is keyed on this rather than on the zoom itself, so a pinch that
 * drifts through 8.1, 8.2, 8.3 rebuilds nothing. One step per zoom level is
 * already a doubling of scale, which is as fine as the distinction can
 * usefully be.
 */
internal fun trackDetailLevel(zoom: Double): Int =
    floor(zoom).toInt().coerceIn(0, FULL_DETAIL_LEVEL)

/**
 * The deviation, in metres, that may be dropped from the drawn line at [zoom]
 * and [latitude]. Zero means draw every point.
 *
 * It is a quarter of the ground distance one map pixel covers there. A map
 * pixel is two or three device pixels on a modern phone, so a quarter of one
 * is a deviation well under half a physical pixel: real, and invisible.
 */
internal fun trackToleranceMeters(zoom: Double, latitude: Double): Double {
    val level = trackDetailLevel(zoom)
    if (level >= FULL_DETAIL_LEVEL) return 0.0
    val metersPerPixel = EQUATOR_METERS_PER_PIXEL * cos(Math.toRadians(latitude)) / (1 shl level)
    return metersPerPixel * SUB_PIXEL_SHARE
}

/**
 * Drop the points of [points] that sit within [toleranceMeters] of the line
 * their neighbours already draw — Douglas-Peucker, keeping the shape of the
 * route and the two ends of it exactly.
 *
 * Iterative rather than recursive on purpose. The textbook form recurses once
 * per retained point, and a real track — a year of samples along roads that
 * bend constantly — retains enough of them to overflow the stack long before
 * it finishes.
 *
 * Distances are measured on a flat plane anchored at each segment's start,
 * which is exact enough over the few hundred metres a tolerance ever spans. A
 * segment that crosses the antimeridian measures as enormous and its points
 * are therefore all kept: more geometry than needed, never less.
 */
internal fun simplifyTrack(points: List<PathPoint>, toleranceMeters: Double): List<PathPoint> {
    if (toleranceMeters <= 0.0 || points.size < 3) return points

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true

    // Each pending range is two ints; a range only ever splits into two, so
    // the stack cannot outgrow the number of points.
    val stack = IntArray(points.size * 2)
    var top = 0
    stack[top++] = 0
    stack[top++] = points.size - 1

    val toleranceSq = toleranceMeters * toleranceMeters
    while (top > 0) {
        val last = stack[--top]
        val first = stack[--top]
        if (last <= first + 1) continue

        var farthest = -1
        var farthestSq = toleranceSq
        for (i in first + 1 until last) {
            val distanceSq = squaredDistanceToSegment(points[i], points[first], points[last])
            if (distanceSq > farthestSq) {
                farthestSq = distanceSq
                farthest = i
            }
        }
        if (farthest < 0) continue

        keep[farthest] = true
        stack[top++] = first
        stack[top++] = farthest
        stack[top++] = farthest
        stack[top++] = last
    }

    val kept = ArrayList<PathPoint>(points.size / 4 + 2)
    for (i in points.indices) if (keep[i]) kept += points[i]
    return kept
}

/** Zoom at and above which the map is given the track exactly as recorded. */
private const val FULL_DETAIL_LEVEL = 15

/** Ground metres one map pixel spans at zoom 0 on the equator, 256px tiles. */
private const val EQUATOR_METERS_PER_PIXEL = 156_543.034

/** What fraction of that pixel a dropped point may wander from the line. */
private const val SUB_PIXEL_SHARE = 0.25

private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

private fun squaredDistanceToSegment(point: PathPoint, from: PathPoint, to: PathPoint): Double {
    val metersPerLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(from.latitude))
    val pointX = (point.longitude - from.longitude) * metersPerLongitude
    val pointY = (point.latitude - from.latitude) * METERS_PER_DEGREE_LATITUDE
    val segmentX = (to.longitude - from.longitude) * metersPerLongitude
    val segmentY = (to.latitude - from.latitude) * METERS_PER_DEGREE_LATITUDE

    val segmentLengthSq = segmentX * segmentX + segmentY * segmentY
    if (segmentLengthSq == 0.0) return pointX * pointX + pointY * pointY

    val along = ((pointX * segmentX + pointY * segmentY) / segmentLengthSq).coerceIn(0.0, 1.0)
    val offsetX = pointX - along * segmentX
    val offsetY = pointY - along * segmentY
    return offsetX * offsetX + offsetY * offsetY
}
