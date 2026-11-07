import Foundation
import Observation

/// The app's state: the open file, the filter, the export in progress. The
/// counterpart of the Android TimelineViewModel. Heavy work (parse, filter
/// counts, export) runs off the main thread; results land back here.
@Observable
@MainActor
final class TimelineStore {
    enum Phase: Equatable {
        case idle
        case loading(LoadProgressInfo?)
        case loaded
        /// [wrongFile]: the friendly "not a Timeline file" message, no detail.
        case failed(wrongFile: Bool, errorClass: String, message: String?)
    }

    enum ExportState: Equatable {
        case idle
        case working
        /// Saved where the user picked; [fileName] is what they named it.
        case saved(kind: ContentKind, count: Int, fileName: String?)
        /// Handed to the share sheet; nothing was saved by us.
        case shared(kind: ContentKind, count: Int, formatName: String)
        /// Nothing to write: no points, no trips or no places.
        case nothing(ContentKind)
        case failed(formatName: String, detail: String)
    }

    /// A file written to the temporary directory, waiting for the save
    /// dialog or the share sheet.
    struct PendingExport: Identifiable, Equatable {
        let url: URL
        let count: Int
        let kind: ContentKind
        let format: ExportFormatChoice
        let share: Bool
        var id: URL { url }
    }

    private(set) var phase: Phase = .idle
    private(set) var timeline: CoreTimeline?
    private(set) var fileName: String?
    private(set) var dayCount = 0
    private(set) var totalDistance = 0.0

    private(set) var filter = FilterState()
    private(set) var filteredCount = 0
    private(set) var breakdown: [MovementStat] = []
    private(set) var availableMovements: [Movement] = []

    private(set) var exportState: ExportState = .idle
    var pendingExport: PendingExport?

    // The Track / Trips / Places switch and what it shows.
    private(set) var contentKind: ContentKind = .track
    private(set) var tripList = TripListData()
    private(set) var places: [PlaceItem] = []
    /// The trip on the map; nil is every trip step 3 writes.
    private(set) var selectedTrip: Int64?
    /// Ticked for export; none ticked exports every visible trip.
    private(set) var checkedTrips: Set<Int64> = []
    private(set) var selectedPlace: String?
    /// What the map draws for Track and Trips.
    private(set) var mapLine = MapLine()
    /// Bumped when a rename lands, so views showing names refresh.
    private(set) var labelsVersion = 0
    let labels: PlaceLabels

    var includeContainers: Bool {
        didSet {
            persist { $0.includeContainers = includeContainers }
            scheduleRecount()
        }
    }

    var colorByDay: Bool {
        didSet {
            persist { $0.colorByDay = colorByDay }
            scheduleRecount()
        }
    }

    var shareInstead: Bool {
        didSet { persist { $0.shareInstead = shareInstead } }
    }

    private let settings: SettingsStore
    private var remembered: RememberedSettings
    private var loadGeneration = 0
    private var countTask: Task<Void, Never>?

    init(settings: SettingsStore = UserDefaultsSettingsStore(), labels: PlaceLabels = PlaceLabels()) {
        self.settings = settings
        self.labels = labels
        let r = settings.load()
        remembered = r
        colorByDay = r.colorByDay
        shareInstead = r.shareInstead
        includeContainers = r.includeContainers
    }

    // MARK: Loading

    /// The bundled demo history (made-up trips), for a first look without a
    /// Timeline of one's own, and for App Review. Opened from a temporary copy
    /// so it reads like any other file.
    func openSample() async {
        guard let bundled = Bundle.main.url(forResource: "timeline_demo", withExtension: "json") else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sample-timeline.json")
        try? FileManager.default.removeItem(at: url)
        guard (try? FileManager.default.copyItem(at: bundled, to: url)) != nil else { return }
        await open(url: url)
    }

