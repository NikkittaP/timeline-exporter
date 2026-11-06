import SwiftUI

/// Stage 1 placeholder: proves the app runs and reaches the shared core.
struct ContentView: View {
    private let status: String = {
        do {
            let summary = SharedCheck.summary(ofJSON: try SharedCheck.bundledSample())
            return "Shared core: \(summary.pathPoints) points in \(summary.totalSegments) segment(s)"
        } catch {
            return "Sample not found: \(error.localizedDescription)"
        }
    }()

    var body: some View {
        VStack(spacing: 12) {
            Text("Timeline Exporter")
                .font(.title)
            Text(status)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
