import MapLibre
import UIKit

/// What one image is made of: the line the map shows, its statistics and
/// the choices on the Map image screen.
struct MapImageRequest {
    var line: MapLine
    var stats: ImageStats?
    var placeCount: Int?
    var spec: MapImageSpec
    var showStats: Bool
    var showEndpoints: Bool
}

enum MapImageError: Error {
    case nothingToDraw
    case snapshot(String)
}

/// The exact name on the branding: a product name, never translated.
private let brandName = "Timeline Exporter"

/// Render [request] to an opaque image of exactly spec.widthPx x spec.heightPx:
/// a MapLibre snapshot of the light style with the line drawn under the
/// labels, then the branding, the statistics card, the S/E markers and the
/// map attribution painted on top with Core Graphics.
@MainActor
func renderMapImage(_ request: MapImageRequest) async throws -> UIImage {
    let spec = request.spec
    guard let extent = MapImageGeometry.trackExtent(latitudes: request.line.latitudes,
                                                    longitudes: request.line.longitudes) else {
        throw MapImageError.nothingToDraw
    }
    let showStats = request.showStats && request.stats != nil
    let camera = MapImageGeometry.fitCamera(extent, spec: spec, showStats: showStats)
    let shot = try await SnapshotJob(request: request, camera: camera).run()

    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let size = CGSize(width: spec.widthPx, height: spec.heightPx)
    let rtl = UIView.userInterfaceLayoutDirection(for: .unspecified) == .rightToLeft
    return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
        UIColor.white.setFill()
        ctx.fill(CGRect(origin: .zero, size: size))
        shot.image.draw(in: CGRect(origin: .zero, size: size))
        let painter = OverlayPainter(spec: spec, rtl: rtl, cg: ctx.cgContext)
        if request.showEndpoints, let first = request.line.firstCoordinate, let last = request.line.lastCoordinate {
            painter.drawEndpoints(
                start: MapImageGeometry.project(camera, spec: spec, latitude: first.latitude, longitude: first.longitude),
                end: MapImageGeometry.project(camera, spec: spec, latitude: last.latitude, longitude: last.longitude)
            )
        }
        var cardShown = false
        if showStats, let stats = request.stats {
            painter.drawStats(stats, placeCount: request.placeCount)
            cardShown = true
        }
        painter.drawAttribution(shot.attribution, showStats: cardShown)
        painter.drawBranding()
    }
}

/// PNG or JPEG bytes of a rendered image.
func encodeMapImage(_ image: UIImage, format: MapImageFormat) -> Data? {
    switch format {
    case .png: image.pngData()
    case .jpeg: image.jpegData(compressionQuality: MapImageFormat.jpegQuality)
    }
}

extension MapLine {
    var firstCoordinate: CLLocationCoordinate2D? {
        guard let i = pieces.first?.range.lowerBound ?? (latitudes.isEmpty ? nil : 0) else { return nil }
        return CLLocationCoordinate2D(latitude: latitudes[i], longitude: longitudes[i])
    }

    var lastCoordinate: CLLocationCoordinate2D? {
        guard let i = pieces.last.map({ $0.range.upperBound - 1 }) ?? (latitudes.isEmpty ? nil : latitudes.count - 1)
        else { return nil }
        return CLLocationCoordinate2D(latitude: latitudes[i], longitude: longitudes[i])
    }
}

// MARK: Snapshot

private struct Shot {
    let image: UIImage
    let attribution: String
}

/// One snapshot. The line goes into the snapshotter's own style once it has
/// loaded, below the first label layer, so place names stay readable.
private final class SnapshotJob: NSObject, MLNMapSnapshotterDelegate {
    private let request: MapImageRequest
    private let camera: MapImageCamera
    private var snapshotter: MLNMapSnapshotter?
    private var attribution = ""

    init(request: MapImageRequest, camera: MapImageCamera) {
        self.request = request
        self.camera = camera
    }

