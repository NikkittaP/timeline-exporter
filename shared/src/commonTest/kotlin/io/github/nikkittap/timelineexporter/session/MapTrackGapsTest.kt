package io.github.nikkittap.timelineexporter.session

import io.github.nikkittap.timelineexporter.parser.PathPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The dashed connectors on the iOS map, held to what the Android map draws
 * (prepareTrackGeometry): measured on the samples as recorded, within one
 * journey, and never between two journeys.
 */
class MapTrackGapsTest {

    private fun p(i: Int, latitude: Double, longitude: Double) =
        PathPoint(Instant.fromEpochSeconds(1_750_000_000L + i * 600L), latitude, longitude)

    private fun run(vararg points: PathPoint) = Run(points.toList(), "")

    @Test
    fun `a jump is one connector between the samples on either side of it`() {
        val a = p(1, 48.01, 2.01)
        val b = p(2, 52.0, 13.0)
        val t = buildMapTrack(listOf(run(p(0, 48.0, 2.0), a, b, p(3, 52.01, 13.01))), 1000)
        assertEquals(1, t.gapCount)
        assertEquals(listOf(48.01, 2.01, 52.0, 13.0), t.gapCoordinates.toList())
    }

    @Test
    fun `thinning a long drive does not turn it into connectors`() {
        // 1 km apart, every 100th kept: 100 km between the points that stay.
        val drive = List(200) { p(it, 40.0 + it * 0.009, 5.0) }
        val t = buildMapTrack(listOf(Run(drive, "")), budget = 2)
        assertEquals(0, t.gapCount)
        assertEquals(1, t.pieceCount)
    }

    @Test
    fun `thinning keeps the samples a jump runs between`() {
        val near = List(5) { p(it, 48.0 + it * 0.001, 2.0) }
        val far = List(5) { p(it + 5, 52.0 + it * 0.001, 13.0) }
        val t = buildMapTrack(listOf(Run(near + far, "")), budget = 2)
        assertEquals(listOf(48.004, 2.0, 52.0, 13.0), t.gapCoordinates.toList())
        assertEquals(2, t.pieceCount)
    }

    @Test
    fun `a flight alone draws only its connector`() {
        val t = buildMapTrack(listOf(run(p(0, 48.0, 2.0), p(1, 52.0, 13.0))), 1000)
        assertEquals(0, t.pointCount)
        assertEquals(0, t.pieceCount)
        assertEquals(1, t.gapCount)
        assertEquals(listOf(48.0, 2.0, 52.0, 13.0), t.endpoints.toList())
    }

    @Test
    fun `nothing connects one journey to the next`() {
        val t = buildMapTrack(
            listOf(
                Run(listOf(p(0, 48.0, 2.0), p(1, 48.01, 2.01)), "#111111"),
                Run(listOf(p(2, 52.0, 13.0), p(3, 52.01, 13.01)), "#222222"),
            ),
            1000,
        )
        assertEquals(0, t.gapCount)
    }

    @Test
    fun `the track starts at its first sample even when no line starts there`() {
        val t = buildMapTrack(listOf(run(p(0, 48.0, 2.0), p(1, 52.0, 13.0), p(2, 52.01, 13.01))), 1000)
        assertEquals(listOf(48.0, 2.0, 52.01, 13.01), t.endpoints.toList())
    }

    @Test
    fun `a day cut does not end a journey`() {
        val before = listOf(p(0, 48.0, 2.0), p(1, 48.01, 2.01))
        val after = listOf(p(2, 52.0, 13.0), p(3, 52.01, 13.01))
        val t = buildMapTrack(
            listOf(Run(before, "#111111", 20_000), Run(after, "#222222", 20_001)),
            1000,
            journeys = listOf(before + after),
        )
        assertEquals(listOf(48.01, 2.01, 52.0, 13.0), t.gapCoordinates.toList())
        assertEquals(2, t.pieceCount)
    }

    @Test
    fun `an empty track has no connectors and no endpoints`() {
        val t = buildMapTrack(emptyList(), 1000)
        assertEquals(0, t.gapCount)
        assertEquals(emptyList(), t.endpoints.toList())
    }
}
