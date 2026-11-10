import Foundation

/// The pure half of the Map image, the twin of the Android MapImageLayout.kt:
/// what the picture is, where the camera goes and where each overlay sits.
/// Nothing here touches MapLibre or a graphics context, so the tests check it.

/// The picture's shape. Pixel sizes are the Standard resolution; High doubles both.
enum MapImageAspect: String, CaseIterable, Identifiable {
    case portrait, socialPost, square, landscape

    var id: String { rawValue }

    var widthPx: Int {
        switch self {
        case .portrait: 1080
        case .socialPost: 1080
        case .square: 1600
        case .landscape: 1920
        }
    }

    var heightPx: Int {
        switch self {
        case .portrait: 1920
        case .socialPost: 1350
        case .square: 1600
        case .landscape: 1080
        }
    }

    var layout: AspectLayout {
        switch self {
        case .portrait: AspectLayout(statsScale: 1, compactStats: false, brandScale: 1, sidePadding: 84)
        case .socialPost: AspectLayout(statsScale: 0.86, compactStats: false, brandScale: 0.94, sidePadding: 76)
        case .square: AspectLayout(statsScale: 0.9, compactStats: false, brandScale: 0.94, sidePadding: 80)
        // Height is what a landscape image is short of: a compact card in the
        // bottom-start corner, with the attribution beside it.
        case .landscape: AspectLayout(statsScale: 0.84, compactStats: true, brandScale: 0.88, sidePadding: 96)
        }
    }

    var labelKey: String {
        switch self {
        case .portrait: "map_image_aspect_portrait"
        case .socialPost: "map_image_aspect_post"
        case .square: "map_image_aspect_square"
        case .landscape: "map_image_aspect_landscape"
        }
    }
}

/// How one aspect arranges its overlays, in design units.
struct AspectLayout: Equatable {
    let statsScale: Double
    let compactStats: Bool
    let brandScale: Double
    let sidePadding: Double
}

enum MapImageResolution: Int, CaseIterable, Identifiable {
    case standard = 1, high = 2
    var id: Int { rawValue }
    var factor: Int { rawValue }
}

enum MapImageFormat: String, CaseIterable, Identifiable {
    case png, jpeg
    var id: String { rawValue }
    var fileExtension: String { self == .png ? "png" : "jpg" }
    var label: String { self == .png ? "PNG" : "JPEG" }
    static let jpegQuality = 0.93
}

/// An axis-aligned box in output pixels.
struct ImageBox: Equatable {
    var left: Double
    var top: Double
    var right: Double
    var bottom: Double
    var width: Double { right - left }
    var height: Double { bottom - top }

    func intersects(_ o: ImageBox) -> Bool {
        left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }
}

/// One output image: its pixel size and where everything on it goes. Every
/// size is a design unit (one pixel of the 1080 px wide portrait) times
/// [unit], which grows with the shorter side. MapLibre renders at the logical
/// size with [pixelRatio]; the shorter logical side is always 432.
struct MapImageSpec: Equatable {
    var aspect: MapImageAspect
    var resolution: MapImageResolution = .standard

    static let designShortSide = 1080.0
    static let logicalShortSide = 432.0
    /// A compact card never takes more than this share of the width.
    static let compactStatsShare = 0.48

    var widthPx: Int { aspect.widthPx * resolution.factor }
    var heightPx: Int { aspect.heightPx * resolution.factor }
    private var layout: AspectLayout { aspect.layout }
    private var shortSide: Double { Double(min(widthPx, heightPx)) }

    var unit: Double { shortSide / Self.designShortSide }
    var pixelRatio: Double { shortSide / Self.logicalShortSide }
    var logicalWidth: Int { Int((Double(widthPx) / pixelRatio).rounded()) }
    var logicalHeight: Int { Int((Double(heightPx) / pixelRatio).rounded()) }

    func u(_ designUnits: Double) -> Double { designUnits * unit }

    var marginPx: Double { u(40) }
    var brandScale: Double { layout.brandScale }
    var brandHeightPx: Double { u(72 * brandScale) }
    var brandMaxWidthPx: Double { u(460 * brandScale) }
    var markerRadiusPx: Double { u(30) }
    var statsScale: Double { layout.statsScale }
    var compactStats: Bool { layout.compactStats }
    var statsCardHeightPx: Double { u(248 * statsScale) }
    var attributionBandPx: Double { u(84) }
    var attributionBottomMarginPx: Double { u(24) }

    var sidePaddingPx: Int { Int(u(layout.sidePadding).rounded()) }
    var topPaddingPx: Int { Int((marginPx + brandHeightPx + u(60)).rounded()) }
    var bottomGapPx: Int { Int(u(64).rounded()) }

    func reservedBottomPx(showStats: Bool) -> Int {
        let v: Double
        if !showStats { v = attributionBandPx }
        else if compactStats { v = marginPx + statsCardHeightPx + u(22) }
        else { v = attributionBandPx + statsCardHeightPx + u(22) }
        return Int(v.rounded())
    }

