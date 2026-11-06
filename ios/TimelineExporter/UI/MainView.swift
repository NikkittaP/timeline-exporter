import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// The one screen: choose a file, filter it, preview it on the map as the
/// track, trips or places, export. The same steps as the Android MainScreen.
struct MainView: View {
    @Environment(TimelineStore.self) private var store
    @State private var importing = false
    @State private var dateSheet = false
    @State private var timeSheet = false
    @State private var info: InfoSheet?
    @AppStorage(WhatsNew.seenKey) private var whatsNewSeen: String?

    var body: some View {
        NavigationStack {
            Form {
                FileSection(importing: $importing)
                if store.phase == .loaded, let core = store.timeline {
                    FilterSection(core: core, dateSheet: $dateSheet, timeSheet: $timeSheet)
                    PreviewSection()
                    switch store.contentKind {
                    case .track: EmptyView()
                    case .trips: TripsSection()
                    case .places: PlacesSection()
                    }
                    ExportSection()
                }
            }
            .navigationTitle(L.s("app_name"))
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
                if case .success(let url) = result {
                    Task { await store.open(url: url) }
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
            .exportPresenter()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { menu }
            }
            .sheet(item: $info) { sheet in
                switch sheet {
                case .help: HelpSheet()
                case .feedback:
                    FeedbackSheet(
                        formatName: store.timeline?.formatName,
                        pointCount: store.timeline.map { $0.pointCount }
                    )
                case .whatsNew(let entries): WhatsNewSheet(entries: entries, title: L.s("whats_new_title"))
                case .history: WhatsNewSheet(entries: history, title: L.s("whats_new_history_title"))
                }
            }
            .onAppear(perform: showWhatsNew)
        }
    }

    private var history: [WhatsNewEntry] {
        WhatsNew.history(WhatsNew.notes, current: WhatsNew.currentVersion)
    }

    private var menu: some View {
        Menu {
            Button { info = .help } label: { Label(L.s("action_help"), systemImage: "questionmark.circle") }
            Button { info = .feedback } label: { Label(L.s("feedback_title"), systemImage: "lightbulb") }
            if !history.isEmpty {
                Button { info = .history } label: { Label(L.s("whats_new_title"), systemImage: "sparkles") }
            }
            Section(L.s("language_settings_hint")) {
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Label(L.s("language_title"), systemImage: "globe")
                }
            }
            Text(L.f("app_version", WhatsNew.currentVersion))
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(L.s("action_more"))
        .accessibilityIdentifier("moreMenu")
    }

    /// After an update, the notes of every release since the last one seen;
    /// a fresh install just records its version.
    private func showWhatsNew() {
        let current = WhatsNew.currentVersion
        let pending = WhatsNew.pending(WhatsNew.notes, seen: whatsNewSeen, current: current)
        whatsNewSeen = current
        if !pending.isEmpty { info = .whatsNew(pending) }
    }
}

private enum InfoSheet: Identifiable {
    case help, feedback, history
    case whatsNew([WhatsNewEntry])

    var id: String {
        switch self {
        case .help: "help"
        case .feedback: "feedback"
        case .history: "history"
        case .whatsNew: "whatsNew"
        }
    }
}

// MARK: File

private struct FileSection: View {
    @Environment(TimelineStore.self) private var store
    @Binding var importing: Bool

    var body: some View {
        Section(L.s("step_choose_file")) {
            switch store.phase {
            case .idle:
                Text(L.s("idle_hint")).foregroundStyle(.secondary)
                pickButton(L.s("pick_file_button"))
            case .loading(let info):
                LoadingRow(info: info)
            case .loaded:
                if let core = store.timeline {
                    LoadedSummary(core: core)
                }
                pickButton(L.s("file_change_button"))
            case .failed(let wrongFile, let errorClass, let message):
                Label {
                    Text(wrongFile
                         ? L.s("error_not_timeline")
                         : L.f("parse_error", errorClass, message ?? L.s("parse_error_no_message")))
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                pickButton(L.s("pick_file_button"))
            }
        }
    }

    private func pickButton(_ title: String) -> some View {
        Button {
            importing = true
        } label: {
            Label(title, systemImage: "doc.badge.plus")
        }
        .accessibilityIdentifier("pickFile")
    }
}

private struct LoadingRow: View {
    let info: LoadProgressInfo?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let info, info.sorting {
                Text(L.s("loading_sorting"))
                ProgressView()
            } else if let info {
                Text(L.s("loading_reading"))
                if let fraction = info.fraction {
                    ProgressView(value: fraction)
                } else {
                    ProgressView()
                }
                Text(detail(info)).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
            } else {
                Text(L.s("loading_opening"))
                ProgressView()
            }
        }
        .padding(.vertical, 4)
    }

    private func detail(_ info: LoadProgressInfo) -> String {
        if let total = info.totalBytes {
            return L.f("loading_reading_detail", Fmt.mb(info.bytesRead), Fmt.mb(total))
        }
        return L.f("loading_reading_detail_unknown", Fmt.mb(info.bytesRead))
    }
}

