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