    func brandBox(rtl: Bool, width: Double? = nil) -> ImageBox {
        let w = width ?? brandMaxWidthPx
        let top = marginPx
        let W = Double(widthPx)
        return rtl ? ImageBox(left: W - marginPx - w, top: top, right: W - marginPx, bottom: top + brandHeightPx)
            : ImageBox(left: marginPx, top: top, right: marginPx + w, bottom: top + brandHeightPx)
    }

    var statsMaxWidthPx: Double {
        compactStats ? Double(widthPx) * Self.compactStatsShare : Double(widthPx) - marginPx * 2
    }

    func statsBox(rtl: Bool, width: Double? = nil) -> ImageBox {
        let w = width ?? statsMaxWidthPx
        let H = Double(heightPx)
        let W = Double(widthPx)
        let bottom = compactStats ? H - marginPx : H - attributionBandPx
        let top = bottom - statsCardHeightPx
        return rtl ? ImageBox(left: W - marginPx - w, top: top, right: W - marginPx, bottom: bottom)
            : ImageBox(left: marginPx, top: top, right: marginPx + w, bottom: bottom)
    }

    /// The attribution pill's lower edge and the longest it may be.
    func attributionSlot(showStats: Bool, pillHeight: Double) -> (bottom: Double, maxWidth: Double) {
        let besideCard = compactStats && showStats
        let W = Double(widthPx)
        let H = Double(heightPx)
        let maxWidth = besideCard ? W - marginPx * 2 - statsMaxWidthPx - u(24) : W - marginPx * 2
        let bottom = besideCard ? H - marginPx
            : min(H - (attributionBandPx - pillHeight) / 2, H - attributionBottomMarginPx)
        return (bottom, maxWidth)
    }
}

/// How the statistics figures share the card's row. They keep their natural
/// widths when they fit; otherwise the figures' type shrinks, down to
/// [minScale], before any value is cut, and a cut column keeps a share of the
/// room in proportion to what it needs, never an equal slice.
struct StatsColumnsFit: Equatable {
    /// Of the value and label fonts and of the gap between columns.
    let scale: Double
    let widths: [Double]
    let gap: Double

    static let minScale = 0.72
    /// Measured widths are not exact: a row that misses by less still fits.
    static let tolerance = 0.5

    static func fit(natural: [Double], gap: Double, available: Double) -> StatsColumnsFit {
        let gaps = gap * Double(max(natural.count - 1, 0))
        let total = natural.reduce(0, +) + gaps
        guard total > available + tolerance, total > 0 else {
            return StatsColumnsFit(scale: 1, widths: natural, gap: gap)
        }
        let scale = max(available / total, minScale)
        let scaledGaps = gaps * scale
        let scaled = natural.map { $0 * scale }
        let scaledSum = scaled.reduce(0, +)
        if scaledSum + scaledGaps <= available + tolerance || scaledSum <= 0 {
            return StatsColumnsFit(scale: scale, widths: scaled, gap: gap * scale)
        }
        let share = max(available - scaledGaps, 0) / scaledSum
        return StatsColumnsFit(scale: scale, widths: scaled.map { $0 * share }, gap: gap * scale)
    }
}

/// Where to point the camera, in MapLibre's terms.
struct MapImageCamera: Equatable {
    let latitude: Double
    let longitude: Double
    let zoom: Double
}

/// The box a track covers, longitude as an arc eastward from [westLon].
struct TrackWindow: Equatable {
    let minLat: Double
    let maxLat: Double
    let westLon: Double
    let lonSpan: Double
}

enum MapImageGeometry {
    static let tileSize = 512.0
    static let maxMercatorLatitude = 85.051129
    static let maxZoom = 16.0
    static let pointZoom = 14.0

    static func wrapLongitude(_ lon: Double) -> Double {
        var l = (lon + 180).truncatingRemainder(dividingBy: 360)
        if l < 0 { l += 360 }
        return l - 180
    }

    /// The shortest arc that holds every sample: the complement of the widest
    /// empty stretch of longitude between them. Nil for no samples.
    static func trackExtent(latitudes: [Double], longitudes: [Double]) -> TrackWindow? {
        guard let minLat = latitudes.min(), let maxLat = latitudes.max(), !longitudes.isEmpty else { return nil }
        let lons = longitudes.map(wrapLongitude).sorted()
        var widestGap = lons[0] + 360 - lons[lons.count - 1]
        var west = lons[0]
        for i in 1..<lons.count {
            let gap = lons[i] - lons[i - 1]
            if gap > widestGap {
                widestGap = gap
                west = lons[i]
            }
        }
        return TrackWindow(minLat: minLat, maxLat: maxLat, westLon: west, lonSpan: min(max(360 - widestGap, 0), 360))
    }

