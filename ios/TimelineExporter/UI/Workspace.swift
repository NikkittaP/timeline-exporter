import SwiftUI

/// The loaded file's screen. One column on a phone: the header, then the Track
/// map or the Trips or Places list, with the Export bar under it. On a wide
/// window that column is the pane beside the map (PageLayout).
///
/// Nothing here scrolls as a page: the header and the Export bar stay put, and
/// only a list scrolls, inside its own part of the screen.
struct Workspace: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass
    let core: CoreTimeline
    let mode: PageLayout.Mode
    let pageSize: CGSize
    @Binding var mapState: PreviewMapState
    @Binding var importing: Bool
    @Binding var detail: DetailRoute?
    let onJourney: (JourneyScene) -> Void
    @State private var filtersShown = false
    @State private var exportShown = false

    /// A phone on its side: every point of height is the map's.
    private var condensed: Bool {
        mode == .compact && pageSize.height < PageLayout.condensedMaxHeight
    }

    /// The map in the column itself: always for the track, and over the list
    /// where the window is tall enough for both.
    private var showsMapInColumn: Bool {
        mode != .split && (store.contentKind == .track || mode == .stacked)
    }

    var body: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                WorkspaceHeader(
                    core: core,
                    condensed: condensed,
                    importing: $importing,
                    showFilters: { filtersShown = true },
                    onJourney: onJourney
                )
                // One map for the three modes, so the switch does not tear
                // it down: only its height changes.
                if showsMapInColumn {
                    MapPane(state: $mapState, colorToggle: true)
                        .frame(height: store.contentKind == .track
                               ? nil : PageLayout.stackedMapHeight(pageHeight: pageSize.height))
                        .frame(maxHeight: store.contentKind == .track ? .infinity : nil)
                }
                panel
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ExportBar(condensed: condensed) { exportShown = true }
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            // The column stays this one view when the width changes, so a
            // list's scroll position survives a turn.
            .frame(width: mode == .split ? PageLayout.sidebarWidth(pageWidth: pageSize.width) : nil)

            if mode == .split {
                Divider().ignoresSafeArea(.container, edges: .bottom)
                MapPane(state: $mapState, underBottomEdge: true)
                    .frame(maxWidth: .infinity)
            }
        }
        .sheet(isPresented: $filtersShown) {
            FiltersSheet()
        }
        .sheet(isPresented: $exportShown) {
            ExportSheet(wideMapImage: MapImagePreviewFrame.wideSheet(window: pageSize, sizeClass: sizeClass))
        }
        .task {
            await stageScreenshot()
        }
    }

    @ViewBuilder private var panel: some View {
        switch store.contentKind {
        case .track:
            if mode == .split {
                TrackPanel()
            }
        case .trips:
            TripsPanel(
                inPlace: mode != .compact,
                showMap: mode == .compact ? { mapState.expanded = true } : nil,
                open: open
            )
        case .places:
            PlacesPanel(
                inPlace: mode != .compact,
                showMap: mode == .compact ? { mapState.expanded = true } : nil,
                open: open
            )
        }
    }

    /// A trip's row: beside a map it is shown there, and again takes it off;
    /// on a phone it opens the trip's page.
    private func open(_ trip: TripItem) {
        if mode == .compact {
            store.selectTrip(trip.key)
            detail = .trip(trip.key)
        } else {
            store.selectTrip(store.previewedTrip == trip.key ? nil : trip.key)
        }
    }

    /// A place's row on a phone; beside a map the list handles it itself.
    private func open(_ place: PlaceItem) {
        store.selectPlace(place.key)
        detail = .place(place.key)
    }

    /// A screenshot scene's sheet or page, once the scene has settled.
    private func stageScreenshot() async {
        guard ScreenshotMode.isActive else { return }
        try? await Task.sleep(for: .seconds(1.5))
        if ScreenshotMode.opensExport {
            exportShown = true
        }
        guard ScreenshotMode.opensDetail, mode == .compact else { return }
        // The scene picks its trip or place after the file has loaded.
        for _ in 0..<40 {
            if let key = store.previewedTrip {
                detail = .trip(key)
                return
            }
            if let key = store.selectedPlace {
                detail = .place(key)
                return
            }
            try? await Task.sleep(for: .milliseconds(250))
        }
    }
}

// MARK: Header

/// The file, the Filter button and the mode switch: all that stays above the
/// content, in three short rows, or in one on a phone turned on its side.
private struct WorkspaceHeader: View {
    @Environment(TimelineStore.self) private var store
    let core: CoreTimeline
    let condensed: Bool
    @Binding var importing: Bool
    let showFilters: () -> Void
    let onJourney: (JourneyScene) -> Void
    @State private var distanceTaps = TapSequence(count: 5, window: 3)

