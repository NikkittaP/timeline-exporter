package io.github.nikkittap.timelineexporter.session

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString

/** The iOS facade: files in, files out, plain values across. */
class TimelineSessionTest {

    private val created = ArrayList<Path>()

    @AfterTest
    fun cleanUp() {
        created.forEach { SystemFileSystem.delete(it, mustExist = false) }
    }

    private fun tempFile(name: String, text: String? = null): Path {
        val path = Path(SystemTemporaryDirectory, "tls-${name}-${created.size}-${kotlin.random.Random.nextInt(1_000_000)}")
        created += path
        if (text != null) SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
        return path
    }

    private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    // A walk on one day, a drive on the next; a stay between them.
    private val json = """
        { "semanticSegments": [
            { "startTime": "2025-06-01T08:00:00.000Z", "endTime": "2025-06-01T09:00:00.000Z",
              "activity": { "distanceMeters": 3000.0, "topCandidate": { "type": "WALKING" } } },
            { "startTime": "2025-06-01T08:00:00.000Z", "endTime": "2025-06-01T09:00:00.000Z",
              "timelinePath": [
                { "point": "48.0°, 2.0°", "time": "2025-06-01T08:10:00.000Z" },
                { "point": "48.1°, 2.1°", "time": "2025-06-01T08:40:00.000Z" } ] },
            { "startTime": "2025-06-01T09:00:00.000Z", "endTime": "2025-06-02T10:00:00.000Z",
              "visit": { "topCandidate": { "placeId": "p1", "placeLocation": { "latLng": "48.1°, 2.1°" } } } },
            { "startTime": "2025-06-02T10:00:00.000Z", "endTime": "2025-06-02T11:00:00.000Z",
              "activity": { "distanceMeters": 50000.0, "topCandidate": { "type": "IN_PASSENGER_VEHICLE" } } },
            { "startTime": "2025-06-02T10:00:00.000Z", "endTime": "2025-06-02T11:00:00.000Z",
              "timelinePath": [
                { "point": "48.1°, 2.1°", "time": "2025-06-02T10:05:00.000Z" },
                { "point": "48.5°, 2.5°", "time": "2025-06-02T10:30:00.000Z" },
                { "point": "49.0°, 3.0°", "time": "2025-06-02T10:55:00.000Z" } ] }
        ] }
    """.trimIndent()

    private fun open(text: String = json): TimelineSession =
        TimelineSession.open(tempFile("in", text).toString(), TimeZone.UTC) {}

    @Test
    fun `summary values cross as plain numbers`() {
        val s = open()
        assertEquals(5, s.pointCount)
        assertEquals("PHONE_TAKEOUT", s.formatName)
        assertEquals(Instant.parse("2025-06-01T08:10:00Z").toEpochMilliseconds(), s.firstPointMillis)
        assertEquals(Instant.parse("2025-06-02T10:55:00Z").toEpochMilliseconds(), s.lastPointMillis)
        assertEquals(2, s.dayCount)
        assertEquals(53000.0, s.totalDistanceMeters)
        assertEquals(listOf("WALKING", "DRIVING"), s.availableMovements())
    }

    @Test
    fun `filters by range and movement and time of day`() {
        val s = open()
        assertEquals(5, s.filteredCount(FilterSpec()))
        val day2 = Instant.parse("2025-06-02T00:00:00Z").toEpochMilliseconds()
        assertEquals(3, s.filteredCount(FilterSpec(rangeStartMillis = day2, rangeEndMillis = day2 + 86_399_999)))
        assertEquals(2, s.filteredCount(FilterSpec(movements = setOf("WALKING"))))
        assertEquals(0, s.filteredCount(FilterSpec(movements = emptySet())))
        assertEquals(2, s.filteredCount(FilterSpec(timeStartMinute = 8 * 60, timeEndMinute = 9 * 60)))
        // A whole-day window and a half-set range are no constraint.
        assertEquals(5, s.filteredCount(FilterSpec(timeStartMinute = 0, timeEndMinute = 0)))
        assertEquals(5, s.filteredCount(FilterSpec(rangeStartMillis = day2)))
    }

