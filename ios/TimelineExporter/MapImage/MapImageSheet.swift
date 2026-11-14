import SwiftUI
import UIKit

/// The Map image screen: a preview of the picture and its choices, then
/// Save (Files) or Share (which also offers Save Image to Photos).
struct MapImageSheet: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.colorScheme) private var colorScheme
    /// The size to ask for on a wide window; nil keeps the system's sheet.
    /// Decided by the page: a sheet's own size class says little about the iPad.
    private let wide: CGSize?

    init(wide: CGSize?) {
        self.wide = wide
        // Laid out for the size it is about to get, not as a column first.
        if #available(iOS 18, *), let wide {
            _sheetSize = State(initialValue: wide)
        }
    }

    @AppStorage("map_image_aspect") private var aspectRaw = MapImageAspect.portrait.rawValue
    @AppStorage("map_image_resolution") private var resolutionRaw = MapImageResolution.standard.rawValue
    @AppStorage("map_image_format") private var formatRaw = MapImageFormat.png.rawValue
    @AppStorage("map_image_show_stats") private var showStats = true
    @AppStorage("map_image_show_endpoints") private var showEndpoints = true
    @AppStorage("map_image_color_by_day") private var colorByDay = true
    /// "1" or "0" once chosen; until then the picture follows the appearance:
    /// someone reading in the dark most likely wants a dark map.
    @AppStorage("map_image_dark_map") private var darkMapRaw = ""

    private var darkMap: Bool { darkMapRaw.isEmpty ? colorScheme == .dark : darkMapRaw == "1" }

    private var darkMapBinding: Binding<Bool> {
        Binding(get: { darkMap }, set: { darkMapRaw = $0 ? "1" : "0" })
    }

    @State private var content: (line: MapLine, stats: ImageStats?, placeCount: Int?)?
    /// Bumped each time [content] arrives, so the preview follows it.
    @State private var contentVersion = 0
    @State private var preview: UIImage?
    @State private var rendering = false
    @State private var renderFailed = false
    /// The newest render: an older one finishing late must not touch the screen.
    @State private var renderID = 0
    @State private var busy: String?
    @State private var message: String?
    @State private var failed = false

    private var aspect: MapImageAspect { MapImageAspect(rawValue: aspectRaw) ?? .portrait }
    private var resolution: MapImageResolution { MapImageResolution(rawValue: resolutionRaw) ?? .standard }
    private var format: MapImageFormat { MapImageFormat(rawValue: formatRaw) ?? .png }
    private var spec: MapImageSpec { MapImageSpec(aspect: aspect, resolution: resolution) }

    /// Only the track has days to colour; trips are coloured per trip.
    private var showsColorByDay: Bool { store.contentKind == .track }

    /// What the line depends on; a change fetches it again.
    private var contentKey: String { showsColorByDay ? "\(colorByDay)" : "line" }

    /// What the preview depends on; a change renders it again.
    private var previewKey: String { "\(aspectRaw)|\(showStats)|\(showEndpoints)|\(darkMap)|\(contentVersion)" }

    private var ratio: CGFloat { CGFloat(spec.widthPx) / CGFloat(spec.heightPx) }

    /// The whole sheet, as laid out: from outside the action bar, so a status
    /// line appearing in the bar does not resize the picture.
    @State private var sheetSize = CGSize(width: 390, height: 700)

    private var previewSize: CGSize {
        MapImagePreviewFrame.size(ratio: ratio, sheet: sheetSize, heightShare: sizeClass == .regular ? 0.7 : 0.48)
    }

    /// From the sheet's real width: where the system keeps a narrow sheet
    /// (portrait, iOS 17), this stays the one familiar column.
    private var twoColumns: Bool { sheetSize.width >= MapImagePreviewFrame.twoColumnMinWidth }

    private var previewBox: some View {
        Color(.tertiarySystemFill)
            .overlay { previewView }
            .clipShape(RoundedRectangle(cornerRadius: Design.controlRadius))
    }

    private var sizeCaption: some View {
        Text(L.f("map_image_size", spec.widthPx, spec.heightPx, format.label))
            .font(.footnote)
            .foregroundStyle(.secondary)
            .monospacedDigit()
    }

    /// The left column of a wide sheet: all the room there is, at the
    /// picture's own shape, whichever aspect ratio is picked.
    /// The picture and its size caption stay together, centred as one: a
    /// 16:9 picture no longer floats mid-pane with its caption at the bottom.
    private var previewPane: some View {
        GeometryReader { geo in
            let room = MapImagePreviewFrame.paneFit(ratio: ratio, pane: geo.size)
            VStack(spacing: MapImagePreviewFrame.captionSpacing) {
                previewBox
                    .frame(width: room.width, height: room.height)
                sizeCaption
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(20)
        .background(Color(.systemGroupedBackground))
    }

    var body: some View {
        NavigationStack {
            HStack(spacing: 0) {
                if twoColumns {
                    previewPane
                    Divider().ignoresSafeArea(.container, edges: .bottom)
                }
                Form {
                    if !twoColumns {
                        Section {
                            VStack(spacing: 6) {
                                previewBox
                                    .frame(width: previewSize.width, height: previewSize.height)
                                sizeCaption
                            }
                            .frame(maxWidth: .infinity)
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                        }
                    }
                    Section {
                        Picker(L.s("map_image_aspect"), selection: $aspectRaw) {
                            ForEach(MapImageAspect.allCases) { Text(L.s($0.labelKey)).tag($0.rawValue) }
                        }
                        Picker(L.s("map_image_resolution"), selection: $resolutionRaw) {
                            Text(L.s("map_image_res_standard")).tag(MapImageResolution.standard.rawValue)
                            Text(L.s("map_image_res_high")).tag(MapImageResolution.high.rawValue)
                        }
                        Picker(L.s("map_image_format"), selection: $formatRaw) {
                            ForEach(MapImageFormat.allCases) { Text($0.label).tag($0.rawValue) }
                        }
                        Toggle(L.s("map_image_show_stats"), isOn: $showStats)
                        Toggle(L.s("map_image_show_endpoints"), isOn: $showEndpoints)
                        if showsColorByDay {
                            Toggle(L.s("map_color_by_day"), isOn: $colorByDay)
                                .accessibilityIdentifier("mapImageColorByDay")
                        }
                        Toggle(L.s("map_image_dark_map"), isOn: darkMapBinding)
                            .accessibilityIdentifier("mapImageDarkMap")
                    } footer: {
                        if showStats {
                            Text(L.s("ios_map_image_distance_note"))
                        }
                    }
                }
                .frame(width: twoColumns ? MapImagePreviewFrame.controlsWidth : nil)
                .listSectionSpacing(.compact)
                .contentMargins(.top, 8, for: .scrollContent)
                // Under the settings: never on top of them, and in both layouts.
                .safeAreaInset(edge: .bottom) { actionBar }
            }
            .navigationTitle(L.s("map_image_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("date_dialog_cancel")) { dismiss() }
                }
            }
            .task(id: contentKey) {
                let loaded = await store.mapImageContent(colorByDay: showsColorByDay ? colorByDay : nil)
                guard !Task.isCancelled else { return }
                content = loaded
                contentVersion += 1
            }
            .task(id: previewKey) {
                await renderPreview()
            }
        }
        .onGeometryChange(for: CGSize.self, of: { $0.size }) { sheetSize = $0 }
        .modifier(MapImageSheetSizing(wide: wide))
    }

    private var failedToDraw: Bool { content?.line.isEmpty ?? false }

    /// Save first and largest; always on screen, whatever the Form's scroll.
    private var actionBar: some View {
        VStack(spacing: 8) {
            if let busy {
                HStack(spacing: 6) {
                    ProgressView()
                    Text(busy)
                }
                .font(.footnote)
            } else if let message {
                Text(message).font(.footnote).foregroundStyle(failed ? .red : .green)
            }
            // Side by side where the words fit, Save on top where they do not.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    shareButton
                    saveButton
                }
                VStack(spacing: 8) {
                    saveButton
                    shareButton
                }
            }
            .disabled(busy != nil || content == nil || failedToDraw)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private var shareButton: some View {
        Button {
            Task { await deliver(share: true) }
        } label: {
            Label(L.s("map_image_share"), systemImage: "square.and.arrow.up")
                .lineLimit(1)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .accessibilityIdentifier("mapImageShare")
    }

    private var saveButton: some View {
        Button {
            Task { await deliver(share: false) }
        } label: {
            Label(L.s("map_image_save"), systemImage: "square.and.arrow.down")
                .lineLimit(1)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .accessibilityIdentifier("mapImageSave")
    }

    /// The last picture only while it is the shape on screen: stretched into
    /// another aspect ratio it would show a picture that will not be saved.
    private func usable(_ image: UIImage) -> Bool {
        guard image.size.height > 0 else { return false }
        return abs(image.size.width / image.size.height - ratio) < 0.02
    }

    @ViewBuilder private var previewView: some View {
        if let content, content.line.isEmpty {
            Text(L.s("map_image_empty")).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
        } else if let preview, usable(preview) {
            Image(uiImage: preview)
                .resizable()
                .scaledToFit()
                .opacity(rendering ? 0.55 : 1)
                .overlay {
                    if rendering { ProgressView() }
                }
                .accessibilityLabel(L.s("map_image_preview_description"))
                .accessibilityIdentifier(rendering ? "mapImagePreviewRendering" : "mapImagePreview")
        } else if renderFailed {
            Text(L.s("map_image_error")).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
        } else {
            VStack(spacing: 8) {
                ProgressView()
                Text(L.s("map_image_rendering")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func request(_ spec: MapImageSpec) -> MapImageRequest? {
        guard let content, !content.line.isEmpty else { return nil }
        return MapImageRequest(line: content.line, stats: content.stats, placeCount: content.placeCount,
                               spec: spec, showStats: showStats, showEndpoints: showEndpoints, darkMap: darkMap)
    }

    /// The preview is always the Standard image: High is the same picture.
    /// Waits a moment first, so flicking through the options renders once.
    private func renderPreview() async {
        guard content != nil else { return }
        renderID &+= 1
        let id = renderID
        rendering = true
        renderFailed = false
        defer { if id == renderID { rendering = false } }
        try? await Task.sleep(for: .milliseconds(180))
        guard !Task.isCancelled, let request = request(MapImageSpec(aspect: aspect)) else { return }
        do {
            let image = try await renderMapImage(request)
            guard !Task.isCancelled, id == renderID else { return }
            preview = image
        } catch {
            guard !Task.isCancelled, id == renderID else { return }
            preview = nil
            renderFailed = true
        }
    }

    private func deliver(share: Bool) async {
        guard let request = request(spec) else { return }
        message = nil
        failed = false
        busy = resolution == .high ? L.s("map_image_rendering_high") : L.s("map_image_rendering")
        let image: UIImage
        do {
            image = try await renderMapImage(request)
        } catch {
            busy = nil
            fail(resolution == .high ? "map_image_high_error" : "map_image_error")
            return
        }
        busy = L.s("map_image_saving")
        let name = MapImageText.filename(firstDay: content?.stats?.firstDay, lastDay: content?.stats?.lastDay,
                                         format: format)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        let format = self.format
        let written = await Task.detached(priority: .userInitiated) { () -> Bool in
            guard let data = encodeMapImage(image, format: format) else { return false }
            return (try? data.write(to: url, options: .atomic)) != nil
        }.value
        busy = nil
        guard written else {
            fail(share ? "map_image_share_error" : "map_image_save_error")
            return
        }
        FileActions.present(url: url, share: share) { done in
            if done && !share {
                message = L.s("map_image_saved")
            }
        }
    }

    private func fail(_ key: String) {
        failed = true
        message = L.s(key)
    }
}

/// How big the picture is shown: as wide as the Form's column, and no taller
/// than its share of the sheet, so the settings stay within reach on any phone.
enum MapImagePreviewFrame {
    /// The Form's margins on both sides together.
    static let sideMargins: CGFloat = 40
    /// A sheet at least this wide puts the picture beside the settings.
    static let twoColumnMinWidth: CGFloat = 760
    static let controlsWidth: CGFloat = 360

    static let captionSpacing: CGFloat = 10
    /// The footnote caption's line, with room for a larger text size.
    static let captionHeight: CGFloat = 28

    /// The picture's size in the wide sheet's preview pane: as large as the
    /// pane holds at the picture's own shape, with the caption below it.
    static func paneFit(ratio: CGFloat, pane: CGSize) -> CGSize {
        let height = max(0, pane.height - captionHeight - captionSpacing)
        let width = max(0, min(pane.width, height * ratio))
        return CGSize(width: width, height: ratio > 0 ? width / ratio : 0)
    }

    static func size(ratio: CGFloat, sheet: CGSize, heightShare: CGFloat) -> CGSize {
        let width = min(max(0, sheet.width - sideMargins), sheet.height * heightShare * ratio)
        return CGSize(width: width, height: width / ratio)
    }

    /// The sheet to ask for on a window wider than tall with room to spare;
    /// nil keeps the system's own, as upright and in narrow windows.
    static func wideSheet(window: CGSize, sizeClass: UserInterfaceSizeClass?) -> CGSize? {
        guard sizeClass == .regular, window.width >= 960, window.width > window.height else { return nil }
        return CGSize(width: min(1080, window.width - 96), height: min(860, window.height - 48))
    }
}

/// Asks for [wide] as the sheet's size. Always the same modifier, so a turn of
/// the iPad resizes the sheet instead of rebuilding what is inside it. iOS 17
/// has no way to ask: there the sheet stays the system's, in one column.
private struct MapImageSheetSizing: ViewModifier {
    let wide: CGSize?

    func body(content: Content) -> some View {
        if #available(iOS 18, *) {
            content.presentationSizing(MapImagePresentationSizing(wide: wide))
        } else {
            content
        }
    }
}

@available(iOS 18, *)
private struct MapImagePresentationSizing: PresentationSizing {
    let wide: CGSize?

    func proposedSize(for root: PresentationSizingRoot, context: PresentationSizingContext) -> ProposedViewSize {
        if let wide { return ProposedViewSize(wide) }
        return AutomaticPresentationSizing.automatic.proposedSize(for: root, context: context)
    }
}

/// The share sheet or the Files save dialog for one file, from UIKit, on top
/// of whatever is showing.
enum FileActions {
    static func present(url: URL, share: Bool, done: @escaping (Bool) -> Void) {
        guard let host = UIApplication.shared.topViewController else {
            done(false)
            return
        }
        if share {
            let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            vc.completionWithItemsHandler = { _, completed, _, _ in done(completed) }
            if let popover = vc.popoverPresentationController {
                popover.sourceView = host.view
                popover.sourceRect = CGRect(x: host.view.bounds.midX, y: host.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            host.present(vc, animated: true)
        } else {
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            let delegate = PickerDelegate(done: done)
            picker.delegate = delegate
            objc_setAssociatedObject(picker, &PickerDelegate.key, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
            host.present(picker, animated: true)
        }
    }

    private final class PickerDelegate: NSObject, UIDocumentPickerDelegate {
        nonisolated(unsafe) static var key: UInt8 = 0
        private let done: (Bool) -> Void

        init(done: @escaping (Bool) -> Void) {
            self.done = done
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            done(!urls.isEmpty)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            done(false)
        }
    }
}