    @MainActor func run() async throws -> Shot {
        let spec = request.spec
        guard let styleURL = Bundle.main.url(forResource: "map_style_light", withExtension: "json") else {
            throw MapImageError.snapshot("No map style")
        }
        // The altitude is ignored: zoomLevel below wins.
        let mapCamera = MLNMapCamera(
            lookingAtCenter: CLLocationCoordinate2D(latitude: camera.latitude, longitude: camera.longitude),
            altitude: 10_000, pitch: 0, heading: 0
        )
        let options = MLNMapSnapshotOptions(
            styleURL: styleURL,
            camera: mapCamera,
            size: CGSize(width: spec.logicalWidth, height: spec.logicalHeight)
        )
        options.zoomLevel = camera.zoom
        options.scale = spec.pixelRatio
        options.showsLogo = false
        options.showsAttribution = false
        let snapshotter = MLNMapSnapshotter(options: options)
        snapshotter.delegate = self
        self.snapshotter = snapshotter

        let snapshot: MLNMapSnapshot = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<MLNMapSnapshot, Error>) in
                snapshotter.start { snapshot, error in
                    if let snapshot {
                        cont.resume(returning: snapshot)
                    } else {
                        cont.resume(throwing: MapImageError.snapshot(error?.localizedDescription ?? "unknown"))
                    }
                }
            }
        } onCancel: {
            Task { @MainActor in snapshotter.cancel() }
        }
        self.snapshotter = nil
        return Shot(image: snapshot.image, attribution: attribution)
    }

    func mapSnapshotter(_ snapshotter: MLNMapSnapshotter, didFinishLoading style: MLNStyle) {
        addLine(to: style)
        attribution = Self.attribution(of: style)
    }

    private func addLine(to style: MLNStyle) {
        let line = request.line
        var features: [MLNPolylineFeature] = []
        for piece in line.pieces where piece.range.count >= 2 {
            var coords = piece.range.map {
                CLLocationCoordinate2D(latitude: line.latitudes[$0], longitude: line.longitudes[$0])
            }
            let f = MLNPolylineFeature(coordinates: &coords, count: UInt(coords.count))
            f.attributes = ["color": piece.color.isEmpty ? TimelineMapView.Coordinator.trackColor : piece.color]
            features.append(f)
        }
        guard !features.isEmpty else { return }
        let source = MLNShapeSource(identifier: "image-line", features: features, options: nil)
        style.addSource(source)
        let firstLabel = style.layers.first { $0 is MLNSymbolStyleLayer }

        func add(_ layer: MLNStyleLayer) {
            if let firstLabel { style.insertLayer(layer, below: firstLabel) } else { style.addLayer(layer) }
        }
        // A pale band under the line, so it reads over roads and water.
        let casing = MLNLineStyleLayer(identifier: "image-casing", source: source)
        casing.lineColor = NSExpression(forConstantValue: UIColor.white)
        casing.lineOpacity = NSExpression(forConstantValue: 0.9)
        casing.lineWidth = NSExpression(forConstantValue: 6.4)
        casing.lineCap = NSExpression(forConstantValue: "round")
        casing.lineJoin = NSExpression(forConstantValue: "round")
        add(casing)

        let colors = Array(Set(features.compactMap { $0.attributes["color"] as? String })).sorted()
        for color in colors {
            let layer = MLNLineStyleLayer(identifier: "image-line-\(color)", source: source)
            layer.predicate = NSPredicate(format: "color == %@", color)
            layer.lineColor = NSExpression(forConstantValue: UIColor(hex: color) ?? .systemBlue)
            layer.lineWidth = NSExpression(forConstantValue: 3.2)
            layer.lineCap = NSExpression(forConstantValue: "round")
            layer.lineJoin = NSExpression(forConstantValue: "round")
            add(layer)
        }
    }

    private static func attribution(of style: MLNStyle) -> String {
        let parts = style.sources.compactMap { $0 as? MLNTileSource }.flatMap { source in
            source.attributionInfos.map { $0.title.string }
        }
        let text = parts.joined(separator: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return text
    }
}

// MARK: Overlays

/// The overlays, in output pixels: every size is a design unit through spec.u.
private struct OverlayPainter {
    let spec: MapImageSpec
    let rtl: Bool
    let cg: CGContext

    private let ink = UIColor(hex: "#1F1D1A")!
    private let muted = UIColor(hex: "#6B675F")!
    private static let startColor = UIColor(hex: "#0E9F86")!
    private static let endColor = UIColor(hex: "#E5533D")!

    private func u(_ v: Double) -> CGFloat { CGFloat(spec.u(v)) }

    private func rect(_ b: ImageBox) -> CGRect {
        CGRect(x: CGFloat(b.left), y: CGFloat(b.top), width: CGFloat(b.width), height: CGFloat(b.height))
    }

    /// The same quiet white surface under the branding, the card and the attribution.
    private func surface(_ r: CGRect, corner: CGFloat, alpha: CGFloat, shadow: Bool) {
        cg.saveGState()
        if shadow {
            cg.setShadow(offset: CGSize(width: 0, height: u(4)), blur: u(18),
                         color: UIColor.black.withAlphaComponent(40.0 / 255).cgColor)
        }
        UIColor.white.withAlphaComponent(alpha).setFill()
        UIBezierPath(roundedRect: r, cornerRadius: corner).fill()
        cg.restoreGState()
    }

