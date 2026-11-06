import SwiftUI

@main
struct TimelineExporterApp: App {
    @State private var store = TimelineStore()

    var body: some Scene {
        WindowGroup {
            MainView()
                .environment(store)
                // "Open in Timeline Exporter" from Files or another app's share sheet.
                .onOpenURL { url in
                    Task { await store.open(url: url) }
                }
        }
    }
}
