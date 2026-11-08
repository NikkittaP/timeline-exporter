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

/// The collapsed Movement row's text. Names whichever side is shorter, so
/// four of five modes do not push the distance off the end of the row.
enum MovementSummary {
    static func text(selected: [Movement], available: [Movement], meters: Double,
                     movingOnly: Bool, places: Int) -> String {
        // Arabic lists with "، " and Chinese and Japanese with "、", not ", ".
        let separator = L.s("list_separator")
        let chosen = Set(selected)
        let missing = available.filter { !chosen.contains($0) }
        var parts: [String]
        if selected.isEmpty {
            parts = [L.s("filter_movement_none")]
        } else if missing.isEmpty {
            parts = [L.s("date_preset_all_short")]
        } else if missing.count < selected.count {
            parts = [L.f("filter_movement_all_except", missing.map(\.label).joined(separator: separator))]
        } else {
            parts = [selected.map(\.label).joined(separator: separator)]
        }
        if meters > 0 { parts.append(Fmt.km(meters)) }
        if movingOnly { parts.append(L.s("filter_moving_only_short")) }
        if places > 0 { parts.append(L.plural("places_count", places)) }
        return parts.joined(separator: " · ")
    }
}

/// The trips of one calendar year, for the list's year sections.
struct TripYear: Identifiable, Equatable {
    let year: Int
    var trips: [TripItem]
    var id: Int { year }

    /// [trips] are newest first; so are the years, and the trips inside each.
    static func group(_ trips: [TripItem], calendar: Calendar = .current) -> [TripYear] {
        var years: [TripYear] = []
        for trip in trips {
            let year = calendar.component(.year, from: trip.startDay)
            if let last = years.indices.last, years[last].year == year {
                years[last].trips.append(trip)
            } else {
                years.append(TripYear(year: year, trips: [trip]))
            }
        }
        return years
    }
}

/// What the screen shows and step 4 writes: the Track / Trips / Places switch.
enum ContentKind: String, CaseIterable, Identifiable {
    case track, trips, places
    var id: String { rawValue }

    var label: String {
        switch self {
        case .track: L.s("content_track")
        case .trips: L.s("content_trips")
        case .places: L.s("content_places")
        }
    }
}

/// The user's own names for places, keyed by the shared PlaceEntry key (the
/// Google place ID where there is one), so they survive a newer export.
final class PlaceLabels {
    private let defaults: UserDefaults
    private static let key = "place_labels"
    private(set) var labels: [String: String]

    init(_ defaults: UserDefaults = .standard) {
        self.defaults = defaults
        labels = defaults.dictionary(forKey: Self.key) as? [String: String] ?? [:]
    }

    subscript(key: String) -> String? { labels[key] }

    /// Name a place, or forget the name when [label] is nil or blank.
    func set(_ key: String, _ label: String?) {
        let trimmed = label?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmed.isEmpty {
            labels.removeValue(forKey: key)
        } else {
            labels[key] = trimmed
        }
        defaults.set(labels, forKey: Self.key)
    }

    /// The label, Google's name, Home / Work, or the coordinates.
    func displayName(_ p: PlaceItem) -> String {
        if let label = labels[p.key] { return label }
        if let name = p.name { return name }
        if p.isHome { return L.s("place_home") }
        if p.isWork { return L.s("place_work") }
        return p.coordinates
    }
}

/// Settings remembered between launches: the filter and the export choices.
/// The keys are those of the Android SharedPreferences, for one vocabulary.
struct RememberedSettings: Equatable {
    var filter = FilterState()
    var colorByDay = false
    var shareInstead = false
    var includeContainers = false

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
        static let includeContainers = "include_containers"
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
        s.includeContainers = defaults.bool(forKey: Key.includeContainers)
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
        defaults.set(s.includeContainers, forKey: Key.includeContainers)
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
