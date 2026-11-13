import SwiftUI

/// Every filter in one sheet, opened by the header's Filter button. They are
/// the file's filters, not a mode's: Track, Trips and Places all follow them.
/// Each change applies at once; the count row says what it leaves.
struct FiltersSheet: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var dateSheet = false
    @State private var timeSheet = false

    private static let presetDays = [7, 30, 90]

    var body: some View {
        NavigationStack {
            Form {
                if let core = store.timeline {
                    Section {
                        FilterValueRow(symbol: "calendar", title: L.s("metric_date_range"), value: rangeText, prominent: true) {
                            dateSheet = true
                        }
                        .accessibilityIdentifier("dateRange")
                        presets(core)
                        FilterValueRow(symbol: "clock", title: L.s("filter_time_any"), value: timeText) {
                            timeSheet = true
                        }
                    }
                    Section {
                        NavigationLink {
                            MovementFilterView()
                        } label: {
                            Label {
                                OptionLabel(title: L.s("filter_movement_title"), hint: store.movementSummary)
                            } icon: {
                                Image(systemName: "figure.walk")
                            }
                        }
                        .accessibilityIdentifier("movementFilters")
                    }
                    Section {
                        countRow(core)
                    }
                }
            }
            .navigationTitle(L.s("filter_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("whats_new_close")) { dismiss() }
                        .accessibilityIdentifier("filtersClose")
                }
            }
            .sheet(isPresented: $dateSheet) {
                if let core = store.timeline {
                    DateRangeSheet(core: core, initial: store.filter.range) { store.setRange($0) }
                }
            }
            .sheet(isPresented: $timeSheet) {
                TimeOfDaySheet(initial: store.filter.timeOfDay) { store.setTimeOfDay($0) }
            }
        }
    }

    private func presets(_ core: CoreTimeline) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                PresetChip(title: L.s("date_preset_all_short"), selected: store.filter.range == nil) {
                    store.setRange(nil)
                }
                ForEach(Self.presetDays, id: \.self) { days in
                    PresetChip(title: L.plural("map_day_count", days), selected: isPreset(days, core)) {
                        if let last = core.lastPoint {
                            store.setRange(Calendar.current.lastDays(days, endingAt: last))
                        }
                    }
                }
            }
        }
    }

    private func isPreset(_ days: Int, _ core: CoreTimeline) -> Bool {
        guard let range = store.filter.range, let last = core.lastPoint else { return false }
        return range == Calendar.current.lastDays(days, endingAt: last)
    }

    /// What the filters leave, right under them, and the way back.
    private func countRow(_ core: CoreTimeline) -> some View {
        HStack {
            Text(L.f("filter_filtered_count", Fmt.grouped(store.filteredCount), Fmt.grouped(core.pointCount)))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .monospacedDigit()
                .accessibilityIdentifier("filteredCount")
            Spacer(minLength: 8)
            if !store.filter.isEmpty {
                Button(L.s("filter_clear_button")) { store.resetFilters() }
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("resetFilters")
            }
        }
    }

    /// The dates in force: without a filter, the file's own.
    private var rangeText: String {
        guard let r = store.effectiveRange else { return L.s("date_preset_all_short") }
        return Fmt.dayRange(r.lowerBound, r.upperBound)
    }

    private var timeText: String {
        if let w = store.filter.timeOfDay {
            return L.f("filter_time_range", Fmt.minuteOfDay(w.start), Fmt.minuteOfDay(w.end))
        }
        return L.s("time_preset_any")
    }
}

/// A row that opens a filter's dialog, showing what is set.
private struct FilterValueRow: View {
    let symbol: String
    let title: String
    let value: String
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            LabeledContent {
                Text(value)
                    .font(prominent ? Font.body.weight(.semibold) : Font.body)
                    .foregroundStyle(prominent ? .primary : .secondary)
                    .monospacedDigit()
                    .multilineTextAlignment(.trailing)
            } label: {
                // On the Text and a real colour: the row is a Button, and it
                // tinted the title through a style set on the Label.
                Label {
                    Text(title).foregroundStyle(Color(.label))
                } icon: {
                    Image(systemName: symbol)
                }
            }
            .contentShape(Rectangle())
        }
    }
}

/// The movement switches, one level down so the sheet stays short.
private struct MovementFilterView: View {
    @Environment(TimelineStore.self) private var store

    var body: some View {
        Form {
            Section {
                ForEach(store.availableMovements) { m in
                    MovementRow(movement: m, stat: store.breakdown.first { $0.movement == m })
                }
            } footer: {
                Text(store.movementSummary).monospacedDigit()
            }
            Section {
                Toggle(isOn: Binding(get: { store.filter.movingOnly }, set: { store.setMovingOnly($0) })) {
                    OptionLabel(title: L.s("filter_moving_only"), hint: L.s("filter_moving_only_hint"))
                }
                Toggle(isOn: Binding(get: { store.filter.dropRepeated }, set: { store.setDropRepeated($0) })) {
                    OptionLabel(title: L.s("filter_drop_repeated"), hint: L.s("filter_drop_repeated_hint"))
                }
            }
        }
        .navigationTitle(L.s("filter_movement_title"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct MovementRow: View {
    @Environment(TimelineStore.self) private var store
    let movement: Movement
    let stat: MovementStat?

    var body: some View {
        Toggle(isOn: Binding(
            get: { store.isMovementOn(movement) },
            set: { store.setMovement(movement, enabled: $0) }
        )) {
            HStack {
                Label(movement.label, systemImage: movement.symbol)
                Spacer()
                if let stat, stat.trips > 0 {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(Fmt.km(stat.meters)).monospacedDigit()
                        Text(L.f("filter_trip_count", Fmt.grouped(stat.trips)))
                            .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    }
                }
            }
        }
    }
}
