import MapLibre
import SwiftUI
import UIKit

/// What the map shows: a line (the track or trips) or the places.
struct MapContent: Equatable {
    var line = MapLine()
    var places: [PlaceItem] = []
    var selectedPlace: String?
    /// One day of a line coloured by day, brought forward; nil is every day.
    var selectedDay: Int64?
}

/// How the base map is doing. The user's own line is drawn whatever this says.
enum MapStatus: Equatable {
    case loading, ready, tilesUnavailable, failed
}

/// Where the user last moved the map to, for this content. Outlives the map
/// views: a Form drops rows that scroll away, and the full-screen map is
/// another view of the same thing.
final class MapCameraMemory {
    struct Camera {
        let line: MapLine
        let places: [PlaceItem]
        let center: CLLocationCoordinate2D
        let zoom: Double
    }

    private var camera: Camera?

    func save(_ content: MapContent, center: CLLocationCoordinate2D, zoom: Double) {
        camera = Camera(line: content.line, places: content.places, center: center, zoom: zoom)
    }

    func clear() {
        camera = nil
    }

    /// The saved camera, when it was left on this same content.
    func saved(for content: MapContent) -> Camera? {
        guard let camera, camera.line == content.line, camera.places == content.places else { return nil }
        return camera
    }
}

/// The preview map: MapLibre with the app's OpenFreeMap styles. Lines are
/// drawn one layer per colour (at most the 21 day colours), places as
/// circles sized by visits. Bumping [fitToken] frames everything again;
/// bumping [restoreToken] goes back to where [memory] was left.
struct TimelineMapView: UIViewRepresentable {
    let content: MapContent
    let fitToken: Int
    let restoreToken: Int
    let memory: MapCameraMemory
    /// A map drawn edge to edge: how much floats over its bottom, above the
    /// safe area. Framing keeps clear of that and of the safe area, and the
    /// attribution sits above it. nil is a map inside the page.
    var bottomOverlay: CGFloat? = nil
    let onStatus: (MapStatus) -> Void
    let onSelectPlace: (String?) -> Void

    @Environment(\.colorScheme) private var colorScheme

