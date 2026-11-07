import XCTest
@testable import TimelineExporter

/// Runs inside the app (hosted test), so Bundle.main is the app bundle and the
/// Shared.xcframework linked into the app is the one exercised.
@MainActor
final class TimelineStoreTests: XCTestCase {
    private var sampleURL: URL!

    override func setUp() async throws {
        let bundled = try XCTUnwrap(Bundle.main.url(forResource: "sample-timeline", withExtension: "json"))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        sampleURL = dir.appendingPathComponent("Timeline.json")
        try FileManager.default.copyItem(at: bundled, to: sampleURL)
    }

    func testOpensTheSampleThroughTheSharedCore() async throws {
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: sampleURL)
        XCTAssertEqual(store.phase, .loaded)
        let core = try XCTUnwrap(store.timeline)
        XCTAssertEqual(core.pointCount, 3)
        XCTAssertEqual(core.segmentCount, 1)
        XCTAssertEqual(store.filteredCount, 3)
        XCTAssertEqual(store.fileName, "Timeline.json")
        // The distance is Google's own, from activity segments; the sample has none.
        XCTAssertEqual(store.totalDistance, 0)
    }

    func testNotATimelineFileIsReportedAsSuch() async throws {
        let url = sampleURL.deletingLastPathComponent().appendingPathComponent("other.json")
        try Data(#"{"hello": "world"}"#.utf8).write(to: url)
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: url)
        XCTAssertEqual(store.phase, .failed(wrongFile: true, errorClass: "", message: nil))
        XCTAssertNil(store.timeline)
    }

    func testARangeOutsideTheDataMatchesNothing() async throws {
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: sampleURL)
        let day = Date(timeIntervalSince1970: 1_000_000_000)
        store.setRange(Calendar.current.dayRange(from: day, to: day))
        await store.recount()
        XCTAssertEqual(store.filteredCount, 0)
        await store.export(.gpx)
        XCTAssertEqual(store.exportState, .nothing(.track))
        XCTAssertNil(store.pendingExport)
    }

    func testEveryFormatExportsToATemporaryFile() async throws {
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: sampleURL)
        for format in ExportFormatChoice.allCases {
            await store.export(format)
            let pending = try XCTUnwrap(store.pendingExport, format.rawValue)
            XCTAssertEqual(pending.count, 3)
            XCTAssertEqual(pending.url.pathExtension, format.fileExtension)
            XCTAssertTrue(pending.url.lastPathComponent.hasPrefix("Timeline"))
            let text = try String(contentsOf: pending.url, encoding: .utf8)
            XCTAssertFalse(text.isEmpty)
            store.finishSave(savedAs: "copy.\(format.fileExtension)")
            XCTAssertEqual(store.exportState, .saved(kind: .track, count: 3, fileName: "copy.\(format.fileExtension)"))
            XCTAssertFalse(FileManager.default.fileExists(atPath: pending.url.path(percentEncoded: false)))
        }
    }

    func testTheFilterIsRememberedForTheNextFile() async throws {
        let settings = InMemorySettingsStore()
        let store = TimelineStore(settings: settings)
        await store.open(url: sampleURL)
        store.setMovingOnly(true)
        store.setTimeOfDay(TimeWindow(start: 22 * 60, end: 6 * 60))
        store.colorByDay = true

        let again = TimelineStore(settings: settings)
        XCTAssertTrue(again.colorByDay)
        await again.open(url: sampleURL)
        XCTAssertTrue(again.filter.movingOnly)
        XCTAssertEqual(again.filter.timeOfDay, TimeWindow(start: 22 * 60, end: 6 * 60))
    }

    func testAWholeDayWindowIsNoWindow() async throws {
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: sampleURL)
        store.setTimeOfDay(TimeWindow(start: 0, end: 1440))
        XCTAssertNil(store.filter.timeOfDay)
    }

    func testTheMapLineFollowsTheTrack() async throws {
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: sampleURL)
        XCTAssertEqual(store.mapLine.latitudes.count, 3)
        XCTAssertEqual(store.mapLine.pieces.count, 1)
        XCTAssertEqual(store.mapLine.pieces.first?.range, 0..<3)

        let day = Date(timeIntervalSince1970: 1_000_000_000)
        store.setRange(Calendar.current.dayRange(from: day, to: day))
        await store.recount()
        XCTAssertTrue(store.mapLine.isEmpty)
    }

    func testTheMapImageGetsTheLineAndItsStatistics() async throws {
        let store = TimelineStore(settings: InMemorySettingsStore())
        await store.open(url: sampleURL)
        XCTAssertTrue(store.canMakeMapImage)
        let content = await store.mapImageContent()
        XCTAssertEqual(content.line, store.mapLine)
        let stats = try XCTUnwrap(content.stats)
        XCTAssertLessThanOrEqual(stats.firstDay, stats.lastDay)
        XCTAssertGreaterThanOrEqual(stats.dayCount, 1)
        XCTAssertGreaterThan(stats.distanceMeters, 0)
        XCTAssertEqual(content.placeCount, 0)

        store.setContentKind(.places)
        XCTAssertFalse(store.canMakeMapImage)
    }

    func testASampleWithoutVisitsHasNoPlacesOrTrips() async throws {
        let suite = "PlacesTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = TimelineStore(settings: InMemorySettingsStore(), labels: PlaceLabels(defaults))
        await store.open(url: sampleURL)

        store.setContentKind(.places)
        await store.recount()
        XCTAssertTrue(store.places.isEmpty)
        XCTAssertTrue(store.mapLine.isEmpty)
        await store.export(.kml)
        XCTAssertEqual(store.exportState, .nothing(.places))

        store.setContentKind(.trips)
        await store.recount()
        XCTAssertTrue(store.visibleTrips.isEmpty)
        await store.export(.gpx)
        XCTAssertEqual(store.exportState, .nothing(.trips))
        XCTAssertNil(store.pendingExport)
    }
}

