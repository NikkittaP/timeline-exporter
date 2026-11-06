import Foundation

/// A window of the local clock, minutes from midnight. start == end would be
/// the whole day, which is stored as no window at all.
struct TimeWindow: Equatable, Codable {
    var start: Int
    var end: Int

    var isWholeDay: Bool { start == end || (start == 0 && end == 1440) }

    static let presets: [(key: String, window: TimeWindow)] = [
        ("time_preset_morning", TimeWindow(start: 6 * 60, end: 12 * 60)),
        ("time_preset_day", TimeWindow(start: 9 * 60, end: 18 * 60)),
        ("time_preset_evening", TimeWindow(start: 18 * 60, end: 0)),
        ("time_preset_night", TimeWindow(start: 22 * 60, end: 6 * 60)),
    ]
}

/// Google's activity types, in the buckets the filter shows. Raw values are
/// the shared core's MovementGroup names.
enum Movement: String, CaseIterable, Identifiable {
    case walking = "WALKING"
    case cycling = "CYCLING"
    case driving = "DRIVING"
    case transit = "TRANSIT"
    case flying = "FLYING"
    case other = "OTHER"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .walking: L.s("movement_walking")
        case .cycling: L.s("movement_cycling")
        case .driving: L.s("movement_driving")
        case .transit: L.s("movement_transit")
        case .flying: L.s("movement_flying")
        case .other: L.s("movement_other")
        }
    }

    var symbol: String {
        switch self {
        case .walking: "figure.walk"
        case .cycling: "bicycle"
        case .driving: "car.fill"
        case .transit: "tram.fill"
        case .flying: "airplane"
        case .other: "ellipsis.circle"
        }
    }
}

/// What the user has set. nil means "no constraint" everywhere, as in the
/// shared TimelineFilter; an empty movement set matches nothing.
struct FilterState: Equatable {
    var range: ClosedRange<Date>?
    var movements: Set<Movement>?
    var movingOnly = false
    var dropRepeated = false
    var timeOfDay: TimeWindow?

    var isEmpty: Bool {
        range == nil && movements == nil && !movingOnly && !dropRepeated && timeOfDay == nil
    }
}

/// Settings remembered between launches: the filter and the export choices.
/// The keys are those of the Android SharedPreferences, for one vocabulary.
struct RememberedSettings: Equatable {
    var filter = FilterState()
    var colorByDay = false
    var shareInstead = false

    /// The filter to start a new file with: a remembered range that misses
    /// the file entirely is dropped, as are a movement set naming every group
    /// and a whole-day window - they would filter nothing and still light up.
    func filter(dataFirst: Date?, dataLast: Date?) -> FilterState {
        var f = filter
        if let r = f.range {
            if let first = dataFirst, let last = dataLast, r.lowerBound <= last, r.upperBound >= first {
                // keeps the range
            } else {
                f.range = nil
            }
        }
        if let m = f.movements, m.isEmpty || m == Set(Movement.allCases) {
            f.movements = nil
        }
        if let w = f.timeOfDay, w.isWholeDay {
            f.timeOfDay = nil
        }
        return f
    }
}

/// Where RememberedSettings live. UserDefaults in the app, memory in tests.
protocol SettingsStore {
    func load() -> RememberedSettings
    func save(_ settings: RememberedSettings)
}

final class InMemorySettingsStore: SettingsStore {
    private var settings: RememberedSettings
    init(_ settings: RememberedSettings = RememberedSettings()) { self.settings = settings }
    func load() -> RememberedSettings { settings }
    func save(_ settings: RememberedSettings) { self.settings = settings }
}

final class UserDefaultsSettingsStore: SettingsStore {
    private let defaults: UserDefaults

    init(_ defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private enum Key {
        static let rangeFrom = "range_from"
        static let rangeTo = "range_to"
        static let movements = "movements"
        static let movingOnly = "moving_only"
        static let dropRepeated = "drop_repeated"
        static let timeFrom = "time_from"
        static let timeTo = "time_to"
        static let shareInstead = "share_instead"
        static let colorByDay = "color_by_day"
    }

    func load() -> RememberedSettings {
        var s = RememberedSettings()
        if let from = defaults.object(forKey: Key.rangeFrom) as? Double,
           let to = defaults.object(forKey: Key.rangeTo) as? Double, from <= to {
            s.filter.range = Date(timeIntervalSince1970: from)...Date(timeIntervalSince1970: to)
        }
        if let names = defaults.stringArray(forKey: Key.movements) {
            s.filter.movements = Set(names.compactMap(Movement.init(rawValue:)))
        }
        s.filter.movingOnly = defaults.bool(forKey: Key.movingOnly)
        s.filter.dropRepeated = defaults.bool(forKey: Key.dropRepeated)
        if let from = defaults.object(forKey: Key.timeFrom) as? Int,
           let to = defaults.object(forKey: Key.timeTo) as? Int,
           (0...1440).contains(from), (0...1440).contains(to) {
            s.filter.timeOfDay = TimeWindow(start: from, end: to)
        }
        s.shareInstead = defaults.bool(forKey: Key.shareInstead)
        s.colorByDay = defaults.bool(forKey: Key.colorByDay)
        return s
    }

    func save(_ s: RememberedSettings) {
        if let r = s.filter.range {
            defaults.set(r.lowerBound.timeIntervalSince1970, forKey: Key.rangeFrom)
            defaults.set(r.upperBound.timeIntervalSince1970, forKey: Key.rangeTo)
        } else {
            defaults.removeObject(forKey: Key.rangeFrom)
            defaults.removeObject(forKey: Key.rangeTo)
        }
        if let m = s.filter.movements {
            defaults.set(m.map(\.rawValue).sorted(), forKey: Key.movements)
        } else {
            defaults.removeObject(forKey: Key.movements)
        }
        if let w = s.filter.timeOfDay {
            defaults.set(w.start, forKey: Key.timeFrom)
            defaults.set(w.end, forKey: Key.timeTo)
        } else {
            defaults.removeObject(forKey: Key.timeFrom)
            defaults.removeObject(forKey: Key.timeTo)
        }
        defaults.set(s.filter.movingOnly, forKey: Key.movingOnly)
        defaults.set(s.filter.dropRepeated, forKey: Key.dropRepeated)
        defaults.set(s.shareInstead, forKey: Key.shareInstead)
        defaults.set(s.colorByDay, forKey: Key.colorByDay)
    }
}

extension Calendar {
    /// Whole local days from [first] to [last]: 00:00 of the first to the
    /// last millisecond of the last.
    func dayRange(from first: Date, to last: Date) -> ClosedRange<Date> {
        let start = startOfDay(for: min(first, last))
        let lastDay = startOfDay(for: max(first, last))
        let next = date(byAdding: .day, value: 1, to: lastDay) ?? lastDay
        return start...next.addingTimeInterval(-0.001)
    }

    /// "Last N days" ending on the day of [anchor], the last point of the
    /// file rather than today: for old Timeline data that is what people mean.
    func lastDays(_ n: Int, endingAt anchor: Date) -> ClosedRange<Date> {
        let lastDay = startOfDay(for: anchor)
        let firstDay = date(byAdding: .day, value: -(n - 1), to: lastDay) ?? lastDay
        return dayRange(from: firstDay, to: lastDay)
    }
}
