import SwiftUI
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

    func testStatsFiguresKeepTheirWidthsWhenTheyFitEvenByAHair() {
        let natural = [212.0, 61.0, 83.0]
        let exact = natural.reduce(0, +) + 47 * 2
        // The 16:9 card is cut to its figures: what is left inside it can come
        // back a fraction short, which once squeezed "3,229 km" into "3,2…".
        let fit = StatsColumnsFit.fit(natural: natural, gap: 47, available: exact - 1e-9)
        XCTAssertEqual(fit.scale, 1)
        XCTAssertEqual(fit.widths, natural)
        XCTAssertEqual(fit.gap, 47)
    }

    func testStatsFiguresShrinkBeforeAnyValueIsCut() {
        let natural = [400.0, 100.0, 100.0]
        let fit = StatsColumnsFit.fit(natural: natural, gap: 50, available: 600)
        XCTAssertEqual(fit.scale, 600.0 / 700, accuracy: 1e-9)
        XCTAssertEqual(fit.widths[0], 400 * fit.scale, accuracy: 1e-9, "shrunk, not cut")
        XCTAssertEqual(fit.widths.reduce(0, +) + fit.gap * 2, 600, accuracy: 1e-6)
    }

    func testStatsFiguresCutInProportionWhenShrinkingIsNotEnough() {
        let natural = [600.0, 100.0, 100.0]
        let fit = StatsColumnsFit.fit(natural: natural, gap: 50, available: 400)
        XCTAssertEqual(fit.scale, StatsColumnsFit.minScale)
        XCTAssertEqual(fit.widths.reduce(0, +) + fit.gap * 2, 400, accuracy: 1e-6)
        XCTAssertEqual(fit.widths[0] / fit.widths[1], 6, accuracy: 1e-9, "never an equal slice")
    }

    func testTheLandscapeStatsCardHasRoomForLargeFigures() {
        // "12,345 km" and a long label at the landscape scale: well inside the card's cap.
        let s = MapImageSpec(aspect: .landscape)
        XCTAssertGreaterThan(s.statsMaxWidthPx, 900)
        XCTAssertLessThanOrEqual(s.statsBox(rtl: false).right, Double(s.widthPx) - s.marginPx)
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

    func testACustomSizeIsExactlyTheTypedPixelsWithTheNearestShapesLayout() {
        for (w, h) in [(400, 400), (3000, 2000), (1200, 3600), (5000, 1667), (8192, 4096), (2560, 1080)] {
            let s = MapImageSpec.custom(widthPx: w, heightPx: h)
            XCTAssertEqual(s.widthPx, w)
            XCTAssertEqual(s.heightPx, h)
            XCTAssertEqual(min(s.logicalWidth, s.logicalHeight), 432)
            XCTAssertEqual(Double(s.logicalWidth) * s.pixelRatio, Double(w), accuracy: s.pixelRatio / 2 + 0.01)
            XCTAssertGreaterThan(s.heightPx - s.reservedBottomPx(showStats: true) - s.bottomGapPx, s.topPaddingPx)
            let preview = s.preview()
            XCTAssertLessThanOrEqual(min(preview.widthPx, preview.heightPx), 1600)
            XCTAssertLessThanOrEqual(max(preview.widthPx, preview.heightPx), 1920)
            XCTAssertEqual(preview.compactStats, s.compactStats)
            XCTAssertEqual(Double(w) / Double(h), Double(preview.widthPx) / Double(preview.heightPx), accuracy: 0.01)
        }
        XCTAssertEqual(MapImageAspect.nearest(width: 3000, height: 1500), .landscape)
        XCTAssertEqual(MapImageAspect.nearest(width: 1200, height: 1000), .square)
        XCTAssertEqual(MapImageAspect.nearest(width: 2400, height: 3000), .socialPost)
        for aspect in MapImageAspect.allCases {
            XCTAssertEqual(MapImageSpec.custom(widthPx: aspect.widthPx, heightPx: aspect.heightPx), MapImageSpec(aspect: aspect))
        }
        XCTAssertEqual(MapImageSpec.custom(widthPx: 800, heightPx: 600).preview(), MapImageSpec.custom(widthPx: 800, heightPx: 600))
    }

    func testCustomSizesOutsideTheBoundsAreRefused() {
        XCTAssertNil(CustomSizeProblem.of(width: 3000, height: 2000))
        XCTAssertNil(CustomSizeProblem.of(width: 400, height: 1200))
        XCTAssertEqual(CustomSizeProblem.of(width: nil, height: 2000), .missing)
        XCTAssertEqual(CustomSizeProblem.of(width: 399, height: 1000), .outOfRange)
        XCTAssertEqual(CustomSizeProblem.of(width: 8193, height: 4000), .outOfRange)
        XCTAssertEqual(CustomSizeProblem.of(width: 400, height: 1201), .tooElongated)
        let big = MapImageSpec.custom(widthPx: 8000, heightPx: 6000)
        XCTAssertFalse(mapImageFitsMemory(big, available: 300 * 1024 * 1024))
        XCTAssertTrue(mapImageFitsMemory(big, available: 2 * 1024 * 1024 * 1024))
        XCTAssertTrue(mapImageFitsMemory(big, available: 0), "unknown is not none")
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

    func testThePreviewKeepsItsShapeInsideTheSheet() {
        let phone = CGSize(width: 390, height: 780)
        // Portrait: the height share is what limits it.
        let portrait = MapImagePreviewFrame.size(ratio: 9.0 / 16.0, sheet: phone, heightShare: 0.5)
        XCTAssertEqual(portrait.height, 390, accuracy: 0.001)
        XCTAssertEqual(portrait.width / portrait.height, 9.0 / 16.0, accuracy: 0.001)

        // Landscape: the column's width is.
        let landscape = MapImagePreviewFrame.size(ratio: 16.0 / 9.0, sheet: phone, heightShare: 0.5)
        XCTAssertEqual(landscape.width, 390 - MapImagePreviewFrame.sideMargins, accuracy: 0.001)
        XCTAssertEqual(landscape.width / landscape.height, 16.0 / 9.0, accuracy: 0.001)
    }

    func testOnlyAWideWindowAsksForTheWideSheet() throws {
        let landscape = CGSize(width: 1376, height: 950)
        let sheet = try XCTUnwrap(MapImagePreviewFrame.wideSheet(window: landscape, sizeClass: .regular))
        XCTAssertEqual(sheet.width, 1080, accuracy: 0.001)
        XCTAssertEqual(sheet.height, 860, accuracy: 0.001)
        XCTAssertGreaterThanOrEqual(sheet.width, MapImagePreviewFrame.twoColumnMinWidth)

        XCTAssertNil(MapImagePreviewFrame.wideSheet(window: CGSize(width: 1032, height: 1300), sizeClass: .regular),
                     "upright keeps the one column")
        XCTAssertNil(MapImagePreviewFrame.wideSheet(window: CGSize(width: 800, height: 700), sizeClass: .regular),
                     "a narrow window keeps the one column")
        XCTAssertNil(MapImagePreviewFrame.wideSheet(window: landscape, sizeClass: .compact))
    }

    /// The order of a turn: landscape, upright, landscape again. Each window
    /// gets its own answer, whatever the one before it was; the sheet came
    /// back to landscape at its upright size when the answer lagged a turn.
    func testATurnBackToLandscapeAsksForTheWideSheetAgain() {
        let landscape = CGSize(width: 1180, height: 820)
        let upright = CGSize(width: 820, height: 1180)
        let answers = [landscape, upright, landscape].map {
            MapImagePreviewFrame.wideSheet(window: $0, sizeClass: .regular)
        }
        XCTAssertNotNil(answers[0])
        XCTAssertNil(answers[1])
        XCTAssertEqual(answers[2], answers[0])
    }

    /// Before the sheet is in a window there is no live answer, and laying out
    /// again is a no-op rather than a crash.
    @MainActor func testASheetHostWithoutASheetDoesNothing() {
        let host = SheetHost()
        XCTAssertNil(host.liveWide)
        host.relayout(wide: CGSize(width: 1080, height: 772), force: true)
        host.relayout(wide: nil, force: false)
        XCTAssertNil(host.liveWide)
    }

    func testTheWidePaneKeepsThePictureShapeAndRoomForItsCaption() {
        let pane = CGSize(width: 640, height: 760)
        for aspect in MapImageAspect.allCases {
            let ratio = CGFloat(aspect.widthPx) / CGFloat(aspect.heightPx)
            let fit = MapImagePreviewFrame.paneFit(ratio: ratio, pane: pane)
            XCTAssertEqual(fit.width / fit.height, ratio, accuracy: 0.001, "\(aspect)")
            XCTAssertLessThanOrEqual(fit.width, pane.width + 0.001)
            XCTAssertLessThanOrEqual(fit.height + MapImagePreviewFrame.captionHeight + MapImagePreviewFrame.captionSpacing,
                                     pane.height + 0.001)
        }
    }
}