    private func font(_ size: CGFloat, _ weight: UIFont.Weight) -> UIFont {
        .systemFont(ofSize: size, weight: weight)
    }

    private func width(_ text: String, _ font: UIFont) -> CGFloat {
        (text as NSString).size(withAttributes: [.font: font]).width
    }

    /// Draw [text] with its baseline at [baseline], starting at [x] (or
    /// ending there when [alignEnd]), cut with an ellipsis past [maxWidth].
    private func draw(_ text: String, font: UIFont, color: UIColor, x: CGFloat, baseline: CGFloat,
                      maxWidth: CGFloat = .greatestFiniteMagnitude, alignEnd: Bool = false) {
        let shown = Self.fit(text, font: font, maxWidth: maxWidth)
        let w = width(shown, font)
        let left = alignEnd ? x - w : x
        let top = baseline - font.ascender
        (shown as NSString).draw(at: CGPoint(x: left, y: top), withAttributes: [.font: font, .foregroundColor: color])
    }

    private static func fit(_ text: String, font: UIFont, maxWidth: CGFloat) -> String {
        let attrs: [NSAttributedString.Key: Any] = [.font: font]
        if (text as NSString).size(withAttributes: attrs).width <= maxWidth { return text }
        var chars = Array(text)
        while !chars.isEmpty {
            chars.removeLast()
            let candidate = String(chars).trimmingCharacters(in: .whitespaces) + "…"
            if (candidate as NSString).size(withAttributes: attrs).width <= maxWidth { return candidate }
        }
        return ""
    }

    func drawEndpoints(start: (x: Double, y: Double), end: (x: Double, y: Double)) {
        let radius = spec.markerRadiusPx
        var (sx, sy) = start
        var (ex, ey) = end
        // A round trip starts and ends at the same door: pull the markers
        // apart so E never hides S.
        let minGap = radius * 2 + spec.u(6)
        let distance = hypot(ex - sx, ey - sy)
        if distance < minGap {
            let (ux, uy) = distance > 0.5 ? ((ex - sx) / distance, (ey - sy) / distance) : (1.0, 0.0)
            let push = (minGap - distance) / 2
            sx -= ux * push; sy -= uy * push
            ex += ux * push; ey += uy * push
        }
        marker(x: sx, y: sy, radius: radius, color: Self.startColor, letter: "S")
        marker(x: ex, y: ey, radius: radius, color: Self.endColor, letter: "E")
    }

    private func marker(x: Double, y: Double, radius: Double, color: UIColor, letter: String) {
        let r = CGRect(x: CGFloat(x - radius), y: CGFloat(y - radius), width: CGFloat(radius * 2), height: CGFloat(radius * 2))
        cg.saveGState()
        cg.setShadow(offset: CGSize(width: 0, height: u(3)), blur: u(8),
                     color: UIColor.black.withAlphaComponent(70.0 / 255).cgColor)
        color.setFill()
        cg.fillEllipse(in: r)
        cg.restoreGState()
        UIColor.white.setStroke()
        cg.setLineWidth(u(6))
        cg.strokeEllipse(in: r)
        let f = font(u(32), .bold)
        let w = width(letter, f)
        let baseline = CGFloat(y) + (f.capHeight / 2)
        draw(letter, font: f, color: .white, x: CGFloat(x) - w / 2, baseline: baseline)
    }

    /// The app icon and its name, small, in the top-start corner.
    func drawBranding() {
        let s = spec.brandScale
        let height = CGFloat(spec.brandHeightPx)
        let iconSize: CGFloat = u(48 * s)
        let inset: CGFloat = (height - iconSize) / 2
        let gap: CGFloat = u(14 * s)
        let endPad: CGFloat = u(24 * s)
        let nameFont = font(u(30 * s), .medium)
        let textWidth = width(brandName, nameFont)
        let w = min(inset + iconSize + gap + textWidth + endPad, CGFloat(spec.brandMaxWidthPx))
        let box = rect(spec.brandBox(rtl: rtl, width: Double(w)))
        surface(box, corner: u(20 * s), alpha: 245.0 / 255, shadow: true)

        let iconLeft: CGFloat = rtl ? box.maxX - inset - iconSize : box.minX + inset
        let iconRect = CGRect(x: iconLeft, y: box.minY + inset, width: iconSize, height: iconSize)
        if let icon = UIImage(named: "BrandIcon") {
            cg.saveGState()
            UIBezierPath(roundedRect: iconRect, cornerRadius: iconSize * 0.225).addClip()
            icon.draw(in: iconRect)
            cg.restoreGState()
        }
        let baseline = box.midY + (nameFont.capHeight / 2)
        let textX: CGFloat = rtl ? iconLeft - gap : iconLeft + iconSize + gap
        draw(brandName, font: nameFont, color: ink, x: textX, baseline: baseline, alignEnd: rtl)
    }

