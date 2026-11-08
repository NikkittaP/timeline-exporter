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

    /// One day alone has nothing to tell apart.
    var showsDayLegend: Bool {
        contentKind == .track && colorByDay && mapLine.days.count > 1
    }
}

// MARK: Preview: the map and the Track / Trips / Places switch

struct PreviewSection: View {
    @Environment(TimelineStore.self) private var store
    /// Regular on an iPad: a taller map in the wider column.
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var fitToken = 0
    @State private var restoreToken = 0
    @State private var status = MapStatus.loading
    @State private var expanded = false

    var body: some View {
        @Bindable var store = store
        Section {
            Picker(selection: Binding(get: { store.contentKind }, set: { store.setContentKind($0) })) {
                ForEach(ContentKind.allCases) { kind in
                    Text(kind.label).tag(kind)
                }
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("contentKind")
            .id("preview")

            // On the row: a Form hands a Section's modifiers to each of its
            // rows, and the cover never showed from there.
            map
                .frame(height: sizeClass == .regular ? Design.mapHeightRegular : Design.mapHeightCompact)
                .listRowInsets(EdgeInsets())
                .fullScreenCover(isPresented: $expanded, onDismiss: { restoreToken += 1 }) {
                    ExpandedMap()
                }

            if store.contentKind == .track {
                Toggle(L.s("map_color_by_day"), isOn: $store.colorByDay)
                    .accessibilityIdentifier("colorByDay")
            }
            if store.showsDayLegend {
                DayLegend()
                    .listRowInsets(EdgeInsets())
            }
        } header: {
            StepHeader(title: L.s("step_preview"))
        }
    }

    private var map: some View {
        StoreMap(fitToken: fitToken, restoreToken: restoreToken, status: $status)
            .overlay(alignment: .topLeading) {
                // Clear of the buttons in the other corner.
                MapStatusChip(status: status)
                    .padding(.leading, 8)
                    .padding(.top, 8)
                    .padding(.trailing, Design.minTarget + 16)
            }
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 8) {
                    MapButton(symbol: "arrow.up.left.and.arrow.down.right", label: L.s("map_expand")) {
                        expanded = true
                    }
                    .accessibilityIdentifier("mapExpand")
                    MapButton(symbol: "scope", label: L.s("map_recenter")) {
                        fitToken += 1
                    }
                }
                .padding(8)
            }
    }
}

/// The map on the store's content, with the reason when there is nothing to draw.
private struct StoreMap: View {
    @Environment(TimelineStore.self) private var store
    let fitToken: Int
    var restoreToken = 0
    @Binding var status: MapStatus

    var body: some View {
        TimelineMapView(
            content: store.mapContent,
            fitToken: fitToken,
            restoreToken: restoreToken,
            memory: store.mapCamera,
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

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                StoreMap(fitToken: fitToken, status: $status)
                    .ignoresSafeArea(edges: store.showsDayLegend ? [.top, .horizontal] : .all)
                HStack(alignment: .top) {
                    MapStatusChip(status: status)
                    Spacer(minLength: 8)
                    VStack(spacing: 8) {
                        MapButton(symbol: "xmark", label: L.s("map_exit_fullscreen")) { dismiss() }
                        MapButton(symbol: "scope", label: L.s("map_recenter")) { fitToken += 1 }
                    }
                }
                .padding()
            }
            if store.showsDayLegend {
                DayLegend()
                    .background(.bar)
            }
        }
    }
}

/// The days of a line coloured by day. Picking one brings it forward on the
/// map and frames it; picking it again goes back to all of them.
private struct DayLegend: View {
    @Environment(TimelineStore.self) private var store

    var body: some View {
        let days = store.mapLine.days
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                Text(L.plural("map_day_count", days.count))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                if store.selectedDay != nil {
                    Button(L.s("map_show_all_days")) { store.selectDay(nil) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .accessibilityIdentifier("mapShowAllDays")
                }
                ForEach(days) { day in
                    dayChip(day)
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: Design.minTarget)
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
                    .accessibilityHidden(true)
                Text(Fmt.date(day.date))
                    .font(.footnote.weight(selected ? .semibold : .regular))
                    .monospacedDigit()
            }
            .padding(.horizontal, 10)
            .frame(minHeight: 32)
            .background(
                selected ? Color.accentColor.opacity(0.18) : Color(.tertiarySystemFill),
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .frame(width: Design.minTarget, height: Design.minTarget)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Design.controlRadius))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

// MARK: Trips

struct TripsSection: View {
    @Environment(TimelineStore.self) private var store

