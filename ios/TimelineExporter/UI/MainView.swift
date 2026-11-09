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
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The window's room for the page, as laid out.
    @State private var pageSize = CGSize.zero
    @State private var mapState = PreviewMapState()

    /// One map either way, in the page or beside it. Also for the Track, so
    /// the Track / Trips / Places switch does not tear the map down and move
    /// everything; the sections themselves stay in the same Form.
    private var splitMap: Bool {
        store.phase == .loaded && PageLayout.isSplit(width: pageSize.width, sizeClass: sizeClass)
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                HStack(spacing: 0) {
                    Form {
                        if showsWelcome {
                            WelcomeSections(importing: $importing) { info = .help }
                        } else {
                            FileSection(importing: $importing)
                        }
                        if store.phase == .loaded, let core = store.timeline {
                            FilterSection(core: core, dateSheet: $dateSheet, timeSheet: $timeSheet)
                            PreviewSection(mapState: $mapState, showsMap: !splitMap)
                            switch store.contentKind {
                            case .track: EmptyView()
                            case .trips: TripsSection()
                            case .places: PlacesSection()
                            }
                            ExportSection(wideMapImage: MapImagePreviewFrame.wideSheet(window: pageSize, sizeClass: sizeClass))
                        }
                    }
                    .task(id: store.phase == .loaded) {
                        await frameScreenshot(proxy)
                    }
                    .modifier(ReadableColumn())
                    // The Form stays this one view when the width changes,
                    // so its scroll position and row state survive a turn.
                    .frame(width: splitMap ? PageLayout.sidebarWidth(pageWidth: pageSize.width) : nil)

                    if splitMap {
                        Divider().ignoresSafeArea(.container, edges: .bottom)
                        MapPane(state: $mapState)
                            .frame(maxWidth: .infinity)
                    }
                }
                .onGeometryChange(for: CGSize.self, of: { $0.size }) { pageSize = $0 }
                .expandableMap($mapState)
            }
            .navigationTitle(L.s("app_name"))
            .navigationBarTitleDisplayMode(.inline)
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
                ToolbarItem(placement: .principal) { brand }
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

    /// The root screen's identity; the one place the icon sits beside the name.
    private var brand: some View {
        HStack(spacing: 8) {
            Image("BrandIcon")
                .resizable()
                .scaledToFit()
                .frame(width: 22, height: 22)
                .clipShape(RoundedRectangle(cornerRadius: 5))
                .accessibilityHidden(true)
            Text(L.s("app_name"))
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        // Clear of the menu at the largest text, as a system title would be.
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    /// The explanation stays until a file has opened: also after a wrong
    /// file, which is when it is needed most.
    private var showsWelcome: Bool {
        switch store.phase {
        case .idle, .failed: true
        case .loading, .loaded: false
        }
    }

    /// A screenshot scene's row, scrolled into view once the scene has settled.
    private func frameScreenshot(_ proxy: ScrollViewProxy) async {
        guard store.phase == .loaded, let anchor = ScreenshotMode.anchor else { return }
        try? await Task.sleep(for: .seconds(2))
        proxy.scrollTo(anchor.id, anchor: anchor.bottom ? .bottom : .top)
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
        guard !ScreenshotMode.isActive else { return }
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

/// Loading, then what was opened. Before that the welcome screen has the
/// button, and after a failure it has the reason too.
private struct FileSection: View {
    @Environment(TimelineStore.self) private var store
    @Binding var importing: Bool

    var body: some View {
        Section {
            switch store.phase {
            case .loading(let info):
                LoadingRow(info: info)
            case .loaded:
                if let core = store.timeline {
                    LoadedSummary(core: core)
                }
                Button {
                    importing = true
                } label: {
                    Label(L.s("file_change_button"), systemImage: "doc.badge.plus")
                }
                .accessibilityIdentifier("pickFile")
            case .idle, .failed:
                EmptyView()
            }
        } header: {
            StepHeader(title: L.s("step_choose_file"))
        }
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
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "doc.text.fill")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 40, height: 40)
                    .background(
                        Color.accentColor.opacity(Design.selectedRowOpacity),
                        in: RoundedRectangle(cornerRadius: Design.controlRadius)
                    )
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    if let name = store.fileName {
                        Text(name).font(.headline).lineLimit(1).truncationMode(.middle)
                    }
                    Text(core.formatLabel).font(.footnote).foregroundStyle(.secondary)
                    if let first = core.firstPoint, let last = core.lastPoint {
                        Text(Fmt.dayRange(first, last))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .accessibilityLabel(L.s("metric_date_range") + " " + Fmt.dayRange(first, last))
                    }
                }
            }
            // Three across where they fit, one under another at the largest text.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 8) { tiles }
                VStack(alignment: .leading, spacing: 8) { tiles }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private var tiles: some View {
        MetricTile(title: L.s("metric_points"), value: Fmt.grouped(core.pointCount))
        MetricTile(title: L.s("metric_days"), value: Fmt.grouped(store.dayCount))
        MetricTile(title: L.s("metric_distance"), value: Fmt.km(store.totalDistance))
    }
}