private struct LoadedSummary: View {
    @Environment(TimelineStore.self) private var store
    let core: CoreTimeline

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let name = store.fileName {
                Label(name, systemImage: "doc.text").font(.headline).lineLimit(1).truncationMode(.middle)
            }
            Text(core.formatLabel).font(.footnote).foregroundStyle(.secondary)
            HStack(alignment: .top) {
                Metric(title: L.s("metric_points"), value: Fmt.grouped(core.pointCount))
                Metric(title: L.s("metric_days"), value: Fmt.grouped(store.dayCount))
                Metric(title: L.s("metric_distance"), value: Fmt.km(store.totalDistance))
            }
            if let first = core.firstPoint, let last = core.lastPoint {
                Metric(title: L.s("metric_date_range"), value: Fmt.dayRange(first, last))
            }
        }
        .padding(.vertical, 4)
    }
}

private struct Metric: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.body.weight(.semibold)).monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: Filter

private struct FilterSection: View {
    @Environment(TimelineStore.self) private var store
    let core: CoreTimeline
    @Binding var dateSheet: Bool
    @Binding var timeSheet: Bool

    var body: some View {
        Section {
            dateRow
            timeRow
        } header: {
            Text(L.s("step_filter"))
        } footer: {
            Text(L.f("filter_filtered_count", Fmt.grouped(store.filteredCount), Fmt.grouped(core.pointCount)))
                .monospacedDigit()
                .accessibilityIdentifier("filteredCount")
        }

        Section(L.s("filter_movement_title")) {
            ForEach(store.availableMovements) { m in
                MovementRow(movement: m, stat: store.breakdown.first { $0.movement == m })
            }
            Toggle(isOn: Binding(get: { store.filter.movingOnly }, set: { store.setMovingOnly($0) })) {
                VStack(alignment: .leading) {
                    Text(L.s("filter_moving_only"))
                    Text(L.s("filter_moving_only_hint")).font(.caption).foregroundStyle(.secondary)
                }
            }
            Toggle(isOn: Binding(get: { store.filter.dropRepeated }, set: { store.setDropRepeated($0) })) {
                VStack(alignment: .leading) {
                    Text(L.s("filter_drop_repeated"))
                    Text(L.s("filter_drop_repeated_hint")).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var dateRow: some View {
        HStack {
            Button {
                dateSheet = true
            } label: {
                LabeledContent {
                    Text(rangeText).monospacedDigit()
                } label: {
                    Label(L.s("metric_date_range"), systemImage: "calendar")
                }
            }
            .accessibilityIdentifier("dateRange")
            if store.filter.range != nil {
                clearButton { store.setRange(nil) }
            }
        }
    }

    private var timeRow: some View {
        HStack {
            Button {
                timeSheet = true
            } label: {
                LabeledContent {
                    Text(timeText).monospacedDigit()
                } label: {
                    Label(L.s("filter_time_any"), systemImage: "clock")
                }
            }
            if store.filter.timeOfDay != nil {
                clearButton { store.setTimeOfDay(nil) }
            }
        }
    }

    private func clearButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L.s("filter_clear_button"))
    }

    private var rangeText: String {
        if let r = store.filter.range {
            return Fmt.dayRange(r.lowerBound, r.upperBound)
        }
        return L.s("date_preset_all_short")
    }

    private var timeText: String {
        if let w = store.filter.timeOfDay {
            return L.f("filter_time_range", Fmt.minuteOfDay(w.start), Fmt.minuteOfDay(w.end))
        }
        return L.s("time_preset_any")
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

// MARK: Export

private struct ExportSection: View {
    @Environment(TimelineStore.self) private var store

    var body: some View {
        @Bindable var store = store
        Section {
            ForEach(ExportFormatChoice.allCases) { format in
                Button {
                    Task { await store.export(format) }
                } label: {
                    Label {
                        Text(format.displayName)
                    } icon: {
                        Image(systemName: store.shareInstead ? "square.and.arrow.up" : "square.and.arrow.down")
                    }
                }
                .disabled(store.exportState == .working)
                .accessibilityIdentifier("export-\(format.rawValue)")
            }
            Picker(selection: $store.shareInstead) {
                Text(L.s("export_action_save")).tag(false)
                Text(L.s("export_action_share")).tag(true)
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
            if store.contentKind == .track {
                Toggle(L.s("map_color_by_day"), isOn: $store.colorByDay)
            }
        } header: {
            Text(title)
        } footer: {
            status
        }
    }

    private var title: String {
        switch store.contentKind {
        case .track: L.s("step_export_track")
        case .trips: store.exportTripKeys.count == 1 ? L.s("step_export_trip") : L.s("step_export_trips")
        case .places: L.s("step_export_places")
        }
    }

    @ViewBuilder private var status: some View {
        switch store.exportState {
        case .idle:
            EmptyView()
        case .working:
            HStack(spacing: 6) {
                ProgressView()
                Text(L.s("export_writing"))
            }
        case .saved(let kind, let count, let fileName):
            Text(L.f(Self.key("export_success", kind), Fmt.grouped(count), fileName ?? L.s("export_fallback_filename")))
                .foregroundStyle(.green)
        case .shared(let kind, let count, let formatName):
            Text(L.f(Self.key("export_shared", kind), Fmt.grouped(count), formatName))
        case .nothing(let kind):
            Text(L.s(kind == .track ? "export_error_no_points" : Self.key("export_error_no", kind)))
                .foregroundStyle(.orange)
        case .failed(let formatName, let detail):
            Text(L.f("export_error_generic", formatName, detail)).foregroundStyle(.red)
        }
    }

    /// export_success, export_success_trips, export_success_places and the like.
    private static func key(_ base: String, _ kind: ContentKind) -> String {
        switch kind {
        case .track: base
        case .trips: base + "_trips"
        case .places: base + "_places"
        }
    }
}