    /// Fit [extent] into the part of the image the route may use - below the
    /// branding, above the statistics and attribution, inside the side
    /// padding - in Web Mercator directly, since the usable area is off centre.
    static func fitCamera(_ extent: TrackWindow, spec: MapImageSpec, showStats: Bool) -> MapImageCamera {
        let ratio = spec.pixelRatio
        let left = Double(spec.sidePaddingPx) / ratio
        let right = Double(spec.widthPx - spec.sidePaddingPx) / ratio
        let top = Double(spec.topPaddingPx) / ratio
        let bottom = Double(spec.heightPx - spec.reservedBottomPx(showStats: showStats) - spec.bottomGapPx) / ratio
        let usableWidth = right - left
        let usableHeight = bottom - top

        let x0 = mercatorX(extent.westLon)
        let x1 = x0 + extent.lonSpan / 360
        let y0 = mercatorY(extent.maxLat)
        let y1 = mercatorY(extent.minLat)
        let dx = x1 - x0
        let dy = y1 - y0

        let zoom: Double
        if dx <= 0 && dy <= 0 {
            zoom = pointZoom
        } else {
            let byWidth = dx > 0 ? log2(usableWidth / (dx * tileSize)) : .infinity
            let byHeight = dy > 0 ? log2(usableHeight / (dy * tileSize)) : .infinity
            zoom = min(max(min(byWidth, byHeight), 0), maxZoom)
        }
        let scale = tileSize * pow(2, zoom)
        let boxX = (x0 + x1) / 2
        let boxY = (y0 + y1) / 2
        let viewCenterX = Double(spec.logicalWidth) / 2
        let viewCenterY = Double(spec.logicalHeight) / 2
        let cameraX = boxX + (viewCenterX - (left + right) / 2) / scale
        let cameraY = min(max(boxY + (viewCenterY - (top + bottom) / 2) / scale, 0), 1)
        return MapImageCamera(
            latitude: latitudeOfMercatorY(cameraY),
            longitude: wrapLongitude(cameraX * 360 - 180),
            zoom: zoom
        )
    }

    /// Where a coordinate lands in the output image, in output pixels, for a
    /// snapshot taken at [camera]: the projection MapLibre renders with.
    static func project(_ camera: MapImageCamera, spec: MapImageSpec, latitude: Double, longitude: Double) -> (x: Double, y: Double) {
        let scale = tileSize * pow(2, camera.zoom) * spec.pixelRatio
        var dLon = longitude - camera.longitude
        dLon -= 360 * ((dLon + 180) / 360).rounded(.down)
        let x = Double(spec.widthPx) / 2 + dLon / 360 * scale
        let y = Double(spec.heightPx) / 2 + (mercatorY(latitude) - mercatorY(camera.latitude)) * scale
        return (x, y)
    }

    static func mercatorX(_ lon: Double) -> Double { (lon + 180) / 360 }

    static func mercatorY(_ latitude: Double) -> Double {
        let lat = min(max(latitude, -maxMercatorLatitude), maxMercatorLatitude)
        let s = sin(lat * .pi / 180)
        return 0.5 - log((1 + s) / (1 - s)) / (4 * .pi)
    }

    static func latitudeOfMercatorY(_ y: Double) -> Double {
        (2 * atan(exp((0.5 - y) * 2 * .pi)) - .pi / 2) * 180 / .pi
    }
}

enum MapImageText {
    /// "timeline-route-2026-05-12_2026-05-23.png", or one date for a single day.
    static func filename(firstDay: Int64?, lastDay: Int64?, format: MapImageFormat) -> String {
        let base: String
        if let firstDay, let lastDay {
            base = firstDay == lastDay ? "timeline-route-\(iso(firstDay))"
                : "timeline-route-\(iso(firstDay))_\(iso(lastDay))"
        } else {
            base = "timeline-route"
        }
        return "\(base).\(format.fileExtension)"
    }

    /// An epoch day as yyyy-MM-dd, the proleptic Gregorian date it names.
    static func iso(_ epochDay: Int64) -> String {
        // Howard Hinnant's civil_from_days.
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return String(format: "%04lld-%02lld-%02lld", y, m, d)
    }

    /// OpenStreetMap's licence asks for "© OpenStreetMap contributors"; the
    /// image never prints less, whatever the source's own wording.
    static func withOsmCredit(_ attribution: String) -> String {
        if attribution.contains("OpenStreetMap contributors") { return attribution }
        if let r = attribution.range(of: #"(Data from\s+)?(©\s*)?OpenStreetMap"#, options: .regularExpression) {
            return attribution.replacingCharacters(in: r, with: "© OpenStreetMap contributors")
        }
        return "\(attribution) © OpenStreetMap contributors".trimmingCharacters(in: .whitespaces)
    }

    static let fallbackAttribution = "© OpenMapTiles © OpenStreetMap contributors"
}