// MARK: Filter

private struct FilterSection: View {
    @Environment(TimelineStore.self) private var store
    let core: CoreTimeline
    @Binding var dateSheet: Bool
    @Binding var timeSheet: Bool

    private static let presetDays = [7, 30, 90]

    var body: some View {
        Section {
            FilterValueRow(symbol: "calendar", title: L.s("metric_date_range"), value: rangeText, prominent: true) {
                dateSheet = true
            }
            .accessibilityIdentifier("dateRange")
            presets
            FilterValueRow(symbol: "clock", title: L.s("filter_time_any"), value: timeText) {
                timeSheet = true
            }
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
            countRow
        } header: {
            StepHeader(title: L.s("step_filter"))
        }
    }

    private var presets: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                PresetChip(title: L.s("date_preset_all_short"), selected: store.filter.range == nil) {
                    store.setRange(nil)
                }
                ForEach(Self.presetDays, id: \.self) { days in
                    PresetChip(title: L.plural("map_day_count", days), selected: isPreset(days)) {
                        if let last = core.lastPoint {
                            store.setRange(Calendar.current.lastDays(days, endingAt: last))
                        }
                    }
                }
            }
        }
    }

    private func isPreset(_ days: Int) -> Bool {
        guard let range = store.filter.range, let last = core.lastPoint else { return false }
        return range == Calendar.current.lastDays(days, endingAt: last)
    }

    /// What the filters leave, right under them, and the way back.
    private var countRow: some View {
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

/// The movement switches, one level down so the main screen stays short.
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
        .modifier(ReadableColumn())
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

/// What will be written, the format, Save or Share, and one button for it.
private struct ExportSection: View {
    @Environment(TimelineStore.self) private var store
    @AppStorage("export_format") private var formatRaw = ExportFormatChoice.gpx.rawValue
    @State private var mapImage = false
    /// The size for the Map image sheet on a wide window.
    let wideMapImage: CGSize?

    private var format: ExportFormatChoice { ExportFormatChoice(rawValue: formatRaw) ?? .gpx }

    var body: some View {
        @Bindable var store = store
        Section {
            LabeledContent(L.s("export_writes_label")) {
                Text(writesText).monospacedDigit().multilineTextAlignment(.trailing)
            }
            .accessibilityIdentifier("exportWrites")
            Picker(L.s("map_image_format"), selection: $formatRaw) {
                ForEach(ExportFormatChoice.allCases) { Text($0.displayName).tag($0.rawValue) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .accessibilityIdentifier("exportFormat")
            Picker(selection: $store.shareInstead) {
                Text(L.s("export_action_save")).tag(false)
                Text(L.s("export_action_share")).tag(true)
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
            Button {
                Task { await store.export(format) }
            } label: {
                Label(
                    L.f(store.shareInstead ? "ios_export_run_share" : "ios_export_run_save", format.displayName),
                    systemImage: store.shareInstead ? "square.and.arrow.up" : "square.and.arrow.down"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(store.exportState == .working)
            .accessibilityIdentifier("exportRun")
            .id("export")
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        } header: {
            StepHeader(title: title)
        } footer: {
            status
        }

        if store.contentKind != .places {
            Section {
                mapImageRow
            }
        }
    }

    /// A feature of its own, not a fifth file format.
    private var mapImageRow: some View {
        Button {
            mapImage = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "photo")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .frame(width: 32)
                    .accessibilityHidden(true)
                OptionLabel(title: L.s("map_image_export_entry"), hint: L.s("ios_map_image_entry_hint"))
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!store.canMakeMapImage)
        .opacity(store.canMakeMapImage ? 1 : 0.5)
        .accessibilityIdentifier("mapImage")
        .id("mapImage")
        // On the row, not the Section: from there the sheet never showed.
        .sheet(isPresented: $mapImage) {
            MapImageSheet(wide: wideMapImage)
        }
    }

    private var title: String {
        switch store.contentKind {
        case .track: L.s("step_export_track")
        case .trips: store.exportTripKeys.count == 1 ? L.s("step_export_trip") : L.s("step_export_trips")
        case .places: L.s("step_export_places")
        }
    }

    private var writesText: String {
        switch store.contentKind {
        case .track:
            return L.f("export_writes_value", L.s("content_track"), L.f("points_count", Fmt.grouped(store.filteredCount)))
        case .trips:
            // Nothing ticked writes every visible trip.
            let marked = store.exportTripKeys.count
            let count = marked == 0 ? store.visibleTrips.count : marked
            return L.f("export_writes_value", L.s(count == 1 ? "content_trip" : "content_trips"),
                       L.plural("trips_count", count))
        case .places:
            return L.f("export_writes_value", L.s("content_places"), L.plural("places_count", store.places.count))
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