    @Test
    fun `breakdown follows the range`() {
        val s = open()
        val day2 = Instant.parse("2025-06-02T00:00:00Z").toEpochMilliseconds()
        assertEquals(
            listOf(MovementRow("DRIVING", 1, 50000.0), MovementRow("WALKING", 1, 3000.0)),
            s.movementBreakdown(FilterSpec()),
        )
        assertEquals(
            listOf(MovementRow("DRIVING", 1, 50000.0)),
            s.movementBreakdown(FilterSpec(rangeStartMillis = day2, rangeEndMillis = day2 + 86_399_999)),
        )
    }

    @Test
    fun `exports every format to a file`() {
        val s = open()
        for (format in ExportFormat.entries) {
            val out = tempFile("out.${format.fileExtension}")
            assertEquals(2, s.exportTrack(FilterSpec(movements = setOf("WALKING")), format, false, "Test track ü", out.toString()))
            val text = read(out)
            assertTrue(text.contains("48.1"), "${format.name}: $text")
            if (format != ExportFormat.CSV) assertTrue(text.contains("Test track ü"), "${format.name}: $text")
        }
    }

    @Test
    fun `an empty selection writes nothing`() {
        val s = open()
        val out = tempFile("empty.gpx")
        assertEquals(0, s.exportTrack(FilterSpec(movements = emptySet()), ExportFormat.GPX, false, "x", out.toString()))
        assertEquals(false, SystemFileSystem.exists(out))
    }

    @Test
    fun `long output survives the chunked writer`() {
        val path = tempFile("long")
        SystemFileSystem.sink(path).buffered().use { sink ->
            val out = SinkAppendable(sink)
            repeat(5000) { out.append("ab😀"); out.append('c') }
            out.append("xyz", 1, 3)
            out.flush()
        }
        val expected = buildString { repeat(5000) { append("ab😀c") }; append("yz") }
        assertEquals(expected, read(path))
    }

    @Test
    fun `progress reaches sorting and the bar never goes backwards`() {
        val seen = ArrayList<LoadProgress>()
        TimelineSession.open(tempFile("p", json).toString(), TimeZone.UTC) { seen += it }
        assertEquals(LoadProgress.STAGE_SORTING, seen.last().stage)
        val fractions = seen.filter { it.stage == LoadProgress.STAGE_READING }.map { it.fraction }
        assertEquals(fractions.sorted(), fractions)
        assertTrue(fractions.all { it in 0.0..1.0 })
    }

    @Test
    fun `a file that is not a Timeline is a wrong file`() {
        assertFailsWith<WrongFileException> { open("""{ "hello": 1 }""") }
        assertFailsWith<WrongFileException> { open("not json at all") }
    }

    @Test
    fun `filename names the dates and the window`() {
        val day = Instant.parse("2025-06-01T00:00:00Z").toEpochMilliseconds()
        assertEquals("Timeline.gpx", suggestTrackFilename(FilterSpec(), ExportFormat.GPX, TimeZone.UTC))
        assertEquals(
            "Timeline_2025-06-01_to_2025-06-02_0730-1900.geojson",
            suggestTrackFilename(
                FilterSpec(day, day + 2 * 86_400_000 - 1, timeStartMinute = 450, timeEndMinute = 1140),
                ExportFormat.GEOJSON,
                TimeZone.UTC,
            ),
        )
    }

    @Test
    fun `weighted progress gives the expensive head most of the bar`() {
        assertEquals(0.45, weightedProgress(50, 1000, 100), 1e-9)
        assertEquals(0.9, weightedProgress(100, 1000, 100), 1e-9)
        assertEquals(1.0, weightedProgress(1000, 1000, 100), 1e-9)
        assertEquals(0.5, weightedProgress(500, 1000, null), 1e-9)
    }

