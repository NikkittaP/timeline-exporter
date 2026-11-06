import XCTest
@testable import TimelineExporter

/// Runs inside the app (hosted test), so Bundle.main is the app bundle and the
/// Shared.xcframework linked into the app is the one exercised.
final class SharedLinkTests: XCTestCase {
    func testBundledSampleParsesThroughTheSharedCore() throws {
        let json = try SharedCheck.bundledSample()
        XCTAssertEqual(SharedCheck.summary(ofJSON: json), SampleSummary(pathPoints: 3, totalSegments: 1))
    }
}
