import SwiftUI

extension TimelineStore {
    /// What the map draws for the view picked in the switch.
    var mapContent: MapContent {
        switch contentKind {
        case .track:
            return MapContent(line: mapLine, selectedDay: selectedDay)
        case .trips:
            return MapContent(line: mapLine)
        case .places:
            return MapContent(places: places, selectedPlace: selectedPlace)
        }
    }

    /// Why the map has nothing of the user's on it; nil when it does.
    var mapEmptyNote: String? {
        switch contentKind {
        case .track:
            return filteredCount == 0 ? L.s("preview_no_points") : nil
        case .trips:
            if tripList.noHome { return L.s("trips_no_home") }
            guard visibleTrips.isEmpty else { return nil }
            return tripList.trips.isEmpty ? L.s("trips_none") : L.s("trips_none_filtered")
        case .places:
            guard places.isEmpty else { return nil }
            return filter.range == nil ? L.s("places_none") : L.s("places_none_filtered")
        }
    }

    /// The trip drawn alone on the map, while the Trips list has it.
    var previewedTripItem: TripItem? {
        guard contentKind == .trips, let key = previewedTrip else { return nil }
        return visibleTrips.first { $0.key == key }
    }

    /// One day alone has nothing to tell apart.
    var showsDayLegend: Bool {
        contentKind == .track && colorByDay && mapLine.days.count > 1
    }
}

// MARK: The map

/// The preview map's controls. Kept above the views that draw it: the map moves
/// between the column and its pane when the window's width changes, and turning
/// the iPad must not close the full-screen map.
struct PreviewMapState {
    var fitToken = 0
    var restoreToken = 0
    var status = MapStatus.loading
    var expanded = false
}

extension View {
    /// Presents the full-screen map.
    func expandableMap(_ state: Binding<PreviewMapState>) -> some View {
        fullScreenCover(isPresented: state.expanded, onDismiss: { state.wrappedValue.restoreToken += 1 }) {
            ExpandedMap()
        }
    }
}

/// The map with what floats over it: beside the lists on a wide window, in
/// the column otherwise. The day strip, or the trip being shown, sits along
/// its bottom edge as on the full-screen map.
struct MapPane: View {
    @Environment(TimelineStore.self) private var store
    @Binding var state: PreviewMapState
    /// The pane of a wide window runs to the screen's bottom edge; in the
    /// column the Export bar is under the map.
    var underBottomEdge = false
    /// Colour by day as a button on the map, where no pane has the switch.
    var colorToggle = false
    /// The strip or the card with the gap under it, as laid out.
    @State private var overlayHeight: CGFloat = 0

    var body: some View {
        let trip = store.previewedTripItem
        ZStack(alignment: .bottom) {
            PreviewMap(
                state: $state,
                bottomOverlay: store.showsDayLegend || trip != nil ? overlayHeight : 0,
                colorToggle: colorToggle
            )
            .ignoresSafeArea(.container, edges: underBottomEdge ? .bottom : [])
            if store.showsDayLegend {
                FloatingDayStrip(height: $overlayHeight)
            } else if let trip {
                TripPreviewCard(trip: trip, height: $overlayHeight)
            }
        }
    }
}

/// The preview map with its status and its buttons, wherever it is drawn.
struct PreviewMap: View {
    @Environment(TimelineStore.self) private var store
    @Binding var state: PreviewMapState
    var bottomOverlay: CGFloat? = nil
    var colorToggle = false

    var body: some View {
        StoreMap(fitToken: state.fitToken, restoreToken: state.restoreToken, bottomOverlay: bottomOverlay,
                 status: $state.status)
            .overlay(alignment: .topLeading) {
                // Clear of the buttons in the other corner.
                MapStatusChip(status: state.status)
                    .padding(.leading, 8)
                    .padding(.top, 8)
                    .padding(.trailing, Design.minTarget + 16)
            }
            .overlay(alignment: .topTrailing) {
                MapButtonColumn {
                    MapButton(symbol: "arrow.up.left.and.arrow.down.right", label: L.s("map_expand")) {
                        state.expanded = true
                    }
                    .accessibilityIdentifier("mapExpand")
                    MapButton(symbol: "scope", label: L.s("map_recenter")) {
                        state.fitToken += 1
                    }
                    if colorToggle && store.contentKind == .track {
                        ColorByDayButton()
                            .accessibilityIdentifier("colorByDay")
                    }
                }
                .padding(8)
            }
    }
}