    /// Parse [url]. Returns when the file is loaded or has failed.
    func open(url: URL) async {
        loadGeneration += 1
        let generation = loadGeneration
        phase = .loading(nil)
        timeline = nil
        fileName = url.lastPathComponent
        exportState = .idle
        pendingExport = nil
        filter = FilterState()
        resetContent()

        let outcome = await Task.detached(priority: .userInitiated) {
            CoreTimeline.open(url: url) { info in
                Task { @MainActor [weak self] in
                    guard let self, self.loadGeneration == generation, case .loading = self.phase else { return }
                    self.phase = .loading(info)
                }
            }
        }.value
        guard generation == loadGeneration else { return }

        switch outcome {
        case .opened(let core):
            let summary = await Task.detached(priority: .userInitiated) {
                (core.dayCount, core.totalDistanceMeters, core.availableMovements(), core.tripList())
            }.value
            guard generation == loadGeneration else { return }
            dayCount = summary.0
            totalDistance = summary.1
            availableMovements = summary.2
            tripList = summary.3
            timeline = core
            // The remembered filter goes on before the screen shows the file,
            // so the first count it shows is already the filtered one.
            filter = remembered.filter(dataFirst: core.firstPoint, dataLast: core.lastPoint)
            filteredCount = core.pointCount
            phase = .loaded
            await recount()
        case .wrongFile:
            fileName = nil
            phase = .failed(wrongFile: true, errorClass: "", message: nil)
        case .failed(let errorClass, let message):
            fileName = nil
            phase = .failed(wrongFile: false, errorClass: errorClass, message: message)
        }
    }

    /// Back to the start screen (the file stays wherever it was).
    func close() {
        loadGeneration += 1
        phase = .idle
        timeline = nil
        fileName = nil
        exportState = .idle
        pendingExport = nil
        resetContent()
    }

    private func resetContent() {
        tripList = TripListData()
        places = []
        selectedTrip = nil
        checkedTrips = []
        selectedPlace = nil
        mapLine = MapLine()
    }

    // MARK: Filter

    func setRange(_ range: ClosedRange<Date>?) {
        update { $0.range = range }
    }

    /// Tick or untick one movement group. nil ("everything") becomes every
    /// available group but this one; ticking every available group goes back
    /// to nil; ticking none is kept, and matches nothing, as on Android.
    func setMovement(_ m: Movement, enabled: Bool) {
        let available = Set(availableMovements)
        update { f in
            var current = f.movements ?? available
            if enabled { current.insert(m) } else { current.remove(m) }
            f.movements = current.isSuperset(of: available) ? nil : current
        }
    }

    func isMovementOn(_ m: Movement) -> Bool {
        filter.movements?.contains(m) ?? true
    }

    func setMovingOnly(_ on: Bool) {
        update { $0.movingOnly = on }
    }

    func setDropRepeated(_ on: Bool) {
        update { $0.dropRepeated = on }
    }

    /// A whole-day window is stored as none: it keeps everything.
    func setTimeOfDay(_ window: TimeWindow?) {
        update { $0.timeOfDay = (window?.isWholeDay ?? true) ? nil : window }
    }

    private func update(_ change: (inout FilterState) -> Void) {
        var f = filter
        change(&f)
        guard f != filter else { return }
        filter = f
        exportState = .idle
        persist { $0.filter = f }
        scheduleRecount()
    }

    private func scheduleRecount() {
        countTask?.cancel()
        countTask = Task { await recount() }
    }

    private func persist(_ change: (inout RememberedSettings) -> Void) {
        var r = remembered
        change(&r)
        guard r != remembered else { return }
        remembered = r
        settings.save(r)
    }

    /// Recompute what depends on the filter and the switch, off the main thread.
    func recount() async {
        guard let core = timeline else { return }
        let f = filter
        let kind = contentKind
        let color = colorByDay
        let containers = includeContainers
        let tripKeys = mapTripKeys
        let result = await Task.detached(priority: .userInitiated) { () -> (Int, [MovementStat], [PlaceItem], MapLine) in
            let line: MapLine
            switch kind {
            case .track: line = core.mapTrack(f, colorByDay: color)
            case .trips: line = core.mapTrips(f, keys: tripKeys)
            case .places: line = MapLine()
            }
            return (core.filteredCount(f), core.breakdown(f), core.places(f, includeContainers: containers), line)
        }.value
        guard !Task.isCancelled, f == filter, kind == contentKind, color == colorByDay,
              containers == includeContainers, tripKeys == mapTripKeys, core === timeline else { return }
        filteredCount = result.0
        breakdown = result.1
        places = result.2
        mapLine = result.3
        if let key = selectedPlace, !places.contains(where: { $0.key == key }) {
            selectedPlace = nil
        }
    }

