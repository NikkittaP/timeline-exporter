import MapLibre
import SwiftUI
import UIKit

/// What the map shows: a line (the track or trips) or the places.
struct MapContent: Equatable {
    var line = MapLine()
    var places: [PlaceItem] = []
    var selectedPlace: String?
}

/// The preview map: MapLibre with the app's OpenFreeMap styles. Lines are
/// drawn one layer per colour (at most the 21 day colours), places as
/// circles sized by visits. Bumping [fitToken] frames everything again.
struct TimelineMapView: UIViewRepresentable {
    let content: MapContent
    let fitToken: Int
    let onSelectPlace: (String?) -> Void

    @Environment(\.colorScheme) private var colorScheme

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelectPlace: onSelectPlace)
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

        context.coordinator.scheme = colorScheme
        context.coordinator.show(content, on: map, fit: true)
        context.coordinator.fitToken = fitToken
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        c.onSelectPlace = onSelectPlace
        if c.scheme != colorScheme {
            c.scheme = colorScheme
            // A new style drops the sources; didFinishLoading puts them back.
            map.styleURL = Self.styleURL(colorScheme)
        }
        let refit = c.fitToken != fitToken
        c.fitToken = fitToken
        c.show(content, on: map, fit: refit)
    }

    static func styleURL(_ scheme: ColorScheme) -> URL? {
        Bundle.main.url(forResource: scheme == .dark ? "map_style_dark" : "map_style_light", withExtension: "json")
    }

    // MARK: Coordinator

    final class Coordinator: NSObject, MLNMapViewDelegate {
        var onSelectPlace: (String?) -> Void
        var scheme: ColorScheme?
        var fitToken = 0
        private var shown: MapContent?
        private var wanted = MapContent()
        private var fitPending = false
        private var lineLayers: [String] = []

        private static let lineSource = "timeline-line"
        private static let placeSource = "timeline-places"
        private static let selectedSource = "timeline-selected"
        private static let placeLayer = "timeline-places"
        static let trackColor = "#1565C0"

        init(onSelectPlace: @escaping (String?) -> Void) {
            self.onSelectPlace = onSelectPlace
        }

        /// Draw [content]; the first content and a bump of the fit token frame it.
        func show(_ content: MapContent, on map: MLNMapView, fit: Bool) {
            let first = shown == nil && wanted == MapContent()
            let changedData = content.line != wanted.line || content.places != wanted.places
            wanted = content
            if fit || first || changedData { fitPending = true }
            guard let style = map.style else { return }
            apply(to: style, map: map)
        }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            shown = nil
            lineLayers = []
            apply(to: style, map: mapView)
        }

        private func apply(to style: MLNStyle, map: MLNMapView) {
            if shown?.line != wanted.line { drawLine(wanted.line, style: style) }
            if shown?.places != wanted.places || shown?.selectedPlace != wanted.selectedPlace {
                drawPlaces(wanted.places, selected: wanted.selectedPlace, style: style)
            }
            if let key = wanted.selectedPlace, key != shown?.selectedPlace,
               let p = wanted.places.first(where: { $0.key == key }) {
                let center = CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude)
                map.setCenter(center, zoomLevel: max(map.zoomLevel, 13), animated: true)
                fitPending = false
            }
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
                f.attributes = ["color": piece.color.isEmpty ? Self.trackColor : piece.color]
                features.append(f)
            }
            let source = MLNShapeSource(identifier: Self.lineSource, features: features, options: nil)
            style.addSource(source)

            let below = style.layer(withIdentifier: Self.placeLayer)
            let casing = MLNLineStyleLayer(identifier: "timeline-casing", source: source)
            casing.lineColor = NSExpression(forConstantValue: UIColor.white.withAlphaComponent(0.85))
            casing.lineWidth = NSExpression(forConstantValue: 6)
            Self.round(casing)
            insert(casing, below: below, style: style)

            let colors = Array(Set(features.compactMap { $0.attributes["color"] as? String })).sorted()
            for color in colors {
                let layer = MLNLineStyleLayer(identifier: "timeline-line-\(color)", source: source)
                layer.predicate = NSPredicate(format: "color == %@", color)
                layer.lineColor = NSExpression(forConstantValue: UIColor(hex: color) ?? .systemBlue)
                layer.lineWidth = NSExpression(forConstantValue: 3.5)
                Self.round(layer)
                insert(layer, below: below, style: style)
            }
        }

        private func insert(_ layer: MLNStyleLayer, below: MLNStyleLayer?, style: MLNStyle) {
            if let below { style.insertLayer(layer, below: below) } else { style.addLayer(layer) }
            lineLayers.append(layer.identifier)
        }

        private static func round(_ layer: MLNLineStyleLayer) {
            layer.lineCap = NSExpression(forConstantValue: "round")
            layer.lineJoin = NSExpression(forConstantValue: "round")
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
            circles.circleColor = NSExpression(forConstantValue: UIColor(hex: "#D55E00"))
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
                ring.circleStrokeColor = NSExpression(forConstantValue: UIColor(hex: "#0072B2"))
                ring.circleStrokeWidth = NSExpression(forConstantValue: 3)
                style.addLayer(ring)
            }
        }

        /// Frame the line, or the places, with some room around them.
        private func fit(_ map: MLNMapView) {
            var lats = wanted.line.latitudes
            var lons = wanted.line.longitudes
            if lats.isEmpty {
                lats = wanted.places.map(\.latitude)
                lons = wanted.places.map(\.longitude)
            }
            guard let minLat = lats.min(), let maxLat = lats.max(),
                  let minLon = lons.min(), let maxLon = lons.max() else { return }
            let pad = UIEdgeInsets(top: 32, left: 32, bottom: 32, right: 32)
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