/// Colour by day, as one of the map's own buttons.
private struct ColorByDayButton: View {
    @Environment(TimelineStore.self) private var store

    var body: some View {
        MapButton(
            symbol: store.colorByDay ? "paintpalette.fill" : "paintpalette",
            label: L.s("map_color_by_day"),
            active: store.colorByDay
        ) {
            store.colorByDay.toggle()
        }
    }
}

/// The trip on the map, said over the map itself: which one it is, and the
/// way back to all of them.
private struct TripPreviewCard: View {
    @Environment(TimelineStore.self) private var store
    let trip: TripItem
    @Binding var height: CGFloat

    var body: some View {
        let covered = TripText.covered(trip)
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(Color(hex: trip.color))
                .frame(width: 10, height: 10)
                .padding(.top, 5)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(Fmt.dayRange(trip.startDay, trip.endDay))
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
                Text(TripText.reach(trip))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                if !covered.isEmpty {
                    Text(covered)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            TransportIcons(modes: trip.transport)
                .padding(.top, 3)
            Button {
                store.selectTrip(nil)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title3)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(store.checkedTrips.isEmpty ? L.s("trip_show_all_on_map") : L.s("trip_show_checked_on_map"))
            .accessibilityIdentifier("tripPreviewClose")
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .padding(.vertical, 10)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .mapControlSurface(cornerRadius: Design.cardRadius)
        .frame(maxWidth: 440)
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { height = $0 }
    }
}

/// The map on the store's content, with the reason when there is nothing to draw.
private struct StoreMap: View {
    @Environment(TimelineStore.self) private var store
    let fitToken: Int
    var restoreToken = 0
    var bottomOverlay: CGFloat? = nil
    @Binding var status: MapStatus

    var body: some View {
        TimelineMapView(
            content: store.mapContent,
            fitToken: fitToken,
            restoreToken: restoreToken,
            memory: store.mapCamera,
            bottomOverlay: bottomOverlay,
            onStatus: { status = $0 },
            onSelectPlace: { store.selectPlace($0) }
        )
        .overlay {
            if let note = store.mapEmptyNote {
                Text(note)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Design.controlRadius))
                    .padding(24)
                    .allowsHitTesting(false)
            }
        }
    }
}

/// Says what is wrong with the base map; nothing when it is fine. Loading
/// shows only if it takes a moment, so a quick map does not flicker a label.
private struct MapStatusChip: View {
    let status: MapStatus
    @State private var slow = false

    var body: some View {
        switch status {
        case .ready:
            EmptyView()
        case .loading:
            chip(L.s("ios_map_status_loading"), symbol: nil)
                .opacity(slow ? 1 : 0)
                .accessibilityHidden(!slow)
                .task {
                    try? await Task.sleep(for: .seconds(1))
                    if !Task.isCancelled { slow = true }
                }
                .onDisappear { slow = false }
        case .tilesUnavailable:
            chip(L.s("ios_map_status_tiles"), symbol: "wifi.slash")
        case .failed:
            chip(L.s("ios_map_status_failed"), symbol: "exclamationmark.triangle.fill")
        }
    }

    private func chip(_ text: String, symbol: String?) -> some View {
        HStack(spacing: 6) {
            if let symbol {
                Image(systemName: symbol).accessibilityHidden(true)
            } else {
                ProgressView().controlSize(.small)
            }
            Text(text).lineLimit(2)
        }
        .font(.caption)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Design.controlRadius))
        .accessibilityElement(children: .combine)
    }
}