final class PlaceLabelsTests: XCTestCase {
    func testLabelsPersistAndFallBack() throws {
        let suite = "PlaceLabelsTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let place = PlaceItem(
            key: "ChIJ1", placeId: "ChIJ1", latitude: 48.85, longitude: 2.29, name: nil, address: nil,
            visits: 2, lastVisit: Date(), totalSeconds: 3600, isHome: false, isWork: false,
            coordinates: "48.85000, 2.29000", mapsURL: nil
        )
        let labels = PlaceLabels(defaults)
        XCTAssertEqual(labels.displayName(place), "48.85000, 2.29000")
        labels.set("ChIJ1", "  Gym  ")
        XCTAssertEqual(PlaceLabels(defaults).displayName(place), "Gym")
        labels.set("ChIJ1", " ")
        XCTAssertNil(PlaceLabels(defaults)["ChIJ1"])
    }
}

final class SettingsTests: XCTestCase {
    func testUserDefaultsRoundTrip() throws {
        let suite = "SettingsTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsSettingsStore(defaults)

        var s = RememberedSettings()
        let day = Date(timeIntervalSince1970: 1_750_000_000)
        s.filter.range = Calendar.current.dayRange(from: day, to: day)
        s.filter.movements = [.walking, .cycling]
        s.filter.dropRepeated = true
        s.filter.timeOfDay = TimeWindow(start: 360, end: 720)
        s.shareInstead = true
        store.save(s)

        let loaded = store.load()
        XCTAssertEqual(loaded.filter.movements, [.walking, .cycling])
        XCTAssertEqual(loaded.filter.timeOfDay, TimeWindow(start: 360, end: 720))
        XCTAssertTrue(loaded.filter.dropRepeated)
        XCTAssertFalse(loaded.filter.movingOnly)
        XCTAssertTrue(loaded.shareInstead)
        let range = try XCTUnwrap(loaded.filter.range)
        XCTAssertEqual(range.lowerBound.timeIntervalSince1970, s.filter.range!.lowerBound.timeIntervalSince1970, accuracy: 0.001)

        store.save(RememberedSettings())
        XCTAssertEqual(store.load(), RememberedSettings())
    }

    func testARememberedRangeMissingTheFileIsDropped() {
        var s = RememberedSettings()
        let old = Date(timeIntervalSince1970: 1_000_000_000)
        s.filter.range = Calendar.current.dayRange(from: old, to: old)
        s.filter.movements = Set(Movement.allCases)
        let first = Date(timeIntervalSince1970: 1_700_000_000)
        let f = s.filter(dataFirst: first, dataLast: first.addingTimeInterval(86_400))
        XCTAssertNil(f.range)
        XCTAssertNil(f.movements)
    }

    func testLastDaysEndsOnTheAnchorDay() {
        let cal = Calendar.current
        let anchor = cal.date(from: DateComponents(year: 2025, month: 6, day: 30, hour: 15))!
        let r = cal.lastDays(7, endingAt: anchor)
        XCTAssertEqual(cal.component(.day, from: r.lowerBound), 24)
        XCTAssertEqual(cal.component(.day, from: r.upperBound), 30)
    }
}
