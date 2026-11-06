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
        case saved(count: Int, fileName: String?)
        /// Handed to the share sheet; nothing was saved by us.
        case shared(count: Int, formatName: String)
        case noPoints
        case failed(formatName: String, detail: String)
    }

    /// A file written to the temporary directory, waiting for the save
    /// dialog or the share sheet.
    struct PendingExport: Identifiable, Equatable {
        let url: URL
        let count: Int
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

    var colorByDay: Bool {
        didSet { persist { $0.colorByDay = colorByDay } }
    }

    var shareInstead: Bool {
        didSet { persist { $0.shareInstead = shareInstead } }
    }

    private let settings: SettingsStore
    private var remembered: RememberedSettings
    private var loadGeneration = 0
    private var countTask: Task<Void, Never>?

    init(settings: SettingsStore = UserDefaultsSettingsStore()) {
        self.settings = settings
        let r = settings.load()
        remembered = r
        colorByDay = r.colorByDay
        shareInstead = r.shareInstead
    }

    // MARK: Loading

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
                (core.dayCount, core.totalDistanceMeters, core.availableMovements())
            }.value
            guard generation == loadGeneration else { return }
            dayCount = summary.0
            totalDistance = summary.1
            availableMovements = summary.2
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

    /// Recompute what depends on the filter, off the main thread.
    func recount() async {
        guard let core = timeline else { return }
        let f = filter
        let result = await Task.detached(priority: .userInitiated) {
            (core.filteredCount(f), core.breakdown(f))
        }.value
        guard !Task.isCancelled, f == filter, core === timeline else { return }
        filteredCount = result.0
        breakdown = result.1
    }

    // MARK: Export

    /// The label written inside the file: "Google Timeline 1 Jun 2025 — 7 Jun 2025, 07:00–19:00".
    var trackName: String {
        let rangePart: String
        if let r = filter.range {
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
        return L.f("track_name", described)
    }

    /// Write the filtered track in [format] to a temporary file, then hand it
    /// to the save dialog or the share sheet (pendingExport).
    func export(_ format: ExportFormatChoice) async {
        guard let core = timeline else { return }
        if filteredCount == 0 {
            exportState = .noPoints
            return
        }
        exportState = .working
        let f = filter
        let name = trackName
        let color = colorByDay
        let share = shareInstead
        let filename = CoreTimeline.suggestedFilename(f, format: format)
        let result: Result<(URL, ExportOutcome), Error> = await Task.detached(priority: .userInitiated) {
            do {
                let dir = try Self.exportDirectory()
                let url = dir.appendingPathComponent(filename)
                return .success((url, core.exportTrack(f, format: format, colorByDay: color, name: name, to: url)))
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
                exportState = .noPoints
            } else {
                pendingExport = PendingExport(url: url, count: outcome.count, format: format, share: share)
            }
        }
    }

    /// The save dialog closed: [savedAs] is the name the user gave the file,
    /// nil when they cancelled.
    func finishSave(savedAs: String?) {
        guard let pending = pendingExport else { return }
        pendingExport = nil
        exportState = savedAs.map { .saved(count: pending.count, fileName: $0) } ?? .idle
        try? FileManager.default.removeItem(at: pending.url)
    }

    /// The share sheet closed. Whether something received the file we never
    /// learn, so the message only says the file was prepared.
    func finishShare(completed: Bool) {
        guard let pending = pendingExport else { return }
        pendingExport = nil
        exportState = completed ? .shared(count: pending.count, formatName: pending.format.displayName) : .idle
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