private struct ExpandedMap: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var fitToken = 0
    @State private var status = MapStatus.loading
    /// The day strip with the gap under it, as laid out.
    @State private var stripHeight: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            StoreMap(fitToken: fitToken, bottomOverlay: store.showsDayLegend ? stripHeight : 0, status: $status)
                .ignoresSafeArea()
            HStack(alignment: .top) {
                MapStatusChip(status: status)
                Spacer(minLength: 8)
                MapButtonColumn {
                    MapButton(symbol: "xmark", label: L.s("map_exit_fullscreen")) { dismiss() }
                    MapButton(symbol: "scope", label: L.s("map_recenter")) { fitToken += 1 }
                    if store.contentKind == .track {
                        ColorByDayButton()
                    }
                }
            }
            .padding()
            .frame(maxHeight: .infinity, alignment: .top)
            if store.showsDayLegend {
                FloatingDayStrip(height: $stripHeight)
            }
        }
    }
}

/// The day legend as a control floating over a map, reporting how much of the
/// map it covers.
private struct FloatingDayStrip: View {
    @Binding var height: CGFloat

    var body: some View {
        DayLegend()
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            // One surface for the strip, none per chip; clipped first
            // so the days scroll away inside its corners.
            .clipShape(RoundedRectangle(cornerRadius: Design.cardRadius))
            .mapControlSurface(cornerRadius: Design.cardRadius)
            .frame(maxWidth: Design.dayStripMaxWidth)
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { height = $0 }
    }
}

/// The days of a line coloured by day. Picking one brings it forward on the
/// map and frames it; picking it again goes back to all of them.
private struct DayLegend: View {
    @Environment(TimelineStore.self) private var store
    @ScaledMetric(relativeTo: .footnote) private var textLine: CGFloat = 18

    /// A height of its own: a horizontal ScrollView otherwise takes all it
    /// is offered, half the screen in a full-screen layout.
    private var rowHeight: CGFloat { max(Design.minTarget, textLine + 14) }

    var body: some View {
        let days = store.mapLine.days
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                // Lazy: a history coloured by day can have hundreds of days.
                LazyHStack(spacing: 8) {
                    Text(L.plural("map_day_count", days.count))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .lineLimit(1)
                    if store.selectedDay != nil {
                        Button(L.s("map_show_all_days")) { store.selectDay(nil) }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .lineLimit(1)
                            .accessibilityIdentifier("mapShowAllDays")
                    }
                    ForEach(days) { day in
                        dayChip(day).id(day.epochDay)
                    }
                }
                .padding(.horizontal, 16)
            }
            .frame(height: rowHeight)
            .onAppear {
                // A day picked on the page may be far along the strip.
                if let day = store.selectedDay { proxy.scrollTo(day, anchor: .center) }
            }
        }
        .accessibilityIdentifier("dayLegend")
    }

    private func dayChip(_ day: MapLine.Day) -> some View {
        let selected = store.selectedDay == day.epochDay
        return Button {
            store.selectDay(selected ? nil : day.epochDay)
        } label: {
            HStack(spacing: 6) {
                Circle()
                    .fill(Color(hex: day.color))
                    .frame(width: 10, height: 10)
                    .overlay {
                        // The day's own colour has to stand off the filled chip.
                        if selected { Circle().strokeBorder(.white, lineWidth: 1.5) }
                    }
                    .accessibilityHidden(true)
                Text(Fmt.date(day.date))
                    .font(.footnote.weight(selected ? .semibold : .regular))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            .foregroundStyle(selected ? Color.white : Color.primary)
            .padding(.horizontal, 10)
            .frame(minHeight: 32)
            .background(
                selected ? Color.accentColor : Color(.tertiarySystemFill),
                in: RoundedRectangle(cornerRadius: Design.controlRadius)
            )
            .frame(minHeight: Design.minTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct MapButton: View {
    let symbol: String
    let label: String
    /// A switch that is on: tinted, and said so to VoiceOver.
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(active ? Color.accentColor : Color.primary)
                .frame(width: Design.minTarget, height: Design.minTarget)
                .mapControlSurface(cornerRadius: Design.controlRadius, interactive: true)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

/// The map's buttons, one above the other. Their glass is rendered together;
/// the container's spacing is under the gap, so they stay two separate buttons.
private struct MapButtonColumn<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 4) { column }
        } else {
            column
        }
    }

    private var column: some View {
        VStack(spacing: 8) { content }
    }
}