    func makeCoordinator() -> Coordinator {
        Coordinator(memory: memory, onStatus: onStatus, onSelectPlace: onSelectPlace)
    }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: Self.styleURL(colorScheme))
        map.delegate = context.coordinator
        map.logoView.isHidden = true
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.attributionButtonPosition = .bottomLeft
        map.accessibilityIdentifier = "map"

        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        for g in map.gestureRecognizers ?? [] where (g as? UITapGestureRecognizer)?.numberOfTapsRequired == 2 {
            tap.require(toFail: g)
        }
        map.addGestureRecognizer(tap)

        let c = context.coordinator
        c.scheme = colorScheme
        c.fitToken = fitToken
        c.restoreToken = restoreToken
        c.bottomOverlay = bottomOverlay
        placeAttribution(on: map)
        // The map started loading in its initialiser, before it had a delegate.
        c.beginLoading()
        c.keepCamera = c.restoreCamera(for: content, on: map)
        c.show(content, on: map, fit: !c.keepCamera)
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        c.onSelectPlace = onSelectPlace
        c.onStatus = onStatus
        c.bottomOverlay = bottomOverlay
        placeAttribution(on: map)
        if c.scheme != colorScheme {
            c.scheme = colorScheme
            // A new style drops the sources; didFinishLoading puts them back.
            c.beginLoading()
            map.styleURL = Self.styleURL(colorScheme)
        }
        let refit = c.fitToken != fitToken
        c.fitToken = fitToken
        c.show(content, on: map, fit: refit)
        if c.restoreToken != restoreToken {
            c.restoreToken = restoreToken
            _ = c.restoreCamera(for: content, on: map)
        }
    }

    /// MapLibre's default margin, plus whatever floats over the map's bottom.
    private func placeAttribution(on map: MLNMapView) {
        let margins = CGPoint(x: 8, y: 8 + (bottomOverlay ?? 0))
        if map.attributionButtonMargins != margins { map.attributionButtonMargins = margins }
    }

    static func dismantleUIView(_ map: MLNMapView, coordinator: Coordinator) {
        coordinator.stopLoadingWatch()
    }

    static func styleURL(_ scheme: ColorScheme) -> URL? {
        Bundle.main.url(forResource: scheme == .dark ? "map_style_dark" : "map_style_light", withExtension: "json")
    }

    // MARK: Coordinator

    final class Coordinator: NSObject, MLNMapViewDelegate {
        var onSelectPlace: (String?) -> Void
        var onStatus: (MapStatus) -> Void
        var scheme: ColorScheme?
        var fitToken = 0
        var restoreToken = 0
        var bottomOverlay: CGFloat?
        private let memory: MapCameraMemory
        private var shown: MapContent?
        private var wanted = MapContent()
        private var fitPending = false
        private var lineLayers: [String] = []
        /// A saved camera was just put back: the first drawing must not move it.
        var keepCamera = false
        private var status: MapStatus?
        private var loadingWatch: DispatchWorkItem?

        private static let lineSource = "timeline-line"
        private static let placeSource = "timeline-places"
        private static let selectedSource = "timeline-selected"
        private static let placeLayer = "timeline-places"
        private static let dayLayer = "timeline-day-highlight"
        static let trackColor = "#1565C0"
        /// Without a network the style's TileJSON never arrives and MapLibre
        /// says nothing, so a map not ready by then is reported as such.
        private static let loadingTimeout: TimeInterval = 12
        private static let otherDaysOpacity = 0.22

        init(memory: MapCameraMemory, onStatus: @escaping (MapStatus) -> Void,
             onSelectPlace: @escaping (String?) -> Void) {
            self.memory = memory
            self.onStatus = onStatus
            self.onSelectPlace = onSelectPlace
        }

        /// Puts the camera back where the user left this content.
        func restoreCamera(for content: MapContent, on map: MLNMapView) -> Bool {
            guard let camera = memory.saved(for: content) else { return false }
            map.setCenter(camera.center, zoomLevel: camera.zoom, animated: false)
            return true
        }

        /// Draw [content]; the first content and a bump of the fit token frame it.
        func show(_ content: MapContent, on map: MLNMapView, fit: Bool) {
            let first = shown == nil && wanted == MapContent()
            let changedData = content.line != wanted.line || content.places != wanted.places
            let changedDay = content.selectedDay != wanted.selectedDay
            wanted = content
            if (fit || first || changedData || changedDay) && !(keepCamera && first) { fitPending = true }
            if changedData || changedDay || fit { markBusy(map) }
            guard let style = map.style else { return }
            apply(to: style, map: map)
        }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            shown = nil
            lineLayers = []
            apply(to: style, map: mapView)
        }

        // MARK: Status

        func beginLoading() {
            report(.loading)
            armLoadingWatch()
        }

        func stopLoadingWatch() {
            loadingWatch?.cancel()
            loadingWatch = nil
        }

        private func armLoadingWatch() {
            stopLoadingWatch()
            let watch = DispatchWorkItem { [weak self] in
                guard let self, self.status == .loading else { return }
                self.report(.tilesUnavailable)
            }
            loadingWatch = watch
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.loadingTimeout, execute: watch)
        }

        /// Never straight into a SwiftUI update: this can run during one.
        private func report(_ new: MapStatus) {
            guard status != new else { return }
            status = new
            let callback = onStatus
            DispatchQueue.main.async { callback(new) }
        }

        func mapViewWillStartLoadingMap(_ mapView: MLNMapView) {
            beginLoading()
        }

        func mapViewDidFinishLoadingMap(_ mapView: MLNMapView) {
            stopLoadingWatch()
            report(.ready)
        }

        func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
            stopLoadingWatch()
            report(.failed)
        }

        // Store screenshots: the UI test waits for the value "idle" (the
        // content drawn, the tiles in) instead of a fixed pause.
        func mapViewDidBecomeIdle(_ mapView: MLNMapView) {
            guard ScreenshotMode.isActive, let shown, shown == wanted else { return }
            mapView.accessibilityValue = "idle"
        }

        func mapView(_ mapView: MLNMapView, regionWillChangeAnimated animated: Bool) {
            markBusy(mapView)
        }

        /// Only the user's own moves are worth coming back to.
        func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            guard !reason.isEmpty, !reason.contains(.programmatic) else { return }
            memory.save(wanted, center: mapView.centerCoordinate, zoom: mapView.zoomLevel)
        }

        private func markBusy(_ map: MLNMapView) {
            if ScreenshotMode.isActive { map.accessibilityValue = "busy" }
        }

        // MARK: Drawing

        private func apply(to style: MLNStyle, map: MLNMapView) {
            let lineChanged = shown?.line != wanted.line
            if lineChanged { drawLine(wanted.line, style: style) }
            if lineChanged || shown?.selectedDay != wanted.selectedDay {
                highlightDay(wanted.selectedDay, of: wanted.line, style: style)
            }
            if shown?.places != wanted.places || shown?.selectedPlace != wanted.selectedPlace {
                drawPlaces(wanted.places, selected: wanted.selectedPlace, style: style)
            }
            if !keepCamera, let key = wanted.selectedPlace, key != shown?.selectedPlace,
               let p = wanted.places.first(where: { $0.key == key }) {
                let center = CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude)
                memory.clear()
                map.setCenter(center, zoomLevel: max(map.zoomLevel, 13), animated: true)
                fitPending = false
            }
            keepCamera = false
            shown = wanted
            if fitPending {
                fitPending = false
                fit(map)
            }
        }

        private func drawLine(_ line: MapLine, style: MLNStyle) {
            for id in lineLayers {
                if let layer = style.layer(withIdentifier: id) { style.removeLayer(layer) }
            }
            lineLayers = []
            if let old = style.source(withIdentifier: Self.lineSource) { style.removeSource(old) }
            guard !line.isEmpty else { return }

            var features: [MLNPolylineFeature] = []
            for piece in line.pieces where piece.range.count >= 2 {
                var coords = piece.range.map {
                    CLLocationCoordinate2D(latitude: line.latitudes[$0], longitude: line.longitudes[$0])
                }
                let f = MLNPolylineFeature(coordinates: &coords, count: UInt(coords.count))
                var attributes: [String: Any] = ["color": piece.color.isEmpty ? Self.trackColor : piece.color]
                if let day = piece.day { attributes["day"] = NSNumber(value: Int(day)) }
                f.attributes = attributes
                features.append(f)
            }
            let source = MLNShapeSource(identifier: Self.lineSource, features: features, options: nil)
            style.addSource(source)

            let below = style.layer(withIdentifier: Self.placeLayer)
            let casing = MLNLineStyleLayer(identifier: "timeline-casing", source: source)
            casing.lineColor = NSExpression(forConstantValue: UIColor.white.withAlphaComponent(0.85))
            casing.lineWidth = Self.zoomWidth(Self.casingWidths)
            Self.round(casing)
            insert(casing, below: below, style: style)

            let colors = Array(Set(features.compactMap { $0.attributes["color"] as? String })).sorted()
            for color in colors {
                let layer = MLNLineStyleLayer(identifier: "timeline-line-\(color)", source: source)
                layer.predicate = NSPredicate(format: "color == %@", color)
                layer.lineColor = NSExpression(forConstantValue: UIColor(hex: color) ?? .systemBlue)
                layer.lineWidth = Self.zoomWidth(Self.lineWidths)
                Self.round(layer)
                insert(layer, below: below, style: style)
            }
        }

        /// The picked day on top at full strength, the other days faded.
        private func highlightDay(_ day: Int64?, of line: MapLine, style: MLNStyle) {
            if let old = style.layer(withIdentifier: Self.dayLayer) {
                style.removeLayer(old)
                lineLayers.removeAll { $0 == Self.dayLayer }
            }
            let picked = line.days.first { $0.epochDay == day }
            let opacity = picked == nil ? 1.0 : Self.otherDaysOpacity
            for id in lineLayers {
                (style.layer(withIdentifier: id) as? MLNLineStyleLayer)?.lineOpacity =
                    NSExpression(forConstantValue: opacity)
            }
            guard let picked, let source = style.source(withIdentifier: Self.lineSource) else { return }
            let layer = MLNLineStyleLayer(identifier: Self.dayLayer, source: source)
            layer.predicate = NSPredicate(format: "day == %@", NSNumber(value: Int(picked.epochDay)))
            layer.lineColor = NSExpression(forConstantValue: UIColor(hex: picked.color) ?? .systemBlue)
            layer.lineWidth = Self.zoomWidth(Self.lineWidths.map { ($0.0, $0.1 + 1.5) })
            Self.round(layer)
            insert(layer, below: style.layer(withIdentifier: Self.placeLayer), style: style)
        }

        private func insert(_ layer: MLNStyleLayer, below: MLNStyleLayer?, style: MLNStyle) {
            if let below { style.insertLayer(layer, below: below) } else { style.addLayer(layer) }
            lineLayers.append(layer.identifier)
        }

        private static func round(_ layer: MLNLineStyleLayer) {
            layer.lineCap = NSExpression(forConstantValue: "round")
            layer.lineJoin = NSExpression(forConstantValue: "round")
        }

        /// Thin over a country, wide over a street. The lift is largest zoomed
        /// out, where a route has to read as a line at all, and small over a
        /// street. Only the on-screen maps: MapImageRenderer has its own widths.
        private static let lineWidths: [(Double, Double)] = [(2, 1.8), (6, 2.8), (10, 4), (14, 5.5), (18, 8)]
        private static let casingWidths: [(Double, Double)] = [(2, 3), (6, 4.4), (10, 6), (14, 7.8), (18, 10.8)]

        private static func zoomWidth(_ stops: [(Double, Double)]) -> NSExpression {
            var json: [Any] = ["interpolate", ["linear"], ["zoom"]]
            for (zoom, width) in stops {
                json.append(zoom)
                json.append(width)
            }
            return NSExpression(mglJSONObject: json)
        }

        private func drawPlaces(_ places: [PlaceItem], selected: String?, style: MLNStyle) {
            for id in [Self.placeLayer, "timeline-selected"] {
                if let layer = style.layer(withIdentifier: id) { style.removeLayer(layer) }
            }
            for id in [Self.placeSource, Self.selectedSource] {
                if let source = style.source(withIdentifier: id) { style.removeSource(source) }
            }
            guard !places.isEmpty else { return }

            let maxVisits = Double(places.map(\.visits).max() ?? 1)
            let features = places.map { p -> MLNPointFeature in
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude)
                // 4 pt for one visit up to 12 pt for the most visited, on a root scale.
                let radius = 4 + 8 * (Double(p.visits).squareRoot() / maxVisits.squareRoot())
                f.attributes = ["key": p.key, "radius": radius, "home": p.isHome || p.isWork ? 1 : 0]
                return f
            }
            let source = MLNShapeSource(identifier: Self.placeSource, features: features, options: nil)
            style.addSource(source)
            let circles = MLNCircleStyleLayer(identifier: Self.placeLayer, source: source)
            circles.circleRadius = NSExpression(forKeyPath: "radius")
            circles.circleColor = NSExpression(forConstantValue: UIColor(hex: Design.placeColorHex))
            circles.circleOpacity = NSExpression(forConstantValue: 0.8)
            circles.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            circles.circleStrokeWidth = NSExpression(forConstantValue: 1.5)
            style.addLayer(circles)

            if let key = selected, let p = places.first(where: { $0.key == key }) {
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude)
                let sel = MLNShapeSource(identifier: Self.selectedSource, features: [f], options: nil)
                style.addSource(sel)
                let ring = MLNCircleStyleLayer(identifier: "timeline-selected", source: sel)
                ring.circleRadius = NSExpression(forConstantValue: 16)
                ring.circleColor = NSExpression(forConstantValue: UIColor.clear)
                ring.circleStrokeColor = NSExpression(forConstantValue: UIColor(hex: Design.selectedPlaceHex))
                ring.circleStrokeWidth = NSExpression(forConstantValue: 3)
                style.addLayer(ring)
            }
        }

        /// Frame the picked day, the line or the places, with room around them.
        private func fit(_ map: MLNMapView) {
            memory.clear()
            let line = wanted.line
            var lats = line.latitudes
            var lons = line.longitudes
            if let day = wanted.selectedDay {
                let ranges = line.pieces.filter { $0.day == day }.map(\.range)
                if !ranges.isEmpty {
                    lats = ranges.flatMap { range in range.map { line.latitudes[$0] } }
                    lons = ranges.flatMap { range in range.map { line.longitudes[$0] } }
                }
            }
            if lats.isEmpty {
                lats = wanted.places.map(\.latitude)
                lons = wanted.places.map(\.longitude)
            }
            guard let minLat = lats.min(), let maxLat = lats.max(),
                  let minLon = lons.min(), let maxLon = lons.max() else { return }
            // A little more at the bottom: the attribution sits there.
            var pad = UIEdgeInsets(top: 40, left: 40, bottom: 48, right: 40)
            if let bottomOverlay {
                let safe = map.safeAreaInsets
                pad.top += safe.top
                pad.left += safe.left
                pad.right += safe.right
                pad.bottom += safe.bottom + bottomOverlay
            }
            if maxLat - minLat < 0.002 && maxLon - minLon < 0.002 {
                let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2)
                map.setCenter(center, zoomLevel: 14, animated: false)
                return
            }
            let bounds = MLNCoordinateBounds(
                sw: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
                ne: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon)
            )
            map.setVisibleCoordinateBounds(bounds, edgePadding: pad, animated: false, completionHandler: nil)
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let map = gesture.view as? MLNMapView, gesture.state == .ended,
                  map.style?.layer(withIdentifier: Self.placeLayer) != nil else { return }
            let point = gesture.location(in: map)
            let rect = CGRect(x: point.x - 12, y: point.y - 12, width: 24, height: 24)
            let hit = map.visibleFeatures(in: rect, styleLayerIdentifiers: [Self.placeLayer])
            let key = hit.first?.attribute(forKey: "key") as? String
            onSelectPlace(key)
        }
    }
}

extension UIColor {
    /// "#RRGGBB" from the shared core.
    convenience init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(
            red: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    init(hex: String) {
        self.init(uiColor: UIColor(hex: hex) ?? .systemBlue)
    }
}