    @Test
    fun `the Swift entry points report instead of throwing`() {
        val ok = openTimelineFile(tempFile("ok", json).toString()) {}
        assertEquals(5, ok.session?.pointCount)
        val wrong = openTimelineFile(tempFile("bad", "[1, 2").toString()) {}
        assertEquals(true, wrong.wrongFile)
        assertEquals(null, wrong.session)
        val missing = openTimelineFile(tempFile("missing").toString()) {}
        assertEquals(null, missing.session)

        val spec = filterSpec(false, 1, 0, setOf("DRIVING"), false, false, false, 0, 0)
        assertEquals(FilterSpec(movements = setOf("DRIVING")), spec)
        val out = tempFile("r.csv")
        val result = ok.session!!.exportTrackResult(spec, ExportFormat.CSV, false, "n", out.toString())
        assertEquals(3, result.count)
        assertEquals(false, result.failed)
        val bad = ok.session!!.exportTrackResult(spec, ExportFormat.CSV, false, "n", "/no/such/dir/x.csv")
        assertEquals(true, bad.failed)
    }

    @Test
    fun `places cross with their maps link and coordinates`() {
        val s = open()
        val places = s.places(FilterSpec(), includeContainers = false)
        assertEquals(1, places.size)
        val p = places.single()
        assertEquals("p1", p.placeId)
        assertEquals(1, p.visits)
        assertEquals("48.10000, 2.10000", p.coordinates)
        assertTrue(p.mapsUrl.contains("query_place_id=p1"))

        val out = tempFile("places")
        val r = s.exportPlacesResult(FilterSpec(), false, mapOf(p.key to "Cafe"), ExportFormat.CSV, "Places", out.toString())
        assertEquals(1, r.count)
        assertTrue(read(out).contains("Cafe"))
    }

    @Test
    fun `map track is cut by day when colouring by day`() {
        val s = open()
        val plain = s.mapTrack(FilterSpec(), colorByDay = false, budget = 1000)
        assertEquals(5, plain.pointCount)
        assertEquals(1, plain.pieceCount)
        assertEquals(listOf(""), plain.pieceColors)

        val byDay = s.mapTrack(FilterSpec(), colorByDay = true, budget = 1000)
        assertEquals(listOf(0, 2), byDay.pieceStarts.toList())
        assertEquals(2, byDay.pieceColors.distinct().size)
        assertTrue(byDay.pieceColors.all { it.startsWith("#") })
    }

    @Test
    fun `map track is thinned to the budget and keeps the last point`() {
        val s = open()
        val thin = s.mapTrack(FilterSpec(), colorByDay = false, budget = 2)
        assertEquals(5, thin.sourcePoints)
        assertEquals(3, thin.pointCount)
        assertEquals(49.0, thin.latitudes.last())
    }

    @Test
    fun `map track breaks at a long jump`() {
        val jump = """
            { "semanticSegments": [
                { "startTime": "2025-06-01T08:00:00.000Z", "endTime": "2025-06-01T12:00:00.000Z",
                  "timelinePath": [
                    { "point": "48.0°, 2.0°", "time": "2025-06-01T08:10:00.000Z" },
                    { "point": "48.01°, 2.01°", "time": "2025-06-01T08:20:00.000Z" },
                    { "point": "52.0°, 13.0°", "time": "2025-06-01T11:00:00.000Z" },
                    { "point": "52.01°, 13.01°", "time": "2025-06-01T11:10:00.000Z" } ] }
            ] }
        """.trimIndent()
        val t = open(jump).mapTrack(FilterSpec(), colorByDay = false, budget = 1000)
        assertEquals(listOf(0, 2), t.pieceStarts.toList())
        assertEquals(4, t.pointCount)
    }

    @Test
    fun `image stats count the drawn days and distance`() {
        val s = open()
        val stats = s.trackImageStats(FilterSpec())!!
        assertEquals(2, stats.dayCount)
        assertEquals(stats.firstEpochDay + 1, stats.lastEpochDay)
        assertTrue(stats.distanceMeters > 0)
        assertEquals(null, s.trackImageStats(FilterSpec(rangeStartMillis = 0, rangeEndMillis = 1)))
        assertEquals(null, s.tripsImageStats(FilterSpec(), LongArray(0)))
    }

    @Test
    fun `trips cross as a list even when there are none`() {
        val s = open()
        val list = s.tripList()
        assertTrue(list.trips.isEmpty())
        val out = tempFile("trips")
        val r = s.exportTripsResult(FilterSpec(), LongArray(0), ExportFormat.GPX, "Trips", out.toString())
        assertEquals(0, r.count)
        assertEquals(0, s.mapTrips(FilterSpec(), LongArray(0), 1000).pointCount)
    }
}
