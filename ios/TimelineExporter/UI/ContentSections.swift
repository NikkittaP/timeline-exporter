import SwiftUI

// MARK: Preview: the map and the Track / Trips / Places switch

struct PreviewSection: View {
    @Environment(TimelineStore.self) private var store
    @State private var fitToken = 0
    @State private var expanded = false

    var body: some View {
        Section(L.s("step_preview")) {
            Picker(selection: Binding(get: { store.contentKind }, set: { store.setContentKind($0) })) {
                ForEach(ContentKind.allCases) { kind in
                    Text(kind.label).tag(kind)
                }
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("contentKind")

            map
                .frame(height: 300)
                .listRowInsets(EdgeInsets())
        }
        .fullScreenCover(isPresented: $expanded) {
            ExpandedMap(content: content)
        }
    }

    private var content: MapContent {
        switch store.contentKind {
        case .track, .trips:
            return MapContent(line: store.mapLine)
        case .places:
            return MapContent(places: store.places, selectedPlace: store.selectedPlace)
        }
    }

    private var map: some View {
        TimelineMapView(content: content, fitToken: fitToken) { key in
            store.selectPlace(key)
        }
        .overlay(alignment: .topTrailing) {
            VStack(spacing: 8) {
                MapButton(symbol: "arrow.up.left.and.arrow.down.right", label: L.s("map_expand")) {
                    expanded = true
                }
                MapButton(symbol: "scope", label: L.s("map_recenter")) {
                    fitToken += 1
                }
            }
            .padding(8)
        }
    }
}

private struct ExpandedMap: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let content: MapContent
    @State private var fitToken = 0

    var body: some View {
        TimelineMapView(content: liveContent, fitToken: fitToken) { store.selectPlace($0) }
            .ignoresSafeArea()
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 8) {
                    MapButton(symbol: "xmark", label: L.s("map_exit_fullscreen")) { dismiss() }
                    MapButton(symbol: "scope", label: L.s("map_recenter")) { fitToken += 1 }
                }
                .padding()
            }
    }

    /// The selection can change while the map is expanded.
    private var liveContent: MapContent {
        var c = content
        if store.contentKind == .places { c.selectedPlace = store.selectedPlace }
        return c
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
                .frame(width: 40, height: 40)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
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
                if store.selectedTrip != nil {
                    Button(store.checkedTrips.isEmpty ? L.s("trip_show_all_on_map") : L.s("trip_show_checked_on_map")) {
                        store.selectTrip(nil)
                    }
                }
                ForEach(trips) { trip in
                    TripRow(trip: trip)
                }
            }
        } header: {
            Text(L.plural("trips_count", trips.count))
        } footer: {
            if !trips.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(checkedText(trips))
                    Text(L.s("trips_hint"))
                }
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
        HStack(spacing: 12) {
            Button {
                store.setTrip(trip.key, checked: !checked)
            } label: {
                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(checked ? Color.accentColor : .secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L.s("trip_export_checkbox"))
            .accessibilityValue(checked ? "1" : "0")

            Button {
                store.selectTrip(selected ? nil : trip.key)
            } label: {
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(Color(hex: trip.color)).frame(width: 10, height: 10).padding(.top, 6)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(Fmt.dayRange(trip.startDay, trip.endDay))
                            .font(.body.weight(selected ? .semibold : .regular))
                        Text(details).font(.caption).foregroundStyle(.secondary)
                        if !flags.isEmpty {
                            Text(flags).font(.caption).foregroundStyle(.orange)
                        }
                    }
                    Spacer(minLength: 0)
                    TransportIcons(modes: trip.transport)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .listRowBackground(selected ? Color.accentColor.opacity(0.12) : nil)
    }

    private var details: String {
        var parts = [
            L.plural("map_day_count", trip.dayCount),
            L.f("trip_far_from_home", Fmt.km(trip.maxDistanceMeters)),
        ]
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
                VStack(alignment: .leading) {
                    Text(L.s("places_include_containers"))
                    Text(L.s("places_include_containers_hint")).font(.caption).foregroundStyle(.secondary)
                }
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
            Text(L.plural("places_count", places.count))
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
        Button {
            // The first tap finds the place on the map, the second names it.
            if selected { rename() } else { store.selectPlace(place.key) }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: place.isHome ? "house.fill" : place.isWork ? "briefcase.fill" : "mappin.circle.fill")
                    .foregroundStyle(selected ? Color.accentColor : .secondary)
                    .padding(.top, 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text(store.displayName(place))
                        .font(.body.weight(selected ? .semibold : .regular))
                        .lineLimit(2)
                    if let address = place.address, !address.isEmpty {
                        Text(address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Text(details).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(selected ? Color.accentColor.opacity(0.12) : nil)
    }

    private var details: String {
        let hours = Double(place.totalSeconds) / 3600
        let duration = hours < 48
            ? L.f("place_duration_hours", hours.formatted(.number.precision(.fractionLength(hours < 10 ? 1 : 0))))
            : L.f("place_duration_days", (hours / 24).formatted(.number.precision(.fractionLength(0))))
        return [
            Fmt.grouped(place.visits) + " " + L.s("places_visits_label"),
            duration,
            L.f("place_last_visit", Fmt.date(place.lastVisit)),
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
