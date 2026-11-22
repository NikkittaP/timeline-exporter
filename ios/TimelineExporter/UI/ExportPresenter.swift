import SwiftUI
import UIKit

extension View {
    /// Shows the save dialog or the share sheet whenever the store has a
    /// file waiting (TimelineStore.pendingExport).
    func exportPresenter() -> some View {
        modifier(ExportPresenter())
    }
}

private struct ExportPresenter: ViewModifier {
    @Environment(TimelineStore.self) private var store

    func body(content: Content) -> some View {
        content.onChange(of: store.pendingExport) { _, pending in
            guard let pending else { return }
            present(pending)
        }
    }

    /// Presented from UIKit rather than a SwiftUI sheet: both controllers
    /// bring their own sheet, and the share sheet needs a popover anchor on iPad.
    private func present(_ pending: TimelineStore.PendingExport) {
        let store = self.store
        guard let host = UIApplication.shared.topViewController else {
            store.finishSave(savedAs: nil)
            return
        }
        if pending.share {
            let vc = UIActivityViewController(activityItems: [pending.url], applicationActivities: nil)
            vc.completionWithItemsHandler = { _, completed, _, _ in
                Task { @MainActor in store.finishShare(completed: completed) }
            }
            if let popover = vc.popoverPresentationController {
                popover.sourceView = host.view
                popover.sourceRect = CGRect(x: host.view.bounds.midX, y: host.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            host.present(vc, animated: true)
        } else {
            let picker = UIDocumentPickerViewController(forExporting: [pending.url], asCopy: true)
            let delegate = SaveDelegate { url in
                let name = url?.lastPathComponent
                let folder = url.flatMap(SavedLocation.folderName)
                Task { @MainActor in store.finishSave(savedAs: name, folder: folder) }
            }
            picker.delegate = delegate
            // The picker holds its delegate weakly.
            objc_setAssociatedObject(picker, &SaveDelegate.key, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
            host.present(picker, animated: true)
        }
    }
}

private final class SaveDelegate: NSObject, UIDocumentPickerDelegate {
    nonisolated(unsafe) static var key: UInt8 = 0
    private let done: (URL?) -> Void

    init(done: @escaping (URL?) -> Void) {
        self.done = done
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        done(urls.first)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        done(nil)
    }
}

extension UIApplication {
    /// The controller on top of the key window, where new modals go.
    var topViewController: UIViewController? {
        let scenes = connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}

/// The folder a saved file went to, as the Files app names it, so the
/// message after saving can say where to look. The save dialog only hands
/// back a path: its last folder is the name shown in Files, except for the
/// containers Files gives names of its own (the top of On My iPhone and of
/// iCloud Drive) and the opaque ids other providers use. Those give nil,
/// and the message shows the file name alone.
enum SavedLocation {
    static func folderName(of url: URL) -> String? {
        let parent = url.deletingLastPathComponent().lastPathComponent
        switch parent {
        case "", "/", "File Provider Storage", "Mobile Documents", "Documents", "tmp":
            return nil
        case "com~apple~CloudDocs":
            return "iCloud Drive"
        default:
            return looksLikeAnId(parent) ? nil : parent
        }
    }

    /// A UUID or a long run of letters and digits with no spaces: a provider's
    /// internal id rather than a folder someone named.
    private static func looksLikeAnId(_ name: String) -> Bool {
        if UUID(uuidString: name) != nil { return true }
        let idChars = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        return name.count >= 24 && name.unicodeScalars.allSatisfy(idChars.contains)
    }
}