    /// A white card along the bottom: the date range, then up to three figures.
    func drawStats(_ stats: ImageStats, placeCount: Int?) {
        var columns: [(String, String)] = []
        if stats.distanceMeters >= 50 {
            columns.append((Fmt.km(stats.distanceMeters), L.s("map_image_stat_distance")))
        }
        if stats.dayCount > 0 {
            columns.append((Fmt.grouped(stats.dayCount), L.s("map_image_stat_days")))
        }
        if let placeCount, placeCount > 0 {
            columns.append((Fmt.grouped(placeCount), L.s("map_image_stat_places")))
        }
        let k = spec.statsScale
        let inner = u(40 * k)
        let gap = u(56 * k)
        let header = font(u(38 * k), .medium)
        let value = font(u(54 * k), .bold)
        let label = font(u(29 * k), .regular)
        let rangeText = Fmt.dayRange(CoreTimeline.localDay(stats.firstDay), CoreTimeline.localDay(stats.lastDay))
        let natural = columns.map { max(width($0.0, value), width($0.1, label)) }
        let figuresWidth = natural.reduce(0, +) + gap * CGFloat(max(columns.count - 1, 0))

        let maxWidth = CGFloat(spec.statsMaxWidthPx)
        let cardWidth = spec.compactStats
            ? min(max(figuresWidth, width(rangeText, header)) + inner * 2, maxWidth)
            : maxWidth
        let card = rect(spec.statsBox(rtl: rtl, width: Double(cardWidth)))
        surface(card, corner: u(32 * k), alpha: 250.0 / 255, shadow: true)

        let contentLeft = card.minX + inner
        let contentRight = card.maxX - inner
        let contentWidth = contentRight - contentLeft
        draw(rangeText, font: header, color: ink, x: rtl ? contentRight : contentLeft,
             baseline: card.minY + inner + header.ascender, maxWidth: contentWidth, alignEnd: rtl)

        let labelBaseline = card.maxY - inner + label.descender
        let valueBaseline = labelBaseline - label.ascender - u(10 * k) + value.descender
        let widths = figuresWidth <= contentWidth ? natural
            : Array(repeating: (contentWidth - gap * CGFloat(columns.count - 1)) / CGFloat(max(columns.count, 1)),
                    count: columns.count)
        var cursor: CGFloat = 0
        for (i, column) in columns.enumerated() {
            let w = widths[i]
            let x = rtl ? contentRight - cursor : contentLeft + cursor
            draw(column.0, font: value, color: ink, x: x, baseline: valueBaseline, maxWidth: w, alignEnd: rtl)
            draw(column.1, font: label, color: muted, x: x, baseline: labelBaseline, maxWidth: w, alignEnd: rtl)
            cursor += w + gap
        }
    }

    /// The map data's attribution on a small translucent pill at the bottom end.
    func drawAttribution(_ attribution: String, showStats: Bool) {
        let text = MapImageText.withOsmCredit(attribution.isEmpty ? MapImageText.fallbackAttribution : attribution)
        let padH = u(16)
        let padV = u(8)
        var size = u(25)
        func pillHeight(_ f: UIFont) -> CGFloat { f.ascender - f.descender + padV * 2 }
        let maxPill = CGFloat(spec.attributionSlot(showStats: showStats, pillHeight: Double(pillHeight(font(size, .regular)))).maxWidth)
        let maxText = maxPill - padH * 2
        while width(text, font(size, .regular)) > maxText && size > u(16) { size -= u(1) }
        let f = font(size, .regular)
        let shown = Self.fit(text, font: f, maxWidth: maxText)
        let w = width(shown, f)
        let h = pillHeight(f)
        let bottom = CGFloat(spec.attributionSlot(showStats: showStats, pillHeight: Double(h)).bottom)
        let margin = CGFloat(spec.marginPx)
        let right = rtl ? margin + w + padH * 2 : CGFloat(spec.widthPx) - margin
        let pill = CGRect(x: right - w - padH * 2, y: bottom - h, width: w + padH * 2, height: h)
        surface(pill, corner: h / 2, alpha: 215.0 / 255, shadow: false)
        draw(shown, font: f, color: UIColor(hex: "#4A4740")!, x: pill.minX + padH,
             baseline: pill.maxY - padV + f.descender)
    }
}
