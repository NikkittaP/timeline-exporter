import Foundation
import Shared

// The only Swift that talks to the Kotlin core (module Shared). Everything
// above it sees Swift types: Date, Int, Movement, FilterState. The Kotlin
// side is io.github.nikkittap.timelineexporter.session.TimelineSession.

/// How far a load has got. totalBytes and fraction are nil when the file
/// size is unknown.
struct LoadProgressInfo: Equatable {
    var sorting: Bool
    var bytesRead: Int64
    var totalBytes: Int64?
    var fraction: Double?
}

enum OpenOutcome {
    case opened(CoreTimeline)
    /// Not a Timeline export, not JSON, or too large to read.
    case wrongFile
    case failed(errorClass: String, message: String?)
}

struct MovementStat: Equatable, Identifiable {
    let movement: Movement
    let trips: Int
    let meters: Double
    var id: String { movement.rawValue }
}

enum ExportFormatChoice: String, CaseIterable, Identifiable {
    case gpx, kml, geojson, csv

    var id: String { rawValue }

    fileprivate var core: ExportFormat {
        switch self {
        case .gpx: .gpx
        case .kml: .kml
        case .geojson: .geojson
        case .csv: .csv
        }
    }

    var displayName: String { core.displayName }
    var fileExtension: String { core.fileExtension }
    var mimeType: String { core.mimeType }
}

struct ExportOutcome: Equatable {
    let count: Int
    let errorClass: String?
    let message: String?
    var failed: Bool { errorClass != nil }
}

/// One opened Timeline file. The Kotlin session is immutable, so this can be
/// used from any thread.
final class CoreTimeline: @unchecked Sendable {
    private let session: TimelineSession

    let pointCount: Int
    let segmentCount: Int
    /// The shared TimelineFormat name, e.g. PHONE_TAKEOUT.
    let formatName: String
    let firstPoint: Date?
    let lastPoint: Date?

    private init(_ session: TimelineSession) {
        self.session = session
        pointCount = Int(session.pointCount)
        segmentCount = Int(session.segmentCount)
        formatName = session.formatName
        if session.pointCount > 0 {
            firstPoint = Date(timeIntervalSince1970: Double(session.firstPointMillis) / 1000)
            lastPoint = Date(timeIntervalSince1970: Double(session.lastPointMillis) / 1000)
        } else {
            firstPoint = nil
            lastPoint = nil
        }
    }

    /// Days with points. Walks every point: call off the main thread.
    var dayCount: Int { Int(session.dayCount) }

    /// Google's own distance over the whole file, metres.
    var totalDistanceMeters: Double { session.totalDistanceMeters }

    var formatLabel: String {
        switch formatName {
        case "PHONE_TAKEOUT": L.s("format_phone")
        case "PHONE_TAKEOUT_ARRAY": L.s("format_phone_ios")
        case "SEMANTIC_LOCATION_HISTORY": L.s("format_semantic")
        case "RECORDS": L.s("format_records")
        default: L.s("format_unknown")
        }
    }

    // MARK: Opening

