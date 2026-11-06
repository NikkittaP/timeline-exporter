import SwiftUI

/// Pick the days to keep: quick presets ending on the file's last day, or two
/// dates inside the file's span.
struct DateRangeSheet: View {
    let core: CoreTimeline
    let apply: (ClosedRange<Date>?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var start: Date
    @State private var end: Date

    private let first: Date
    private let last: Date

    init(core: CoreTimeline, initial: ClosedRange<Date>?, apply: @escaping (ClosedRange<Date>?) -> Void) {
        self.core = core
        self.apply = apply
        let cal = Calendar.current
        let now = Date()
        first = cal.startOfDay(for: core.firstPoint ?? now)
        last = cal.startOfDay(for: core.lastPoint ?? now)
        let r = initial ?? cal.dayRange(from: first, to: last)
        _start = State(initialValue: min(max(cal.startOfDay(for: r.lowerBound), first), last))
        _end = State(initialValue: min(max(cal.startOfDay(for: r.upperBound), first), last))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        ForEach([7, 30, 90], id: \.self) { n in
                            Button(L.plural("map_day_count", n)) {
                                finish(Calendar.current.lastDays(n, endingAt: last))
                            }
                            .buttonStyle(.bordered)
                        }
                        Button(L.s("date_preset_all_short")) { finish(nil) }
                            .buttonStyle(.bordered)
                    }
                    .frame(maxWidth: .infinity)
                }
                Section {
                    DatePicker(L.s("time_dialog_from"), selection: $start, in: first...last, displayedComponents: .date)
                    DatePicker(L.s("time_dialog_to"), selection: $end, in: first...last, displayedComponents: .date)
                }
            }
            .navigationTitle(L.s("date_dialog_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("date_dialog_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("date_dialog_apply")) {
                        let range = Calendar.current.dayRange(from: start, to: end)
                        // The whole file is no constraint at all.
                        let whole = Calendar.current.dayRange(from: first, to: last)
                        finish(range == whole ? nil : range)
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func finish(_ range: ClosedRange<Date>?) {
        apply(range)
        dismiss()
    }
}

/// Pick a window of the local clock; a window may wrap past midnight.
struct TimeOfDaySheet: View {
    let apply: (TimeWindow?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var from: Date
    @State private var to: Date

    init(initial: TimeWindow?, apply: @escaping (TimeWindow?) -> Void) {
        self.apply = apply
        let w = initial ?? TimeWindow(start: 9 * 60, end: 18 * 60)
        _from = State(initialValue: Self.date(minute: w.start))
        _to = State(initialValue: Self.date(minute: w.end))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            Button(L.s("time_preset_any")) { finish(nil) }
                                .buttonStyle(.bordered)
                            ForEach(TimeWindow.presets, id: \.key) { preset in
                                Button(L.s(preset.key)) { finish(preset.window) }
                                    .buttonStyle(.bordered)
                            }
                        }
                    }
                }
                Section {
                    DatePicker(L.s("time_dialog_from"), selection: $from, displayedComponents: .hourAndMinute)
                    DatePicker(L.s("time_dialog_to"), selection: $to, displayedComponents: .hourAndMinute)
                } footer: {
                    Text(L.s("time_dialog_local_hint"))
                }
            }
            .navigationTitle(L.s("time_dialog_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("date_dialog_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("date_dialog_apply")) {
                        finish(TimeWindow(start: Self.minute(of: from), end: Self.minute(of: to)))
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func finish(_ window: TimeWindow?) {
        apply(window)
        dismiss()
    }

    private static func date(minute: Int) -> Date {
        let m = ((minute % 1440) + 1440) % 1440
        return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
    }

    private static func minute(of date: Date) -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
}
