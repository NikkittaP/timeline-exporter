import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// The root screen. Before a file is open it explains itself; with one open it
/// is the Workspace: a short header, the Track / Trips / Places switch, that
/// mode's own content and the Export bar. Filters and export are sheets.
struct MainView: View {
    @Environment(TimelineStore.self) private var store
    @State private var importing = false
    @State private var info: InfoSheet?
    @AppStorage(WhatsNew.seenKey) private var whatsNewSeen: String?
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var mapState = PreviewMapState()
    @State private var journey: JourneyScene?
    @State private var titleTaps = TapSequence(count: 7, window: 4)
    /// The trip or place on a page of its own. Only a phone has one: wider
    /// windows show the pick on the map that is already there.
    @State private var detail: DetailRoute?

    var body: some View {
        NavigationStack {
            // Read in the layout pass itself: a size kept in @State from
            // onGeometryChange never reached the landscape width on an iPad
            // launched turned, so the page stayed stacked and the Map image
            // sheet narrow.
            GeometryReader { geometry in
                let pageSize = geometry.size
                let mode = PageLayout.mode(size: pageSize, sizeClass: sizeClass)
                page(pageSize: pageSize, mode: mode)
                    .expandableMap($mapState)
                    .navigationDestination(item: $detail) { route in
                        DetailView(route: route, mapState: $mapState)
                    }
                    // A window grown wide enough has the map beside the list:
                    // the pick stays, its page goes.
                    .onChange(of: mode) { _, now in
                        if now != .compact { detail = nil }
                    }
                    // Back from the page: the list is all of the trips again.
                    .onChange(of: detail) { _, now in
                        if now == nil, mode == .compact {
                            store.selectTrip(nil)
                            store.selectPlace(nil)
                        }
                    }
            }
            .navigationTitle(L.s("app_name"))
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
                if case .success(let url) = result {
                    Task { await store.open(url: url) }
                }
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
            .sheet(item: $journey) { JourneySheet(scene: $0) }
            .onAppear(perform: showWhatsNew)
        }
    }

    @ViewBuilder
    private func page(pageSize: CGSize, mode: PageLayout.Mode) -> some View {
        if store.phase == .loaded, let core = store.timeline {
            Workspace(
                core: core,
                mode: mode,
                pageSize: pageSize,
                mapState: $mapState,
                importing: $importing,
                detail: $detail
            ) { journey = $0 }
        } else {
            Form {
                if showsWelcome {
                    WelcomeSections(importing: $importing) { info = .help }
                } else {
                    Section {
                        LoadingRow(info: loadingInfo)
                    } header: {
                        StepHeader(title: L.s("step_choose_file"))
                    }
                }
            }
            .modifier(ReadableColumn())
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
                // The snail, as a long press on the Android icon.
                .onLongPressGesture { journey = .traveler }
            Text(L.s("app_name"))
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                // The postcard, after seven quick taps on the name.
                .onTapGesture { if titleTaps.tap() { journey = .postcard } }
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

    private var loadingInfo: LoadProgressInfo? {
        if case .loading(let info) = store.phase { return info }
        return nil
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

// MARK: Loading

/// A file being read. Before that the welcome screen has the button, and
/// after a failure it has the reason too.
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
