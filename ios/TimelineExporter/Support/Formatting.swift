import Foundation

/// Number and date formatting, the counterparts of the Android Format.kt.
enum Fmt {
    static func grouped(_ n: Int) -> String {
        n.formatted(.number.grouping(.automatic))
    }

    /// "12 km" from 10 km up, "3.4 km" below, as on Android.
    static func km(_ meters: Double) -> String {
        let km = meters / 1000
        let digits = km >= 10 ? 0 : 1
        return km.formatted(.number.precision(.fractionLength(digits))) + " km"
    }

    /// Decimal megabytes, as the Files app counts them.
    static func mb(_ bytes: Int64) -> String {
        (Double(bytes) / 1_000_000).formatted(.number.precision(.fractionLength(1))) + " MB"
    }

    static func date(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .omitted)
    }

    /// "9 Jun 2025 – 29 Jun 2026", or one date when both fall on the same day.
    static func dayRange(_ start: Date, _ end: Date) -> String {
        if Calendar.current.isDate(start, inSameDayAs: end) {
            return date(start)
        }
        let lower = min(start, end)
        let upper = max(start, end)
        return (lower..<upper).formatted(.interval.day().month(.abbreviated).year())
    }

    /// A minute of the day on the user's clock style, "07:30" or "7:30 AM".
    static func minuteOfDay(_ minute: Int) -> String {
        let m = ((minute % 1440) + 1440) % 1440
        var components = DateComponents()
        components.hour = m / 60
        components.minute = m % 60
        let date = Calendar.current.date(from: components) ?? Date(timeIntervalSince1970: 0)
        return date.formatted(date: .omitted, time: .shortened)
    }
}
