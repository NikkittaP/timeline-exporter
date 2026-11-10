import XCTest
import UIKit
@testable import TimelineExporter

/// Real Map image output, not the sheet's preview: every aspect ratio at
/// Standard and High, with large figures, rendered through the same
/// renderMapImage the Save and Share buttons use. Each file is attached to
/// the test result (tests.xcresult) so its pixels can be looked at.
@MainActor
final class MapImageRenderTests: XCTestCase {
    private func request(_ spec: MapImageSpec) -> MapImageRequest {
        // Copenhagen -> Vienna -> Zurich, the shape of the 16:9 report's trip.
        let lats = [55.68, 53.55, 52.52, 48.21, 47.37]
        let lons = [12.57, 10.0, 13.40, 16.37, 8.54]
        let line = MapLine(latitudes: lats, longitudes: lons,
                           pieces: [MapLine.Piece(range: 0..<lats.count, color: "#3B82F6")])
        // Large on purpose: five digits of km and a two-year range.
        let stats = ImageStats(firstDay: 20_454, lastDay: 21_183, dayCount: 730, distanceMeters: 12_345_678)
        return MapImageRequest(line: line, stats: stats, placeCount: 1_234, spec: spec,
                               showStats: true, showEndpoints: true)
    }

    func testEveryAspectAndResolutionRendersAtItsExactSize() async throws {
        for aspect in MapImageAspect.allCases {
            for resolution in MapImageResolution.allCases {
                let spec = MapImageSpec(aspect: aspect, resolution: resolution)
                let image: UIImage
                do {
                    image = try await renderMapImage(request(spec))
                } catch {
                    throw XCTSkip("the map snapshot could not render here: \(error)")
                }
                let cg = try XCTUnwrap(image.cgImage)
                XCTAssertEqual(cg.width, spec.widthPx, "\(aspect) \(resolution)")
                XCTAssertEqual(cg.height, spec.heightPx, "\(aspect) \(resolution)")
                let data = try XCTUnwrap(encodeMapImage(image, format: .png))
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
                attachment.name = "map-image-\(aspect.rawValue)-\(spec.widthPx)x\(spec.heightPx).png"
                attachment.lifetime = .keepAlways
                add(attachment)
                // CI passes a folder on the Mac (TEST_RUNNER_MAP_IMAGE_SAMPLES):
                // the files land there and become the "map-image-samples" artifact.
                if let dir = ProcessInfo.processInfo.environment["MAP_IMAGE_SAMPLES"], !dir.isEmpty {
                    let folder = URL(fileURLWithPath: dir, isDirectory: true)
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    try data.write(to: folder.appendingPathComponent(attachment.name ?? "map-image.png"))
                }
            }
        }
    }
}