    var body: some View {
        Group {
            if condensed {
                HStack(spacing: 10) {
                    fileMenu
                    filtersButton(titled: false)
                    modePicker
                }
            } else {
                VStack(spacing: 10) {
                    HStack(spacing: 8) {
                        fileSummary
                        Spacer(minLength: 0)
                        fileMenu
                    }
                    HStack(spacing: 10) {
                        filtersButton(titled: true)
                        filteredText
                        Spacer(minLength: 0)
                    }
                    modePicker
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 10)
        .frame(maxWidth: ReadableColumn.maxWidth)
        .frame(maxWidth: .infinity)
    }

    /// The file's name over its three figures.
    private var fileSummary: some View {
        HStack(spacing: 10) {
            Image(systemName: "doc.text.fill")
                .font(.body)
                .foregroundStyle(.tint)
                .frame(width: 34, height: 34)
                .background(
                    Color.accentColor.opacity(Design.selectedRowOpacity),
                    in: RoundedRectangle(cornerRadius: 9)
                )
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(store.fileName ?? core.formatLabel)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(stats)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .accessibilityElement(children: .combine)
        .contentShape(Rectangle())
        // How many times round the Earth, after five quick taps.
        .onTapGesture {
            let meters = store.totalDistance
            if distanceTaps.tap(), meters.isFinite, meters > 0 { onJourney(.distance(meters: meters)) }
        }
    }

    private var stats: String {
        [
            L.f("points_count", Fmt.grouped(core.pointCount)),
            L.plural("map_day_count", store.dayCount),
            Fmt.km(store.totalDistance),
        ].joined(separator: " · ")
    }

    /// What the file is, and the way to another one.
    private var fileMenu: some View {
        Menu {
            Section {
                Text(core.formatLabel)
                if let range = fileRange {
                    Text(range)
                }
            } header: {
                Text(store.fileName ?? core.formatLabel)
            }
            Button {
                importing = true
            } label: {
                Label(L.s("file_change_button"), systemImage: "doc.badge.plus")
            }
        } label: {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.body.weight(.medium))
                .frame(width: Design.minTarget, height: 34)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(L.s("file_change_button"))
        .accessibilityIdentifier("pickFile")
    }

    private var fileRange: String? {
        guard let first = core.firstPoint, let last = core.lastPoint else { return nil }
        return Fmt.dayRange(first, last)
    }

    private func filtersButton(titled: Bool) -> some View {
        let active = store.filter.activeCount
        return Button(action: showFilters) {
            HStack(spacing: 6) {
                Image(systemName: "line.3.horizontal.decrease")
                if titled {
                    Text(L.s("filter_title"))
                }
                // How many are set: quiet when none is.
                if active > 0 {
                    Text(Fmt.grouped(active))
                        .font(.caption2.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Color.accentColor, in: Capsule())
                }
            }
            .font(.subheadline.weight(.medium))
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .accessibilityLabel(L.s("filter_title"))
        .accessibilityValue(active > 0 ? filteredCount : "")
        .accessibilityIdentifier("filters")
    }

    /// What the filters leave; with none set, the dates the file covers.
    private var filteredText: some View {
        Text(store.filter.isEmpty ? (fileRange ?? filteredCount) : filteredCount)
            .font(.caption)
            .foregroundStyle(.secondary)
            .monospacedDigit()
            .lineLimit(2)
            .accessibilityIdentifier("filteredSummary")
    }

    private var filteredCount: String {
        L.f("filter_filtered_count", Fmt.grouped(store.filteredCount), Fmt.grouped(core.pointCount))
    }

    /// The system's segmented control, as it comes.
    private var modePicker: some View {
        Picker(selection: Binding(get: { store.contentKind }, set: { store.setContentKind($0) })) {
            ForEach(ContentKind.allCases) { kind in
                Text(kind.label).tag(kind)
            }
        } label: {
            EmptyView()
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("contentKind")
    }
}

// MARK: Export bar

/// Always under the content: what an export would write now, and the button
/// that opens the Export sheet.
private struct ExportBar: View {
    @Environment(TimelineStore.self) private var store
    let condensed: Bool
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                if !condensed {
                    Text(L.s("export_writes_label"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(store.exportWritesText)
                    .font(.subheadline.weight(.medium))
                    .monospacedDigit()
                    .lineLimit(2)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("exportSummary")
            Spacer(minLength: 8)
            Button(action: action) {
                Label(L.s("export_title"), systemImage: "square.and.arrow.up")
                    .font(.body.weight(.semibold))
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("exportOpen")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, condensed ? 6 : 10)
        .frame(maxWidth: ReadableColumn.maxWidth)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }
}

// MARK: Track

/// The Track's pane beside the map: what is drawn there, and how.
private struct TrackPanel: View {
    @Environment(TimelineStore.self) private var store

    var body: some View {
        @Bindable var store = store
        let moved = store.breakdown.filter { $0.trips > 0 && store.isMovementOn($0.movement) }
        List {
            Section {
                LabeledContent(L.s("metric_points")) {
                    Text(Fmt.grouped(store.filteredCount)).monospacedDigit()
                }
                if let range = store.effectiveRange {
                    LabeledContent(L.s("metric_date_range")) {
                        Text(Fmt.dayRange(range.lowerBound, range.upperBound))
                            .monospacedDigit()
                            .multilineTextAlignment(.trailing)
                    }
                }
                Toggle(L.s("map_color_by_day"), isOn: $store.colorByDay)
                    .accessibilityIdentifier("colorByDay")
            }
            if !moved.isEmpty {
                Section {
                    ForEach(moved) { stat in
                        LabeledContent {
                            Text(Fmt.km(stat.meters)).monospacedDigit()
                        } label: {
                            Label(stat.movement.label, systemImage: stat.movement.symbol)
                        }
                    }
                } header: {
                    Text(L.s("filter_movement_title"))
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}
