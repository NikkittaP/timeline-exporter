package io.github.nikkittap.timelineexporter.ui

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlin.math.abs

/** Continuous real track edges for a transient map effect. */
internal class RouteMotion private constructor(private val edges: List<Edge>, private val total: Double, val focusPoints: List<PathPoint>? = null) {
    private val runBounds: Map<Int, Pair<Double, Double>> = buildMap {
        for (edge in edges) {
            val first = get(edge.run)?.first ?: edge.start
            put(edge.run, first to (edge.start + edge.length))
        }
    }
    private data class Edge(val from: PathPoint, val to: PathPoint, val start: Double, val length: Double, val run: Int)
    internal data class Position(val latitude: Double, val longitude: Double, val bearing: Double, val run: Int, val alpha: Float)

    fun position(fraction: Double): Position? {
        if (edges.isEmpty()) return null
        val distance = fraction.coerceIn(0.0, 1.0) * total
        var low = 0
        var high = edges.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (edges[mid].start + edges[mid].length < distance) low = mid + 1 else high = mid
        }
        val edge = edges[low]
        val t = ((distance - edge.start) / edge.length).coerceIn(0.0, 1.0)
        val deltaLon = wrap(edge.to.longitude - edge.from.longitude)
        val lat = edge.from.latitude + (edge.to.latitude - edge.from.latitude) * t
        val lon = wrap(edge.from.longitude + deltaLon * t)
        val dy = Math.toRadians(edge.to.latitude - edge.from.latitude)
        val dx = Math.toRadians(deltaLon) * kotlin.math.cos(Math.toRadians(lat))
        val bearing = Math.toDegrees(kotlin.math.atan2(dx, dy))
        val fadeDistance = total * 0.015
        val bounds = runBounds.getValue(edge.run)
        val before = if (edge.run != edges.first().run) distance - bounds.first else fadeDistance
        val after = if (edge.run != edges.last().run) bounds.second - distance else fadeDistance
        val alpha = (minOf(before, after) / fadeDistance).coerceIn(0.0, 1.0).toFloat()
        return Position(lat, lon, bearing, edge.run, alpha)
    }

    companion object {
        /** Never bridge trips, invalid coordinates or the map's geographic gaps. */
        fun prepare(trips: List<List<PathPoint>>, shouldContinue: () -> Boolean = { true }): RouteMotion? {
            val edges = ArrayList<Edge>()
            var total = 0.0
            var run = 0
            var visited = 0
            for (trip in trips) {
                var previous: PathPoint? = null
                run++
                for (point in trip) {
                    if (++visited % 1024 == 0 && !shouldContinue()) return null
                    if (!valid(point)) { previous = null; run++; continue }
                    val prior = previous
                    if (prior != null) {
                        val length = haversineMeters(prior, point)
                        if (!length.isFinite() || length > GAP_DISTANCE_METERS) run++
                        else if (length > 0.01) {
                            edges += Edge(prior, point, total, length, run)
                            total += length
                        }
                    }
                    previous = point
                }
            }
            if (edges.isEmpty() || !total.isFinite()) return null
            // A world-scale history compresses a whole local route to a few
            // pixels. Pick its longest continuous run for a legible flight.
            if (total > 2_000_000.0 || edges.size > 15_000) {
                val lengths = mutableMapOf<Int, Double>()
                for (edge in edges) lengths[edge.run] = (lengths[edge.run] ?: 0.0) + edge.length
                val best = lengths.maxByOrNull { it.value }?.key
                var sectionLength = 0.0
                val chosen = edges.asSequence().filter { it.run == best }.take(5_000).takeWhile { edge ->
                    val keep = sectionLength < 1_000_000.0
                    sectionLength += edge.length
                    keep
                }.toList()
                if (chosen.isNotEmpty() && (chosen.size < edges.size || total > 2_000_000.0)) {
                    var distance = 0.0
                    val compact = chosen.map { edge -> edge.copy(start = distance).also { distance += edge.length } }
                    val points = buildList { add(chosen.first().from); chosen.forEach { add(it.to) } }
                    return RouteMotion(compact, distance, points)
                }
            }
            return RouteMotion(edges, total)
        }
        private fun valid(p: PathPoint) = p.latitude.isFinite() && p.longitude.isFinite() && abs(p.latitude) <= 90.0 && abs(p.longitude) <= 180.0
        private fun wrap(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0
    }
}