    // MARK: Map image

    /// Whether the Map image has something to draw: the track or trips view
    /// with a line on the map.
    var canMakeMapImage: Bool {
        contentKind != .places && !mapLine.isEmpty
    }

    /// The line on the map now, with its statistics, for the Map image.
    /// Places are counted only in the track view, where the place list
    /// describes the same selection the route does.
    func mapImageContent() async -> (line: MapLine, stats: ImageStats?, placeCount: Int?) {
        let line = mapLine
        guard let core = timeline else { return (line, nil, nil) }
        let f = filter
        let kind = contentKind
        let keys = mapTripKeys
        let stats = await Task.detached(priority: .userInitiated) {
            kind == .trips ? core.tripsImageStats(f, keys: keys) : core.trackImageStats(f)
        }.value
        return (line, stats, kind == .track ? places.count : nil)
    }

    // MARK: Trips and places

    func setContentKind(_ kind: ContentKind) {
        guard kind != contentKind else { return }
        contentKind = kind
        exportState = .idle
        scheduleRecount()
    }

    /// Trips touching the date range, newest first.
    var visibleTrips: [TripItem] {
        let trips = tripList.trips
        guard let r = filter.range else { return trips.reversed() }
        return trips.filter { $0.end >= r.lowerBound && $0.start <= r.upperBound }.reversed()
    }

    /// The ticked trips still visible; empty means every visible trip.
    var exportTripKeys: [Int64] {
        visibleTrips.map(\.key).filter { checkedTrips.contains($0) }.sorted()
    }

    /// The selected trip alone, or what step 3 will write.
    private var mapTripKeys: [Int64] {
        if let key = selectedTrip { return [key] }
        return exportTripKeys
    }

    func selectTrip(_ key: Int64?) {
        selectedTrip = key
        scheduleRecount()
    }

    func setTrip(_ key: Int64, checked: Bool) {
        if checked { checkedTrips.insert(key) } else { checkedTrips.remove(key) }
        exportState = .idle
        if selectedTrip == nil { scheduleRecount() }
    }

    func clearCheckedTrips() {
        checkedTrips = []
        if selectedTrip == nil { scheduleRecount() }
    }

    func selectPlace(_ key: String?) {
        selectedPlace = key
    }

    func rename(_ key: String, to label: String?) {
        labels.set(key, label)
        labelsVersion += 1
    }

    func displayName(_ p: PlaceItem) -> String {
        _ = labelsVersion
        return labels.displayName(p)
    }

    // MARK: Export

    /// The label written inside the file: "Google Timeline 1 Jun 2025 — 7 Jun 2025, 07:00–19:00".
    var trackName: String {
        let rangePart: String
        let checked = contentKind == .trips ? visibleTrips.filter { checkedTrips.contains($0.key) } : []
        if let first = checked.min(by: { $0.start < $1.start }), let last = checked.max(by: { $0.end < $1.end }) {
            rangePart = L.f("track_name_date_range", Fmt.date(first.startDay), Fmt.date(last.endDay))
        } else if let r = filter.range {
            rangePart = L.f("track_name_date_range", Fmt.date(r.lowerBound), Fmt.date(r.upperBound))
        } else {
            rangePart = L.s("track_name_full_range")
        }
        let described: String
        if let w = filter.timeOfDay {
            described = L.f("track_name_time_window", rangePart, Fmt.minuteOfDay(w.start), Fmt.minuteOfDay(w.end))
        } else {
            described = rangePart
        }
        switch contentKind {
        case .track: return L.f("track_name", described)
        case .trips: return L.f("track_name_trips", described)
        case .places: return L.f("track_name_places", described)
        }
    }