    /// Parse the file at [url] (security-scoped or not, iCloud included:
    /// the coordinator downloads it first). Blocks: call off the main thread.
    /// [progress] is called on this thread, at most every 1/2 percent.
    static func open(url: URL, progress: @escaping (LoadProgressInfo) -> Void) -> OpenOutcome {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        var outcome: OpenOutcome = .failed(errorClass: "NSFileCoordinator", message: nil)
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readURL in
            outcome = open(path: readURL.path(percentEncoded: false), progress: progress)
        }
        if let error = coordinationError {
            return .failed(errorClass: error.domain, message: error.localizedDescription)
        }
        return outcome
    }

    static func open(path: String, progress: @escaping (LoadProgressInfo) -> Void) -> OpenOutcome {
        var lastFraction = -1.0
        var lastSorting = false
        let result = TimelineSessionKt.openTimelineFile(path: path) { p in
            let fraction = p.fraction >= 0 ? p.fraction : nil
            let shown = fraction ?? 0
            // The parser reports every 100 segments; the screen needs far less.
            if p.isSorting == lastSorting && abs(shown - lastFraction) < 0.005 && fraction != nil {
                return
            }
            lastFraction = shown
            lastSorting = p.isSorting
            progress(LoadProgressInfo(
                sorting: p.isSorting,
                bytesRead: p.bytesRead,
                totalBytes: p.totalBytes >= 0 ? p.totalBytes : nil,
                fraction: fraction
            ))
        }
        if let session = result.session {
            return .opened(CoreTimeline(session))
        }
        if result.wrongFile {
            return .wrongFile
        }
        return .failed(errorClass: result.errorClass ?? "Error", message: result.errorMessage)
    }

    // MARK: Filtering

    private static func spec(_ f: FilterState) -> FilterSpec {
        TimelineSessionKt.filterSpec(
            hasRange: f.range != nil,
            rangeStartMillis: millis(f.range?.lowerBound),
            rangeEndMillis: millis(f.range?.upperBound),
            movements: f.movements.map { Set($0.map(\.rawValue)) },
            movingOnly: f.movingOnly,
            dropRepeatedPoints: f.dropRepeated,
            hasTimeOfDay: f.timeOfDay != nil,
            timeStartMinute: Int32(f.timeOfDay?.start ?? 0),
            timeEndMinute: Int32(f.timeOfDay?.end ?? 0)
        )
    }

    private static func millis(_ date: Date?) -> Int64 {
        guard let date else { return 0 }
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    func filteredCount(_ f: FilterState) -> Int {
        Int(session.filteredCount(spec: Self.spec(f)))
    }

    /// Movement groups that occur in the file, in a fixed order.
    func availableMovements() -> [Movement] {
        session.availableMovements().compactMap(Movement.init(rawValue:))
    }

    /// Per-movement trips and distance for the range and time of day in [f],
    /// longest first.
    func breakdown(_ f: FilterState) -> [MovementStat] {
        session.movementBreakdown(spec: Self.spec(f)).compactMap { row in
            Movement(rawValue: row.group).map {
                MovementStat(movement: $0, trips: Int(row.trips), meters: row.distanceMeters)
            }
        }
    }

    // MARK: Export

    /// Write the filtered track to [url] (a plain file URL the app owns).
    func exportTrack(_ f: FilterState, format: ExportFormatChoice, colorByDay: Bool, name: String, to url: URL) -> ExportOutcome {
        let r = session.exportTrackResult(
            spec: Self.spec(f),
            format: format.core,
            colorByDay: colorByDay,
            name: name,
            path: url.path(percentEncoded: false)
        )
        return ExportOutcome(count: Int(r.count), errorClass: r.errorClass, message: r.errorMessage)
    }

    static func suggestedFilename(_ f: FilterState, format: ExportFormatChoice) -> String {
        TimelineSessionKt.suggestTrackFilename(spec: spec(f), format: format.core)
    }
}

// MARK: - Trips, places, map (stage 4)

/// One trip away from home. Days are local midnights in the device calendar,
/// for formatting only.
struct TripItem: Identifiable, Equatable {
    let key: Int64
    let start: Date
    let end: Date
    let startDay: Date
    let endDay: Date
    let dayCount: Int
    let durationSeconds: Int64
    let isDayTrip: Bool
    let maxDistanceMeters: Double
    let travelledMeters: Double
    let travelledEstimated: Bool
    let placeCount: Int
    let openStart: Bool
    let openEnd: Bool
    let longAbsence: Bool
    /// TransportMode names: FLIGHT, CAR, TRAIN, BUS, BOAT, BICYCLE, WALK.
    let transport: [String]
    let color: String
    var id: Int64 { key }
}

struct TripListData: Equatable {
    var noHome = false
    /// Oldest first.
    var trips: [TripItem] = []
}

/// One place, as the shared core aggregates it; the display name is the
/// app's (PlaceLabels).
struct PlaceItem: Identifiable, Equatable {
    let key: String
    let placeId: String?
    let latitude: Double
    let longitude: Double
    let name: String?
    let address: String?
    let visits: Int
    let lastVisit: Date
    let totalSeconds: Int64
    let isHome: Bool
    let isWork: Bool
    let coordinates: String
    let mapsURL: URL?
    var id: String { key }
}

/// A line for the map: coordinates cut into coloured pieces. A piece with
/// color "" takes the app's track colour.
/// The Map image's statistics: epoch days in the session's zone.
struct ImageStats: Equatable {
    let firstDay: Int64
    let lastDay: Int64
    let dayCount: Int
    let distanceMeters: Double
}

struct MapLine: Equatable {
    struct Piece: Equatable {
        let range: Range<Int>
        let color: String
    }

    var latitudes: [Double] = []
    var longitudes: [Double] = []
    var pieces: [Piece] = []
    var isEmpty: Bool { latitudes.isEmpty }
}

extension CoreTimeline {
    /// Points the map draws at most; thinned evenly above it.
    static let mapPointBudget: Int32 = 60_000

    /// Every trip. Detection walks the whole file: call off the main thread.
    func tripList() -> TripListData {
        let list = session.tripList()
        return TripListData(noHome: list.noHome, trips: list.trips.map { t in
            TripItem(
                key: t.key,
                start: Self.date(t.startMillis),
                end: Self.date(t.endMillis),
                startDay: Self.localDay(t.startEpochDay),
                endDay: Self.localDay(t.endEpochDay),
                dayCount: Int(t.dayCount),
                durationSeconds: t.durationSeconds,
                isDayTrip: t.isDayTrip,
                maxDistanceMeters: t.maxDistanceMeters,
                travelledMeters: t.travelledMeters,
                travelledEstimated: t.travelledEstimated,
                placeCount: Int(t.placeCount),
                openStart: t.openStart,
                openEnd: t.openEnd,
                longAbsence: t.longAbsence,
                transport: t.transport,
                color: t.color
            )
        })
    }

