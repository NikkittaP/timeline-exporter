import Foundation
import Shared

/// What the stage 1 check reads from a parse: plain Swift values, so callers
/// (and the tests) never handle Kotlin types.
struct SampleSummary: Equatable {
    let pathPoints: Int
    let totalSegments: Int
}

/// The one place that calls the shared Kotlin core in stage 1. Stage 3 replaces
/// it with the real adapter (async parse with progress, errors as Swift errors).
enum SharedCheck {
    static func summary(ofJSON json: String) -> SampleSummary {
        let parsed = TimelineParserKt.parseTimeline(jsonText: json, onProgress: { _ in })
        return SampleSummary(pathPoints: parsed.pathPoints.count, totalSegments: Int(parsed.totalSegments))
    }

    static func bundledSample(in bundle: Bundle = .main) throws -> String {
        guard let url = bundle.url(forResource: "sample-timeline", withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try String(contentsOf: url, encoding: .utf8)
    }
}