    /// Write the filtered track in [format] to a temporary file, then hand it
    /// to the save dialog or the share sheet (pendingExport).
    func export(_ format: ExportFormatChoice) async {
        guard let core = timeline else { return }
        let kind = contentKind
        let empty: Bool
        switch kind {
        case .track: empty = filteredCount == 0
        case .trips: empty = visibleTrips.isEmpty
        case .places: empty = places.isEmpty
        }
        if empty {
            exportState = .nothing(kind)
            return
        }
        exportState = .working
        let f = filter
        let name = trackName
        let color = colorByDay
        let share = shareInstead
        let keys = exportTripKeys
        let containers = includeContainers
        var names: [String: String] = [:]
        if kind == .places {
            for p in places { names[p.key] = labels.displayName(p) }
        }
        let filename = filenameFor(kind, format: format, keys: keys)
        let result: Result<(URL, ExportOutcome), Error> = await Task.detached(priority: .userInitiated) {
            do {
                let dir = try Self.exportDirectory()
                let url = dir.appendingPathComponent(filename)
                let outcome: ExportOutcome
                switch kind {
                case .track:
                    outcome = core.exportTrack(f, format: format, colorByDay: color, name: name, to: url)
                case .trips:
                    outcome = core.exportTrips(f, keys: keys, format: format, name: name, to: url)
                case .places:
                    outcome = core.exportPlaces(f, includeContainers: containers, names: names,
                                                format: format, name: name, to: url)
                }
                return .success((url, outcome))
            } catch {
                return .failure(error)
            }
        }.value

        switch result {
        case .failure(let error):
            exportState = .failed(formatName: format.displayName, detail: Self.describe(error))
        case .success(let (url, outcome)):
            if outcome.failed {
                exportState = .failed(
                    formatName: format.displayName,
                    detail: [outcome.errorClass, outcome.message].compactMap { $0 }.joined(separator: ": ")
                )
            } else if outcome.count == 0 {
                exportState = .nothing(kind)
            } else {
                pendingExport = PendingExport(url: url, count: outcome.count, kind: kind, format: format, share: share)
            }
        }
    }

    /// The save dialog closed: [savedAs] is the name the user gave the file,
    /// nil when they cancelled.
    func finishSave(savedAs: String?) {
        guard let pending = pendingExport else { return }
        pendingExport = nil
        exportState = savedAs.map { .saved(kind: pending.kind, count: pending.count, fileName: $0) } ?? .idle
        try? FileManager.default.removeItem(at: pending.url)
    }

    /// The share sheet closed. Whether something received the file we never
    /// learn, so the message only says the file was prepared.
    func finishShare(completed: Bool) {
        guard let pending = pendingExport else { return }
        pendingExport = nil
        exportState = completed
            ? .shared(kind: pending.kind, count: pending.count, formatName: pending.format.displayName)
            : .idle
    }

    /// Android's names: Timeline[_dates][_hours].ext for the track,
    /// Timeline_trips / Timeline_places likewise, and ticked trips by their
    /// own dates or count.
    private func filenameFor(_ kind: ContentKind, format: ExportFormatChoice, keys: [Int64]) -> String {
        let track = CoreTimeline.suggestedFilename(filter, format: format)
        switch kind {
        case .track:
            return track
        case .places:
            return track.replacingOccurrences(of: "Timeline", with: "Timeline_places", options: .anchored)
        case .trips:
            let checked = visibleTrips.filter { keys.contains($0.key) }
            if checked.count == 1, let t = checked.first {
                return "Timeline_trip_\(Self.isoDay(t.startDay))_\(Self.isoDay(t.endDay)).\(format.fileExtension)"
            }
            if checked.count > 1 {
                return "Timeline_trips_\(checked.count).\(format.fileExtension)"
            }
            return track.replacingOccurrences(of: "Timeline", with: "Timeline_trips", options: .anchored)
        }
    }

    private static func isoDay(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// One export pending at a time: the previous one is cleared out first,
    /// since a whole Timeline export can be tens of megabytes.
    nonisolated static func exportDirectory() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("Export", isDirectory: true)
        if FileManager.default.fileExists(atPath: dir.path(percentEncoded: false)) {
            try FileManager.default.removeItem(at: dir)
        }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    nonisolated static func describe(_ error: Error) -> String {
        let ns = error as NSError
        return "\(ns.domain) \(ns.code): \(ns.localizedDescription)"
    }
}
