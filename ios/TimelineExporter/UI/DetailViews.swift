import SwiftUI

/// A trip's or a place's own page, pushed from its list on a phone. Wider
/// windows have a map beside the list and show the pick there instead.
enum DetailRoute: Hashable {
    case trip(Int64)
    case place(String)
}

struct DetailView: View {
    let route: DetailRoute
    @Binding var mapState: PreviewMapState

    var body: some View {
        switch route {
        case .trip(let key): TripDetailView(key: key, mapState: $mapState)
        case .place(let key): PlaceDetailView(key: key, mapState: $mapState)
        }
    }
}

/// The page's map: the same preview map, with its buttons, as a row.
private struct DetailMap: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Binding var mapState: PreviewMapState

    var body: some View {
        PreviewMap(state: $mapState)
            .frame(height: sizeClass == .regular ? Design.mapHeightRegular : Design.mapHeightCompact)
            .listRowInsets(EdgeInsets())
    }
}

/// One trip: its route on the map, its mark for the export, its figures.
private struct TripDetailView: View {
    @Environment(TimelineStore.self) private var store
    let key: Int64
    @Binding var mapState: PreviewMapState

    private var trip: TripItem? {
        store.visibleTrips.first { $0.key == key }
    }

    var body: some View {
        Form {
            Section {
                DetailMap(mapState: $mapState)
            }
            if let trip {
                Section {
                    Toggle(isOn: Binding(
                        get: { store.checkedTrips.contains(key) },
                        set: { store.setTrip(key, checked: $0) }
                    )) {
                        Text(L.s("trip_export_checkbox"))
                    }
                    .accessibilityIdentifier("tripExport")
                }
                Section {
                    figures(trip)
                }
            }
        }
        .navigationTitle(trip.map { Fmt.dayRange($0.startDay, $0.endDay) } ?? L.s("content_trip"))
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func figures(_ trip: TripItem) -> some View {
        let flags = TripText.flags(trip)
        Label(L.plural("map_day_count", trip.dayCount), systemImage: "calendar")
        Label(L.f("trip_far_from_home", Fmt.km(trip.maxDistanceMeters)), systemImage: "house")
        if let travelled = TripText.travelled(trip) {
            Label(travelled, systemImage: "road.lanes")
        }
        if trip.placeCount > 0 {
            Label(L.plural("places_count", trip.placeCount), systemImage: "mappin.and.ellipse")
        }
        if !trip.transport.isEmpty {
            HStack(spacing: 14) {
                ForEach(trip.transport, id: \.self) { mode in
                    Image(systemName: TransportIcons.symbol(mode))
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityHidden(true)
        }
        if !flags.isEmpty {
            Label(flags, systemImage: "exclamationmark.circle")
                .foregroundStyle(.orange)
        }
    }
}

/// One place: where it is on the map, what the file says of it, its name.
private struct PlaceDetailView: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.openURL) private var openURL
    let key: String
    @Binding var mapState: PreviewMapState
    @State private var naming: PlaceItem?

    private var place: PlaceItem? {
        store.places.first { $0.key == key }
    }

    var body: some View {
        let name = place.map { store.displayName($0) }
        let unnamed = name == place?.coordinates
        Form {
            Section {
                DetailMap(mapState: $mapState)
            }
            if let place, let name {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(unnamed ? L.s("ios_place_unnamed") : name)
                                .font(.headline)
                            if let badge = PlaceRow.badge(place, name: name) {
                                PlaceBadge(text: badge)
                            }
                        }
                        if let address = place.address, !address.isEmpty {
                            Text(address).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Text(place.coordinates)
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .monospacedDigit()
                    }
                    .padding(.vertical, 2)
                    .accessibilityElement(children: .combine)
                    Label(
                        Fmt.grouped(place.visits) + " " + L.s("places_visits_label"),
                        systemImage: "arrow.triangle.turn.up.right.circle"
                    )
                    Label(PlaceText.stay(place), systemImage: "clock")
                }
                Section {
                    Button {
                        naming = place
                    } label: {
                        Label(L.s("place_dialog_title"), systemImage: "pencil")
                    }
                    .accessibilityIdentifier("placeRename")
                    if let url = place.mapsURL {
                        Button {
                            openURL(url)
                        } label: {
                            Label(L.s("place_dialog_maps"), systemImage: "map")
                        }
                    }
                } footer: {
                    if unnamed {
                        Text(L.s("place_unnamed_hint"))
                    }
                }
            }
        }
        .navigationTitle(unnamed ? L.s("ios_place_unnamed") : (name ?? L.s("content_places")))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $naming) { place in
            PlaceNameSheet(place: place)
        }
    }
}