    func places(_ f: FilterState, includeContainers: Bool) -> [PlaceItem] {
        session.places(spec: Self.spec(f), includeContainers: includeContainers).map { p in
            PlaceItem(
                key: p.key,
                placeId: p.placeId,
                latitude: p.latitude,
                longitude: p.longitude,
                name: p.name,
                address: p.address,
                visits: Int(p.visits),
                lastVisit: Self.date(p.lastVisitMillis),
                totalSeconds: p.totalSeconds,
                isHome: p.isHome,
                isWork: p.isWork,
                coordinates: p.coordinates,
                mapsURL: URL(string: p.mapsUrl)
            )
        }
    }

    func mapTrack(_ f: FilterState, colorByDay: Bool) -> MapLine {
        Self.line(session.mapTrack(spec: Self.spec(f), colorByDay: colorByDay, budget: Self.mapPointBudget))
    }

    /// The trips' lines: [keys] picks some, empty is every trip in the range.
    func mapTrips(_ f: FilterState, keys: [Int64]) -> MapLine {
        Self.line(session.mapTrips(spec: Self.spec(f), keys: Self.longArray(keys), budget: Self.mapPointBudget))
    }

    /// What the Map image's card says about the filtered track.
    func trackImageStats(_ f: FilterState) -> ImageStats? {
        Self.imageStats(session.trackImageStats(spec: Self.spec(f)))
    }

    /// The same for trips: [keys] picks some, empty is every trip in the range.
    func tripsImageStats(_ f: FilterState, keys: [Int64]) -> ImageStats? {
        Self.imageStats(session.tripsImageStats(spec: Self.spec(f), keys: Self.longArray(keys)))
    }

    private static func imageStats(_ s: MapImageStats?) -> ImageStats? {
        guard let s else { return nil }
        return ImageStats(firstDay: s.firstEpochDay, lastDay: s.lastEpochDay,
                          dayCount: Int(s.dayCount), distanceMeters: s.distanceMeters)
    }

    func exportTrips(_ f: FilterState, keys: [Int64], format: ExportFormatChoice, name: String, to url: URL) -> ExportOutcome {
        let r = session.exportTripsResult(
            spec: Self.spec(f), keys: Self.longArray(keys), format: format.core, name: name,
            path: url.path(percentEncoded: false)
        )
        return ExportOutcome(count: Int(r.count), errorClass: r.errorClass, message: r.errorMessage)
    }

    func exportPlaces(_ f: FilterState, includeContainers: Bool, names: [String: String],
                      format: ExportFormatChoice, name: String, to url: URL) -> ExportOutcome {
        let r = session.exportPlacesResult(
            spec: Self.spec(f), includeContainers: includeContainers, names: names, format: format.core,
            name: name, path: url.path(percentEncoded: false)
        )
        return ExportOutcome(count: Int(r.count), errorClass: r.errorClass, message: r.errorMessage)
    }

    private static func line(_ t: MapTrack) -> MapLine {
        let n = Int(t.pointCount)
        var line = MapLine()
        line.latitudes.reserveCapacity(n)
        line.longitudes.reserveCapacity(n)
        for i in 0..<Int32(n) {
            line.latitudes.append(t.latitudes.get(index: i))
            line.longitudes.append(t.longitudes.get(index: i))
        }
        let pieces = Int(t.pieceCount)
        for i in 0..<pieces {
            let from = Int(t.pieceStarts.get(index: Int32(i)))
            let to = i + 1 < pieces ? Int(t.pieceStarts.get(index: Int32(i + 1))) : n
            line.pieces.append(MapLine.Piece(range: from..<to, color: t.pieceColors[i]))
        }
        return line
    }

    private static func longArray(_ values: [Int64]) -> KotlinLongArray {
        let array = KotlinLongArray(size: Int32(values.count))
        for (i, v) in values.enumerated() {
            array.set(index: Int32(i), value: v)
        }
        return array
    }

    private static func date(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(millis) / 1000)
    }

    /// LocalDate.toEpochDays() as that date's midnight on this device.
    static func localDay(_ epochDay: Int64) -> Date {
        var c = DateComponents()
        c.year = 1970
        c.month = 1
        c.day = 1
        let cal = Calendar.current
        let epoch = cal.date(from: c) ?? Date(timeIntervalSince1970: 0)
        return cal.date(byAdding: .day, value: Int(epochDay), to: epoch) ?? epoch
    }
}
