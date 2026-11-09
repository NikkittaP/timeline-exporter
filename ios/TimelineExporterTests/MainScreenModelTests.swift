import CoreLocation
import XCTest
@testable import TimelineExporter

/// What the main screen's rows are built from: pure model code, no views.
@MainActor
final class MainScreenModelTests: XCTestCase {
    private func trip(_ key: Int64, _ year: Int, _ month: Int, calendar: Calendar) throws -> TripItem {
        let day = try XCTUnwrap(calendar.date(from: DateComponents(year: year, month: month, day: 15)))
        return TripItem(
            key: key, start: day, end: day, startDay: day, endDay: day, dayCount: 1, durationSeconds: 3600,
            isDayTrip: true, maxDistanceMeters: 1000, travelledMeters: 1000, travelledEstimated: false,
            placeCount: 0, openStart: false, openEnd: false, longAbsence: false, transport: [], color: "#000000"
        )
    }

    private func sampleStore() async throws -> TimelineStore {
        let bundled = try XCTUnwrap(Bundle.main.url(forResource: "sample-timeline", withExtension: "json"))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("Timeline.json")
        try FileManager.default.copyItem(at: bundled, to: url)
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: url)
        return store
    }

    func testTripsGroupByYearKeepingTheNewestFirstOrder() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let trips = [
            try trip(4, 2026, 5, calendar: calendar),
            try trip(3, 2026, 1, calendar: calendar),
            try trip(2, 2025, 12, calendar: calendar),
            try trip(1, 2023, 7, calendar: calendar),
        ]
        let years = TripYear.group(trips, calendar: calendar)
        XCTAssertEqual(years.map(\.year), [2026, 2025, 2023])
        XCTAssertEqual(years[0].trips.map(\.key), [4, 3])
        XCTAssertEqual(years[1].trips.map(\.key), [2])
        XCTAssertEqual(years[2].trips.map(\.key), [1])
    }

    func testNoTripsNoYears() {
        XCTAssertTrue(TripYear.group([]).isEmpty)
    }

    func testMovementSummaryNamesTheShorterSide() {
        let available: [Movement] = Array(Movement.allCases.prefix(4))
        XCTAssertEqual(available.count, 4, "the cases below need four kinds of movement")

        XCTAssertEqual(
            MovementSummary.text(selected: [], available: available, meters: 0, movingOnly: false, places: 0),
            L.s("filter_movement_none")
        )
        XCTAssertEqual(
            MovementSummary.text(selected: available, available: available, meters: 0, movingOnly: false, places: 0),
            L.s("date_preset_all_short")
        )
        XCTAssertEqual(
            MovementSummary.text(selected: Array(available.dropFirst()), available: available, meters: 0,
                                 movingOnly: false, places: 0),
            L.f("filter_movement_all_except", available[0].label)
        )
        XCTAssertEqual(
            MovementSummary.text(selected: [available[1]], available: available, meters: 0, movingOnly: false, places: 0),
            available[1].label
        )
    }

    func testMovementSummaryAddsWhatIsSet() {
        let available: [Movement] = Array(Movement.allCases.prefix(2))
        let text = MovementSummary.text(selected: available, available: available, meters: 2500,
                                        movingOnly: true, places: 3)
        XCTAssertEqual(text, [
            L.s("date_preset_all_short"), Fmt.km(2500), L.s("filter_moving_only_short"), L.plural("places_count", 3),
        ].joined(separator: " · "))
    }

    func testResetFiltersClearsEveryFilter() async throws {
        let store = try await sampleStore()
        store.setMovingOnly(true)
        store.setDropRepeated(true)
        store.setTimeOfDay(TimeWindow(start: 60, end: 120))
        XCTAssertFalse(store.filter.isEmpty)
        store.resetFilters()
        XCTAssertTrue(store.filter.isEmpty)
    }

    func testEffectiveRangeIsTheFilesWithoutAFilter() async throws {
        let store = try await sampleStore()
        let core = try XCTUnwrap(store.timeline)
        let first = try XCTUnwrap(core.firstPoint)
        let last = try XCTUnwrap(core.lastPoint)
        XCTAssertEqual(store.effectiveRange, first...last)

        let picked = first...first
        store.setRange(picked)
        XCTAssertEqual(store.effectiveRange, picked)
    }

    func testPickedDayGoesAwayWithTheDayColours() async throws {
        let store = try await sampleStore()
        store.colorByDay = true
        await store.recount()
        let day = try XCTUnwrap(store.mapLine.days.first)
        store.selectDay(day.epochDay)
        await store.recount()
        XCTAssertEqual(store.selectedDay, day.epochDay, "still one of the line's days")

        store.colorByDay = false
        await store.recount()
        XCTAssertTrue(store.mapLine.days.isEmpty)
        XCTAssertNil(store.selectedDay)
    }

    func testAFlightAloneIsStillSomethingToShowAndFrame() {
        var line = MapLine()
        XCTAssertTrue(line.isEmpty)
        line.gaps = [MapLine.Gap(from: .init(latitude: 48, longitude: 2), to: .init(latitude: 52, longitude: 13))]
        XCTAssertFalse(line.isEmpty)
        XCTAssertEqual(line.framedLatitudes, [48, 52])
        XCTAssertEqual(line.framedLongitudes, [2, 13])
    }

    func testCameraMemoryIsOnlyForTheSameContent() {
        var line = MapLine()
        line.latitudes = [1, 2]
        line.longitudes = [3, 4]
        let content = MapContent(line: line)
        let memory = MapCameraMemory()
        XCTAssertNil(memory.saved(for: content))

        memory.save(content, center: .init(latitude: 1.5, longitude: 3.5), zoom: 9)
        XCTAssertEqual(memory.saved(for: content)?.zoom, 9)

        var other = line
        other.latitudes = [1, 5]
        XCTAssertNil(memory.saved(for: MapContent(line: other)))

        memory.clear()
        XCTAssertNil(memory.saved(for: content))
    }
}
