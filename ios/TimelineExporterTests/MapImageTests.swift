import XCTest
@testable import TimelineExporter

/// The Map image's layout math, the same checks as the Android MapImageLayoutTest.
final class MapImageLayoutTests: XCTestCase {
    private let spec = MapImageSpec(aspect: .portrait)

    private func camera(_ route: [(Double, Double)], showStats: Bool = true, spec: MapImageSpec? = nil) -> MapImageCamera {
        let s = spec ?? self.spec
        let extent = MapImageGeometry.trackExtent(latitudes: route.map(\.0), longitudes: route.map(\.1))!
        return MapImageGeometry.fitCamera(extent, spec: s, showStats: showStats)
    }

    private func assertInsideUsableArea(_ route: [(Double, Double)], showStats: Bool, spec: MapImageSpec? = nil,
                                        line: UInt = #line) {
        let s = spec ?? self.spec
        let cam = camera(route, showStats: showStats, spec: s)
        let bottomLimit = Double(s.heightPx - s.reservedBottomPx(showStats: showStats) - s.bottomGapPx)
        for (lat, lon) in route {
            let p = MapImageGeometry.project(cam, spec: s, latitude: lat, longitude: lon)
            XCTAssert(p.x >= Double(s.sidePaddingPx) - 1 && p.x <= Double(s.widthPx - s.sidePaddingPx) + 1,
                      "x=\(p.x) for \(lat),\(lon)", line: line)
            XCTAssert(p.y >= Double(s.topPaddingPx) - 1 && p.y <= bottomLimit + 1, "y=\(p.y) for \(lat),\(lon)", line: line)
        }
    }

    func testThePortraitIsExactlyItsSizeAndTheLogicalSizeMultipliesBack() {
        XCTAssertEqual(spec.widthPx, 1080)
        XCTAssertEqual(spec.heightPx, 1920)
        for aspect in MapImageAspect.allCases {
            for resolution in MapImageResolution.allCases {
                let s = MapImageSpec(aspect: aspect, resolution: resolution)
                XCTAssertEqual(s.widthPx, aspect.widthPx * resolution.factor)
                XCTAssertEqual(Double(s.logicalWidth) * s.pixelRatio, Double(s.widthPx), accuracy: 2)
                XCTAssertEqual(Double(s.logicalHeight) * s.pixelRatio, Double(s.heightPx), accuracy: 2)
            }
        }
    }

    func testACityRouteFitsAboveTheStatisticsAndFillsOneDimension() {
        let route = [(52.37, 4.88), (52.40, 4.90), (52.35, 4.95), (52.38, 4.85)]
        assertInsideUsableArea(route, showStats: true)
        assertInsideUsableArea(route, showStats: false)
        let cam = camera(route)
        let points = route.map { MapImageGeometry.project(cam, spec: spec, latitude: $0.0, longitude: $0.1) }
        let xs = points.map(\.x)
        let ys = points.map(\.y)
        let usableW = Double(spec.widthPx - 2 * spec.sidePaddingPx)
        let usableH = Double(spec.heightPx - spec.reservedBottomPx(showStats: true) - spec.bottomGapPx - spec.topPaddingPx)
        XCTAssert(xs.max()! - xs.min()! > usableW - 2 || ys.max()! - ys.min()! > usableH - 2)
    }

    func testHidingTheStatisticsGivesTheRouteMoreRoom() {
        let route = [(40.0, -3.0), (41.5, -3.2)]
        XCTAssertGreaterThan(camera(route, showStats: false).zoom, camera(route, showStats: true).zoom)
    }

    func testASinglePointGetsStreetLevelAndATinyRouteNoCloser() {
        XCTAssertEqual(camera([(48.85, 2.35)]).zoom, MapImageGeometry.pointZoom)
        assertInsideUsableArea([(48.85, 2.35)], showStats: true)
        XCTAssertEqual(camera([(48.85, 2.35), (48.85001, 2.35001)]).zoom, MapImageGeometry.maxZoom)
    }

    func testARouteAcrossTheDateLineIsFramedTheShortWayRound() {
        let route = [(-17.7, 178.0), (-17.9, 179.9), (-18.1, -179.5), (-18.0, -178.4)]
        let extent = MapImageGeometry.trackExtent(latitudes: route.map(\.0), longitudes: route.map(\.1))!
        XCTAssertLessThan(extent.lonSpan, 5)
        assertInsideUsableArea(route, showStats: true)
    }

    func testARouteAroundHalfTheWorldFitsEveryShape() {
        let route = [(55.75, 37.6), (35.68, 139.7), (40.7, -74.0), (-33.9, 151.2)]
        for aspect in MapImageAspect.allCases {
            assertInsideUsableArea(route, showStats: true, spec: MapImageSpec(aspect: aspect))
        }
    }

    func testOverlaysStayApart() {
        for aspect in MapImageAspect.allCases {
            let s = MapImageSpec(aspect: aspect)
            XCTAssertFalse(s.brandBox(rtl: false).intersects(s.statsBox(rtl: false)), "\(aspect)")
            XCTAssertFalse(s.brandBox(rtl: true).intersects(s.statsBox(rtl: true)), "\(aspect)")
        }
    }

    func testTheAttributionAlwaysCreditsOpenStreetMapContributors() {
        XCTAssertEqual(MapImageText.withOsmCredit("OpenFreeMap © OpenMapTiles Data from OpenStreetMap"),
                       "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors")
        XCTAssertEqual(MapImageText.withOsmCredit("© OpenStreetMap contributors"), "© OpenStreetMap contributors")
        XCTAssertEqual(MapImageText.withOsmCredit("X"), "X © OpenStreetMap contributors")
    }

    func testTheFilenameCarriesTheDateRange() {
        // 2026-05-12 is epoch day 20585.
        XCTAssertEqual(MapImageText.iso(0), "1970-01-01")
        XCTAssertEqual(MapImageText.iso(20585), "2026-05-12")
        XCTAssertEqual(MapImageText.filename(firstDay: 20585, lastDay: 20596, format: .png),
                       "timeline-route-2026-05-12_2026-05-23.png")
        XCTAssertEqual(MapImageText.filename(firstDay: 20585, lastDay: 20585, format: .jpeg),
                       "timeline-route-2026-05-12.jpg")
        XCTAssertEqual(MapImageText.filename(firstDay: nil, lastDay: nil, format: .png), "timeline-route.png")
    }
}
