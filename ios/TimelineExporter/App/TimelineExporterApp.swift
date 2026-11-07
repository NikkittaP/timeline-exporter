import SwiftUI

@main
struct TimelineExporterApp: App {
    // A screenshot run remembers nothing between scenes.
    @State private var store = ScreenshotMode.isActive
        ? TimelineStore(settings: InMemorySettingsStore()) : TimelineStore()

    var body: some Scene {
        WindowGroup {
            MainView()
                .environment(store)
                // "Open in Timeline Exporter" from Files or another app's share sheet.
                .onOpenURL { url in
                    Task { await store.open(url: url) }
                }
                .task {
                    if ScreenshotMode.isActive { await ScreenshotMode.prepare(store) }
                }
        }
    }
}
