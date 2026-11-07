import SwiftUI
import UIKit

/// The Map image screen: a preview of the picture and its choices, then
/// Save (Files) or Share (which also offers Save Image to Photos).
struct MapImageSheet: View {
    @Environment(TimelineStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @AppStorage("map_image_aspect") private var aspectRaw = MapImageAspect.portrait.rawValue
    @AppStorage("map_image_resolution") private var resolutionRaw = MapImageResolution.standard.rawValue
    @AppStorage("map_image_format") private var formatRaw = MapImageFormat.png.rawValue
    @AppStorage("map_image_show_stats") private var showStats = true
    @AppStorage("map_image_show_endpoints") private var showEndpoints = true

    @State private var content: (line: MapLine, stats: ImageStats?, placeCount: Int?)?
    @State private var preview: UIImage?
    @State private var rendering = false
    @State private var busy: String?
    @State private var message: String?
    @State private var failed = false

    private var aspect: MapImageAspect { MapImageAspect(rawValue: aspectRaw) ?? .portrait }
    private var resolution: MapImageResolution { MapImageResolution(rawValue: resolutionRaw) ?? .standard }
    private var format: MapImageFormat { MapImageFormat(rawValue: formatRaw) ?? .png }
    private var spec: MapImageSpec { MapImageSpec(aspect: aspect, resolution: resolution) }

    /// What the preview depends on; a change renders it again.
    private var previewKey: String { "\(aspectRaw)|\(showStats)|\(showEndpoints)|\(content != nil)" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    previewView
                        .frame(maxWidth: .infinity)
                        .frame(height: 340)
                        .listRowInsets(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
                } footer: {
                    Text(L.f("map_image_size", spec.widthPx, spec.heightPx, format.label))
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
                }
                Section {
                    Button {
                        Task { await deliver(share: false) }
                    } label: {
                        Label(L.s("map_image_save"), systemImage: "square.and.arrow.down")
                    }
                    .accessibilityIdentifier("mapImageSave")
                    Button {
                        Task { await deliver(share: true) }
                    } label: {
                        Label(L.s("map_image_share"), systemImage: "square.and.arrow.up")
                    }
                    .accessibilityIdentifier("mapImageShare")
                } footer: {
                    if let busy {
                        HStack(spacing: 6) {
                            ProgressView()
                            Text(busy)
                        }
                    } else if let message {
                        Text(message).foregroundStyle(failed ? .red : .green)
                    }
                }
                .disabled(busy != nil || content == nil || failedToDraw)
            }
            .navigationTitle(L.s("map_image_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("date_dialog_cancel")) { dismiss() }
                }
            }
            .task {
                content = await store.mapImageContent()
            }
            .task(id: previewKey) {
                await renderPreview()
            }
        }
    }

    private var failedToDraw: Bool { content?.line.isEmpty ?? false }

    @ViewBuilder private var previewView: some View {
        if let content, content.line.isEmpty {
            Text(L.s("map_image_empty")).foregroundStyle(.secondary).multilineTextAlignment(.center)
        } else if let preview {
            Image(uiImage: preview)
                .resizable()
                .scaledToFit()
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(radius: 2)
                .overlay {
                    if rendering { ProgressView() }
                }
                .accessibilityLabel(L.s("map_image_preview_description"))
        } else if rendering || content == nil {
            VStack(spacing: 8) {
                ProgressView()
                Text(L.s("map_image_rendering")).font(.footnote).foregroundStyle(.secondary)
            }
        } else {
            Text(L.s("map_image_error")).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
    }

    private func request(_ spec: MapImageSpec) -> MapImageRequest? {
        guard let content, !content.line.isEmpty else { return nil }
        return MapImageRequest(line: content.line, stats: content.stats, placeCount: content.placeCount,
                               spec: spec, showStats: showStats, showEndpoints: showEndpoints)
    }

    /// The preview is always the Standard image: High is the same picture.
    private func renderPreview() async {
        guard let request = request(MapImageSpec(aspect: aspect)) else { return }
        rendering = true
        defer { rendering = false }
        do {
            let image = try await renderMapImage(request)
            if !Task.isCancelled { preview = image }
        } catch {
            if !Task.isCancelled { preview = nil }
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
