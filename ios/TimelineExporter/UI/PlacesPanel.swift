import SwiftUI

/// What a place's row and its own page both say.
enum PlaceText {
    /// How long in all and when last.
    static func stay(_ place: PlaceItem) -> String {
        [duration(place), L.f("place_last_visit", Fmt.date(place.lastVisit))].joined(separator: " · ")
    }

    static func duration(_ place: PlaceItem) -> String {
        let hours = Double(place.totalSeconds) / 3600
        return hours < 48
            ? L.f("place_duration_hours", hours.formatted(.number.precision(.fractionLength(hours < 10 ? 1 : 0))))
            : L.plural("map_day_count", Int((hours / 24).rounded()))
    }

    static func symbol(_ place: PlaceItem) -> String {
        place.isHome ? "house.fill" : place.isWork ? "briefcase.fill" : "mappin.circle.fill"
    }
}

/// The Places mode: the count and a search field over a list that scrolls on
/// its own. Every place is written by an export; a row finds its place on the
/// map beside the list, or opens the place's page on a phone.
struct PlacesPanel: View {
    @Environment(TimelineStore.self) private var store
    /// A map is on screen with the list: a row finds its place there.
    let inPlace: Bool
    /// On a phone, every place on the full-screen map.
    let showMap: (() -> Void)?
    let open: (PlaceItem) -> Void
    @State private var query = ""
    @State private var naming: PlaceItem?
    /// The place picked in the list, not on the map: no need to scroll to it.
    @State private var tapped: String?

    var body: some View {
        @Bindable var store = store
        let places = store.places
        let shown = matches(places)
        VStack(spacing: 0) {
            bar(shown: shown.count, searchable: !places.isEmpty)
            ScrollViewReader { proxy in
                List {
                    Section {
                        Toggle(isOn: $store.includeContainers) {
                            OptionLabel(title: L.s("places_include_containers"), hint: L.s("places_include_containers_hint"))
                        }
                    }
                    if places.isEmpty {
                        Section {
                            Text(store.filter.range == nil ? L.s("places_none") : L.s("places_none_filtered"))
                                .foregroundStyle(.secondary)
                        }
                    } else if shown.isEmpty {
                        Section {
                            ContentUnavailableView.search(text: query)
                        }
                    } else {
                        Section {
                            ForEach(shown) { place in
                                PlaceRow(place: place, inPlace: inPlace, tap: { tap(place) }, rename: { naming = place })
                            }
                        } footer: {
                            Text(L.s("place_unnamed_hint"))
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollDismissesKeyboard(.immediately)
                .modifier(ReadableColumn())
                // A dot tapped on the map: bring its row into view.
                .onChange(of: store.selectedPlace) { _, key in
                    guard inPlace, let key, key != tapped else { return }
                    tapped = nil
                    withAnimation { proxy.scrollTo(key, anchor: .center) }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sheet(item: $naming) { place in
            PlaceNameSheet(place: place)
        }
    }

    /// The first tap finds the place on the map, the second names it; on a
    /// phone the row opens the place's page, which has both.
    private func tap(_ place: PlaceItem) {
        if !inPlace {
            open(place)
        } else if store.selectedPlace == place.key {
            naming = place
        } else {
            tapped = place.key
            store.selectPlace(place.key)
        }
    }

    /// By the name shown, Google's or the user's own, and by the address.
    private func matches(_ places: [PlaceItem]) -> [PlaceItem] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return places }
        return places.filter { place in
            store.displayName(place).localizedCaseInsensitiveContains(text)
                || (place.address?.localizedCaseInsensitiveContains(text) ?? false)
        }
    }

    private func bar(shown: Int, searchable: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Text(L.plural("places_count", shown))
                    .font(.headline)
                    .monospacedDigit()
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("modeTitle")
                Spacer(minLength: 8)
                if let showMap, searchable {
                    Button(action: showMap) {
                        Image(systemName: "map")
                            .frame(minWidth: 32, minHeight: 32)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L.s("map_expand"))
                    .accessibilityIdentifier("placesOnMap")
                }
            }
            if searchable {
                searchField
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
        .frame(maxWidth: ReadableColumn.maxWidth)
        .frame(maxWidth: .infinity)
    }

    /// A field of the panel's own: the system's search bar belongs to the
    /// navigation bar, which on a wide window also spans the map.
    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField(L.s("ios_search_places"), text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .accessibilityIdentifier("placeSearch")
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 28, minHeight: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.s("filter_clear_button"))
            }
        }
        .padding(.horizontal, 10)
        .frame(minHeight: 36)
        .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: Design.controlRadius))
    }
}

struct PlaceRow: View {
    @Environment(TimelineStore.self) private var store
    let place: PlaceItem
    let inPlace: Bool
    let tap: () -> Void
    let rename: () -> Void
    /// One width for every symbol, so the titles start in one column.
    @ScaledMetric(relativeTo: .body) private var iconColumn: CGFloat = 26

    var body: some View {
        let selected = inPlace && store.selectedPlace == place.key
        let name = store.displayName(place)
        // A place with no name is shown by its coordinates: say so in words.
        let unnamed = name == place.coordinates
        HStack(spacing: 4) {
            Button(action: tap) {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Image(systemName: PlaceText.symbol(place))
                        .foregroundStyle(selected ? Color.accentColor : Color(hex: Design.placeColorHex))
                        .frame(width: iconColumn)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(unnamed ? L.s("ios_place_unnamed") : name)
                                .font(.body.weight(selected ? .semibold : .regular))
                                .lineLimit(2)
                            if let badge = PlaceRow.badge(place, name: name) {
                                PlaceBadge(text: badge)
                            }
                        }
                        if let address = place.address, !address.isEmpty {
                            Text(address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        } else if unnamed {
                            Text(place.coordinates).font(.caption).foregroundStyle(.tertiary).monospacedDigit()
                        }
                        Text(PlaceText.stay(place)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    }
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(Fmt.grouped(place.visits))
                            .font(.title3.weight(.semibold))
                            .monospacedDigit()
                        Text(L.s("places_visits_label"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .lineLimit(1)
                    .fixedSize()
                    // On a phone the row leads to the place's own page.
                    if !inPlace {
                        Image(systemName: "chevron.forward")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
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
    static func badge(_ place: PlaceItem, name: String) -> String? {
        let role: String? = place.isHome ? L.s("place_home") : place.isWork ? L.s("place_work") : nil
        return role == name ? nil : role
    }
}

struct PlaceBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.accentColor.opacity(Design.selectedRowOpacity), in: Capsule())
    }
}

struct PlaceNameSheet: View {
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