    var body: some View {
        let trips = store.visibleTrips
        Section {
            if store.tripList.noHome {
                Text(L.s("trips_no_home")).foregroundStyle(.secondary)
            } else if store.tripList.trips.isEmpty {
                Text(L.s("trips_none")).foregroundStyle(.secondary)
            } else if trips.isEmpty {
                Text(L.s("trips_none_filtered")).foregroundStyle(.secondary)
            } else {
                LabeledContent {
                    if !store.checkedTrips.isEmpty {
                        Button(L.s("ios_trips_clear_marks")) { store.clearCheckedTrips() }
                            .buttonStyle(.borderless)
                            .accessibilityIdentifier("clearTripMarks")
                    }
                } label: {
                    Text(checkedText(trips)).monospacedDigit()
                }
                if store.selectedTrip != nil {
                    Button {
                        store.selectTrip(nil)
                    } label: {
                        Label(store.checkedTrips.isEmpty ? L.s("trip_show_all_on_map") : L.s("trip_show_checked_on_map"),
                              systemImage: "map")
                    }
                }
            }
        } header: {
            StepHeader(title: L.plural("trips_count", trips.count))
        } footer: {
            if !trips.isEmpty {
                Text(L.s("trips_hint"))
            }
        }

        ForEach(TripYear.group(trips)) { group in
            Section {
                ForEach(group.trips) { trip in
                    TripRow(trip: trip)
                }
            } header: {
                HStack {
                    Text(group.year.formatted(.number.grouping(.never)))
                    Spacer()
                    Text(L.plural("trips_count", group.trips.count))
                }
                .textCase(nil)
                .monospacedDigit()
            }
        }
    }

    private func checkedText(_ trips: [TripItem]) -> String {
        let checked = store.exportTripKeys.count
        if checked == 0 { return L.s("trips_checked_none") }
        return L.f("trips_checked", Fmt.grouped(checked), Fmt.grouped(trips.count))
    }
}

private struct TripRow: View {
    @Environment(TimelineStore.self) private var store
    let trip: TripItem

