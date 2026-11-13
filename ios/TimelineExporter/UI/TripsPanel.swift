import SwiftUI

/// What a trip's row, its card on the map and its own page all say.
enum TripText {
    static func reach(_ trip: TripItem) -> String {
        [
            L.plural("map_day_count", trip.dayCount),
            L.f("trip_far_from_home", Fmt.km(trip.maxDistanceMeters)),
        ].joined(separator: " · ")
    }

    static func travelled(_ trip: TripItem) -> String? {
        guard trip.travelledMeters > 0 else { return nil }
        return (trip.travelledEstimated ? "≈" : "") + Fmt.km(trip.travelledMeters) + " " + L.s("trip_travelled_label")
    }

    static func covered(_ trip: TripItem) -> String {
        var parts: [String] = []
        if let travelled = travelled(trip) {
            parts.append(travelled)
        }
        if trip.placeCount > 0 {
            parts.append(L.plural("places_count", trip.placeCount))
        }
        return parts.joined(separator: " · ")
    }

    static func flags(_ trip: TripItem) -> String {
        var parts: [String] = []
        if trip.openStart { parts.append(L.s("trip_open_start")) }
        if trip.openEnd { parts.append(L.s("trip_open_end")) }
        if trip.longAbsence { parts.append(L.s("trip_long_absence")) }
        return parts.joined(separator: " · ")
    }
}

/// The Trips mode: how many there are and what is marked, over a list by year
/// that scrolls on its own. A row's mark is for the export; the row itself
/// shows the trip, on the map beside the list or on a page of its own.
struct TripsPanel: View {
    @Environment(TimelineStore.self) private var store
    /// A map is on screen with the list: a row shows its trip there.
    let inPlace: Bool
    /// On a phone, every trip on the full-screen map.
    let showMap: (() -> Void)?
    let open: (TripItem) -> Void

    var body: some View {
        let trips = store.visibleTrips
        VStack(spacing: 0) {
            if trips.isEmpty {
                PanelNote(text: emptyNote, symbol: "suitcase")
            } else {
                bar(trips)
                List {
                    ForEach(TripYear.group(trips)) { group in
                        Section {
                            ForEach(group.trips) { trip in
                                TripRow(trip: trip, inPlace: inPlace, open: open)
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
                    Section {
                    } footer: {
                        Text(L.s("trips_hint"))
                    }
                }
                .listStyle(.insetGrouped)
                .modifier(ReadableColumn())
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var emptyNote: String {
        if store.tripList.noHome { return L.s("trips_no_home") }
        return store.tripList.trips.isEmpty ? L.s("trips_none") : L.s("trips_none_filtered")
    }

    /// The count, what an export takes, and the marks all at once.
    private func bar(_ trips: [TripItem]) -> some View {
        let marked = store.exportTripKeys.count
        return HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(L.plural("trips_count", trips.count))
                    .font(.headline)
                    .monospacedDigit()
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("modeTitle")
                Text(checkedText(trips, marked: marked))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .accessibilityIdentifier("tripMarks")
            }
            Spacer(minLength: 8)
            if marked > 0 {
                Button(L.s("ios_trips_clear_marks")) { store.clearCheckedTrips() }
                    .accessibilityIdentifier("clearTripMarks")
            }
            if marked < trips.count {
                Button(L.s("ios_select_all")) { store.checkAllTrips() }
                    .accessibilityIdentifier("selectAllTrips")
            }
            if let showMap {
                Button(action: showMap) {
                    Image(systemName: "map")
                        .frame(minWidth: 32, minHeight: 32)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(L.s("trip_show_all_on_map"))
                .accessibilityIdentifier("tripsOnMap")
            }
        }
        .font(.subheadline)
        .buttonStyle(.borderless)
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
        .frame(maxWidth: ReadableColumn.maxWidth)
        .frame(maxWidth: .infinity)
    }

    private func checkedText(_ trips: [TripItem], marked: Int) -> String {
        if marked == 0 {
            // As export does it: nothing ticked writes every visible trip.
            return trips.count == 1
                ? L.s("trips_checked_none")
                : L.f("ios_trips_none_selected", L.plural("trips_count", trips.count))
        }
        return L.f("trips_checked", Fmt.grouped(marked), Fmt.grouped(trips.count))
    }
}

/// Why a mode has no list, in the list's place.
struct PanelNote: View {
    let text: String
    let symbol: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.largeTitle)
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
            Text(text)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct TripRow: View {
    @Environment(TimelineStore.self) private var store
    let trip: TripItem
    let inPlace: Bool
    let open: (TripItem) -> Void

    var body: some View {
        let checked = store.checkedTrips.contains(trip.key)
        let selected = inPlace && store.previewedTrip == trip.key
        let covered = TripText.covered(trip)
        let flags = TripText.flags(trip)
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
                open(trip)
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
                        Text(TripText.reach(trip)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        if !covered.isEmpty {
                            Text(covered).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                        if !flags.isEmpty {
                            Text(flags).font(.caption).foregroundStyle(.orange)
                        }
                        // Said in words, not only by the row's tint: the
                        // trip on the map need not be one marked for export.
                        if selected {
                            Label(L.s("ios_trip_on_map"), systemImage: "map.fill")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tint)
                                .accessibilityHidden(true)
                        }
                    }
                    Spacer(minLength: 0)
                    TransportIcons(modes: trip.transport)
                    // On a phone the row leads to the trip's own page.
                    if !inPlace {
                        Image(systemName: "chevron.forward")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .padding(.top, 3)
                            .accessibilityHidden(true)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(selected ? L.s("ios_trip_on_map") : "")
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
        // Only the previewed row is tinted: a marked one says so with its
        // filled mark alone, so the two never look alike.
        .listRowBackground(selected ? Color.accentColor.opacity(Design.previewedRowOpacity) : nil)
    }
}

struct TransportIcons: View {
    let modes: [String]

    var body: some View {
        HStack(spacing: 2) {
            ForEach(modes.prefix(3), id: \.self) { mode in
                Image(systemName: Self.symbol(mode)).font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityHidden(true)
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
