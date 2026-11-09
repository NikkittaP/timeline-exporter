import SwiftUI

/// The few values the screens share, so a card, a chip and a map corner agree.
/// Colours stay semantic (system fills, the accent colour); only the place and
/// selected-place colours are fixed, because they mean the same on the map.
enum Design {
    static let cardRadius: CGFloat = 12
    static let controlRadius: CGFloat = 10
    /// Apple's minimum comfortable touch target.
    static let minTarget: CGFloat = 44
    static let mapHeightCompact: CGFloat = 340
    static let mapHeightRegular: CGFloat = 460
    static let selectedRowOpacity = 0.12
    /// A trip ticked for export: a hint of tint, the filled mark says the rest.
    static let markedRowOpacity = 0.07
    /// The trip shown on the map, a step stronger than a ticked one.
    static let previewedRowOpacity = 0.16
    /// The map's floating day strip on a wide screen.
    static let dayStripMaxWidth: CGFloat = 640
    static let placeColorHex = "#D55E00"
    static let selectedPlaceHex = "#0072B2"
}

/// A step's heading in the Form: readable as a heading, not a grey caption.
struct StepHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.headline)
            .foregroundStyle(.primary)
            .textCase(nil)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A switch's or a row's title with its explanation under it.
struct OptionLabel: View {
    let title: String
    let hint: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(hint).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// One choice of a short row of presets.
struct PresetChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .monospacedDigit()
                .frame(minHeight: 30)
        }
        .buttonStyle(.bordered)
        .tint(selected ? Color.accentColor : Color.secondary)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A figure with its name: the file summary's Points, Days and Distance.
struct MetricTile: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.title3.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