    var body: some View {
        let checked = store.checkedTrips.contains(trip.key)
        let selected = store.selectedTrip == trip.key
        HStack(spacing: 4) {
            // The mark is for export; the rest of the row is for the map.
            Button {
                store.setTrip(trip.key, checked: !checked)
            } label: {
                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(checked ? Color.accentColor : .secondary)
                    .frame(width: Design.minTarget, height: Design.minTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L.s("trip_export_checkbox"))
            .accessibilityAddTraits(checked ? .isSelected : [])

            Button {
                store.selectTrip(selected ? nil : trip.key)
            } label: {
                HStack(alignment: .top, spacing: 10) {
                    Circle()
                        .fill(Color(hex: trip.color))
                        .frame(width: 10, height: 10)
                        .padding(.top, 6)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(Fmt.dayRange(trip.startDay, trip.endDay))
                            .font(.body.weight(selected ? .semibold : .regular))
                            .monospacedDigit()
                        Text(reach).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        if !covered.isEmpty {
                            Text(covered).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                        if !flags.isEmpty {
                            Text(flags).font(.caption).foregroundStyle(.orange)
                        }
                    }
                    Spacer(minLength: 0)
                    VStack(alignment: .trailing, spacing: 6) {
                        TransportIcons(modes: trip.transport)
                        if selected {
                            Image(systemName: "map.fill")
                                .font(.caption)
                                .foregroundStyle(.tint)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(selected ? L.s("ios_trip_on_map") : "")
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
        .listRowBackground(selected ? Color.accentColor.opacity(Design.selectedRowOpacity) : nil)
    }

    private var reach: String {
        [
            L.plural("map_day_count", trip.dayCount),
            L.f("trip_far_from_home", Fmt.km(trip.maxDistanceMeters)),
        ].joined(separator: " · ")
    }

    private var covered: String {
        var parts: [String] = []
        if trip.travelledMeters > 0 {
            parts.append((trip.travelledEstimated ? "≈" : "") + Fmt.km(trip.travelledMeters) + " " + L.s("trip_travelled_label"))
        }
        if trip.placeCount > 0 {
            parts.append(L.plural("places_count", trip.placeCount))
        }
        return parts.joined(separator: " · ")
    }

    private var flags: String {
        var parts: [String] = []
        if trip.openStart { parts.append(L.s("trip_open_start")) }
        if trip.openEnd { parts.append(L.s("trip_open_end")) }
        if trip.longAbsence { parts.append(L.s("trip_long_absence")) }
        return parts.joined(separator: " · ")
    }
}

private struct TransportIcons: View {
    let modes: [String]

    var body: some View {
        HStack(spacing: 2) {
            ForEach(modes.prefix(3), id: \.self) { mode in
                Image(systemName: Self.symbol(mode)).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    static func symbol(_ mode: String) -> String {
        switch mode {
        case "FLIGHT": "airplane"
        case "CAR": "car.fill"
        case "TRAIN": "tram.fill"
        case "BUS": "bus.fill"
        case "BOAT": "ferry.fill"
        case "BICYCLE": "bicycle"
        default: "figure.walk"
        }
    }
}

// MARK: Places

struct PlacesSection: View {
    @Environment(TimelineStore.self) private var store
    @State private var showAll = false
    @State private var naming: PlaceItem?

    private static let shortList = 50

    var body: some View {
        @Bindable var store = store
        let places = store.places
        Section {
            Toggle(isOn: $store.includeContainers) {
                OptionLabel(title: L.s("places_include_containers"), hint: L.s("places_include_containers_hint"))
            }
            if places.isEmpty {
                Text(store.filter.range == nil ? L.s("places_none") : L.s("places_none_filtered"))
                    .foregroundStyle(.secondary)
            } else {
                ForEach(showAll ? places : Array(places.prefix(Self.shortList))) { place in
                    PlaceRow(place: place) { naming = place }
                }
                if !showAll && places.count > Self.shortList {
                    Button(L.f("places_show_all", Fmt.grouped(places.count))) { showAll = true }
                }
            }
        } header: {
            StepHeader(title: L.plural("places_count", places.count))
        } footer: {
            if !places.isEmpty {
                Text(L.s("place_unnamed_hint"))
            }
        }
        .sheet(item: $naming) { place in
            PlaceNameSheet(place: place)
        }
    }
}

private struct PlaceRow: View {
    @Environment(TimelineStore.self) private var store
    let place: PlaceItem
    let rename: () -> Void

    var body: some View {
        let selected = store.selectedPlace == place.key
        let name = store.displayName(place)
        // A place with no name is shown by its coordinates: say so in words.
        let unnamed = name == place.coordinates
        HStack(spacing: 4) {
            Button {
                // The first tap finds the place on the map, the second names it.
                if selected { rename() } else { store.selectPlace(place.key) }
            } label: {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: place.isHome ? "house.fill" : place.isWork ? "briefcase.fill" : "mappin.circle.fill")
                        .foregroundStyle(selected ? Color.accentColor : Color(hex: Design.placeColorHex))
                        .padding(.top, 2)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(unnamed ? L.s("ios_place_unnamed") : name)
                                .font(.body.weight(selected ? .semibold : .regular))
                                .lineLimit(2)
                            if let badge = badge(for: name) {
                                Text(badge)
                                    .font(.caption2.weight(.semibold))
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.accentColor.opacity(Design.selectedRowOpacity), in: Capsule())
                            }
                        }
                        if let address = place.address, !address.isEmpty {
                            Text(address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        } else if unnamed {
                            Text(place.coordinates).font(.caption).foregroundStyle(.tertiary).monospacedDigit()
                        }
                        Text(visitsText).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        Text(L.f("place_last_visit", Fmt.date(place.lastVisit)))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])

            if selected {
                Button(action: rename) {
                    Image(systemName: "pencil")
                        .frame(width: Design.minTarget, height: Design.minTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L.s("place_dialog_title"))
            }
        }
        .listRowBackground(selected ? Color.accentColor.opacity(Design.selectedRowOpacity) : nil)
    }

    /// Home or Work beside a place that has a name of its own.
    private func badge(for name: String) -> String? {
        let role: String? = place.isHome ? L.s("place_home") : place.isWork ? L.s("place_work") : nil
        return role == name ? nil : role
    }

    private var visitsText: String {
        let hours = Double(place.totalSeconds) / 3600
        let duration = hours < 48
            ? L.f("place_duration_hours", hours.formatted(.number.precision(.fractionLength(hours < 10 ? 1 : 0))))
            : L.f("place_duration_days", (hours / 24).formatted(.number.precision(.fractionLength(0))))
        return [
            Fmt.grouped(place.visits) + " " + L.s("places_visits_label"),
            duration,
        ].joined(separator: " · ")
    }
}

private struct PlaceNameSheet: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let place: PlaceItem
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L.s("place_dialog_field"), text: $text)
                        .textInputAutocapitalization(.sentences)
                        .submitLabel(.done)
                        .onSubmit(save)
                } footer: {
                    Text([place.name, place.address, place.coordinates].compactMap { $0 }.joined(separator: "\n"))
                }
                if let url = place.mapsURL {
                    Section {
                        Button {
                            openURL(url)
                        } label: {
                            Label(L.s("place_dialog_maps"), systemImage: "map")
                        }
                    }
                }
                if store.labels[place.key] != nil {
                    Section {
                        Button(L.s("place_dialog_remove"), role: .destructive) {
                            store.rename(place.key, to: nil)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(L.s("place_dialog_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("date_dialog_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("place_dialog_save"), action: save)
                }
            }
            .onAppear { text = store.labels[place.key] ?? place.name ?? "" }
        }
        .presentationDetents([.medium])
    }

    private func save() {
        store.rename(place.key, to: text)
        dismiss()
    }
}
