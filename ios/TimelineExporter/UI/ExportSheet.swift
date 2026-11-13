import SwiftUI

extension TimelineStore {
    /// What an export writes now, and how much of it: "Trips — 12 trips".
    /// Shown on the Export bar and again in the sheet, so the two agree.
    var exportWritesText: String {
        switch contentKind {
        case .track:
            return L.f("export_writes_value", L.s("content_track"), L.f("points_count", Fmt.grouped(filteredCount)))
        case .trips:
            // Nothing ticked writes every visible trip.
            let marked = exportTripKeys.count
            let count = marked == 0 ? visibleTrips.count : marked
            return L.f("export_writes_value", L.s(count == 1 ? "content_trip" : "content_trips"),
                       L.plural("trips_count", count))
        case .places:
            return L.f("export_writes_value", L.s("content_places"), L.plural("places_count", places.count))
        }
    }

    /// "Export track", "Export trips", "Export trip", "Export places".
    var exportTitle: String {
        switch contentKind {
        case .track: L.s("step_export_track")
        case .trips: exportTripKeys.count == 1 ? L.s("step_export_trip") : L.s("step_export_trips")
        case .places: L.s("step_export_places")
        }
    }
}

/// The export, opened from the Export bar: a data file in one of four formats,
/// saved or sent, or the Map image. Two things, in two sections; the Map image
/// is a feature of its own, not a fifth format.
struct ExportSheet: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @AppStorage("export_format") private var formatRaw = ExportFormatChoice.gpx.rawValue
    @State private var mapImage = false
    /// The size for the Map image sheet on a wide window.
    let wideMapImage: CGSize?

    private var format: ExportFormatChoice { ExportFormatChoice(rawValue: formatRaw) ?? .gpx }

    var body: some View {
        @Bindable var store = store
        NavigationStack {
            Form {
                Section {
                    LabeledContent(L.s("export_writes_label")) {
                        Text(store.exportWritesText).monospacedDigit().multilineTextAlignment(.trailing)
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
                        .actionLabelWidth()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(store.exportState == .working)
                    .accessibilityIdentifier("exportRun")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                } header: {
                    StepHeader(title: L.s("ios_export_data_file"))
                } footer: {
                    status
                }

                if store.contentKind != .places {
                    Section {
                        mapImageRow
                    }
                }
            }
            .navigationTitle(store.exportTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("whats_new_close")) { dismiss() }
                        .accessibilityIdentifier("exportClose")
                }
            }
        }
    }

    private var mapImageRow: some View {
        Button {
            mapImage = true
        } label: {
            HStack(spacing: 14) {
                MapImageThumbnail()
                    .frame(width: 60, height: 60)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L.s("map_image_export_entry"))
                        .font(.headline)
                    Text(L.s("ios_map_image_entry_hint"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!store.canMakeMapImage)
        .opacity(store.canMakeMapImage ? 1 : 0.5)
        .accessibilityIdentifier("mapImage")
        // On the row, not the Section: from there the sheet never showed.
        .sheet(isPresented: $mapImage) {
            MapImageSheet(wide: wideMapImage)
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
