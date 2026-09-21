package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

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
 *
 * But thinning is a cost-saving measure, not a picture: every change of level
 * is a new set of geometry for MapLibre to tile, and a line re-tiled under the
 * user's fingers flickers. So the map draws as much detail as the phone can
 * afford rather than as little as the zoom needs — see [nextDrawLevel] — and
 * a phone that can afford the whole track is handed it once and never
 * re-thinned at all.
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
internal fun trackToleranceMeters(zoom: Double, latitude: Double): Double =
    levelToleranceMeters(trackDetailLevel(zoom), latitude)

/** [trackToleranceMeters] for a detail level rather than a zoom. */
internal fun levelToleranceMeters(level: Int, latitude: Double): Double {
    if (level >= FULL_DETAIL_LEVEL) return 0.0
    val metersPerPixel = EQUATOR_METERS_PER_PIXEL * cos(Math.toRadians(latitude)) / (1 shl level)
    return metersPerPixel * SUB_PIXEL_SHARE
}

/**
 * The level to draw the track at, given the level it is drawn at now
 * ([drawn], or null for nothing drawn yet), the level the camera needs
 * ([needed]), and how many points the phone can comfortably be handed
 * ([budget]). [pointsAt] says how many points a level draws.
 *
 * What is on screen stays on screen as long as it is at least as fine as the
 * camera needs and still inside the budget: a pinch inside one city changes
 * nothing, and neither does pulling back out of it. Only when it has to
 * change is a new level chosen, and then the finest one the budget allows -
 * which on a phone that can afford the whole track is the whole track, so
 * there is never a second change to make.
 */
internal fun nextDrawLevel(drawn: Int?, needed: Int, budget: Int, pointsAt: (Int) -> Int): Int {
    if (drawn != null && drawn >= needed && pointsAt(drawn) <= budget) return drawn
    for (level in FULL_DETAIL_LEVEL downTo needed + 1) {
        if (pointsAt(level) <= budget) return level
    }
    // Nothing finer fits. The camera's own level is drawn regardless: the
    // budget is a preference, and a line coarser than a pixel is a wrong one.
    return needed
}

/**
 * How many track points this phone can be handed without the map turning
 * sluggish, from how much memory it has.
 *
 * Memory rather than a benchmark, because it is what the cost actually is:
 * every point handed to MapLibre is held as a Java object, again as parsed
 * native geometry, and again in the tile index built from it. It is also a
 * fair stand-in for the rest of the hardware - a phone with 12 GB is not
 * paired with a slow GPU. The top step takes a full year of one person's
 * history (the reference export is 284,402 points) whole.
 */
internal fun trackPointBudget(totalMemoryBytes: Long, lowRamDevice: Boolean): Int {
    if (lowRamDevice) return 20_000
    val gigabytes = totalMemoryBytes / (1L shl 30).toDouble()
    return when {
        gigabytes < 3.0 -> 40_000
        gigabytes < 5.0 -> 100_000
        gigabytes < 7.0 -> 200_000
        else -> 400_000
    }
}

/**
 * Drop the points of [points] that sit within [toleranceMeters] of the line
 * their neighbours already draw — Douglas-Peucker, keeping the shape of the
 * route and the two ends of it exactly.
 */
internal fun simplifyTrack(points: List<PathPoint>, toleranceMeters: Double): List<PathPoint> {
    if (toleranceMeters <= 0.0 || points.size < 3) return points
    return keepRanked(points, rankTrack(points), toleranceMeters)
}

/**
 * Every tolerance's Douglas-Peucker answer for [points] at once: the largest
 * tolerance at which each point would still be kept. The two ends are kept at
 * any tolerance; a point exactly on the line its neighbours draw, at none.
 *
 * Douglas-Peucker splits a range at its farthest point whatever the tolerance
 * is - the tolerance only decides where it stops. So running it once to the
 * bottom and recording each split's distance answers every tolerance: a point
 * survives a tolerance exactly when it and every split above it were farther
 * off the line than that, which is what carrying the smallest of those down
 * as `cap` records. The map re-thins a year of history on every change of
 * detail level, and asks how many points each level would draw before it
 * picks one; this makes both a pass over an array.
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
internal fun rankTrack(points: List<PathPoint>): DoubleArray {
    val rank = DoubleArray(points.size)
    if (points.isEmpty()) return rank
    rank[0] = Double.POSITIVE_INFINITY
    rank[points.size - 1] = Double.POSITIVE_INFINITY
    if (points.size < 3) return rank

    // Each pending range is two ints and the rank of the split above it; a
    // range only ever splits into two, so the stack cannot outgrow the number
    // of points.
    val bounds = IntArray(points.size * 2)
    val caps = DoubleArray(points.size)
    var top = 0
    bounds[0] = 0
    bounds[1] = points.size - 1
    caps[0] = Double.POSITIVE_INFINITY
    top++

    while (top > 0) {
        top--
        val first = bounds[top * 2]
        val last = bounds[top * 2 + 1]
        val cap = caps[top]
        if (last <= first + 1) continue

        var farthest = -1
        var farthestSq = 0.0
        for (i in first + 1 until last) {
            val distanceSq = squaredDistanceToSegment(points[i], points[first], points[last])
            if (distanceSq > farthestSq) {
                farthestSq = distanceSq
                farthest = i
            }
        }
        if (farthest < 0) continue

        val kept = minOf(sqrt(farthestSq), cap)
        rank[farthest] = kept
        bounds[top * 2] = first
        bounds[top * 2 + 1] = farthest
        caps[top] = kept
        top++
        bounds[top * 2] = farthest
        bounds[top * 2 + 1] = last
        caps[top] = kept
        top++
    }
    return rank
}

/** The points of [points] that [rank] keeps at [toleranceMeters]; all of them at zero. */
internal fun keepRanked(points: List<PathPoint>, rank: DoubleArray, toleranceMeters: Double): List<PathPoint> {
    if (toleranceMeters <= 0.0) return points
    val kept = ArrayList<PathPoint>(points.size / 4 + 2)
    for (i in points.indices) if (rank[i] > toleranceMeters) kept += points[i]
    return kept
}

/** How many points [keepRanked] would keep, without building the list. */
internal fun countRanked(rank: DoubleArray, toleranceMeters: Double): Int {
    if (toleranceMeters <= 0.0) return rank.size
    var count = 0
    for (value in rank) if (value > toleranceMeters) count++
    return count
}

/** Zoom at and above which the map is given the track exactly as recorded. */
internal const val FULL_DETAIL_LEVEL = 15

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
