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
    /// The trip shown on the map. A trip marked for export has no tint: its
    /// filled mark says so, and the two states must not look alike.
    static let previewedRowOpacity = 0.16
    /// The map's floating day strip on a wide screen.
    static let dayStripMaxWidth: CGFloat = 640
    static let placeColorHex = "#D55E00"
    static let selectedPlaceHex = "#0072B2"
}

/// When the loaded page puts its map in a pane of its own beside the steps.
/// Decided on the width the window has, so a narrow Stage Manager or Split
/// View window stacks like a phone. The size class keeps out the widest
/// phones; a 13" iPad upright (1032 pt) stays stacked too.
enum PageLayout {
    /// As wide as a large phone: every row already fits that.
    static let sidebarMinWidth: CGFloat = 420
    static let sidebarMaxWidth: CGFloat = 500
    static let mapMinWidth: CGFloat = 620
    static let sidebarShare: CGFloat = 0.36

    static func isSplit(width: CGFloat, sizeClass: UserInterfaceSizeClass?) -> Bool {
        sizeClass == .regular && width >= sidebarMinWidth + mapMinWidth
    }

    static func sidebarWidth(pageWidth: CGFloat) -> CGFloat {
        min(sidebarMaxWidth, max(sidebarMinWidth, pageWidth * sidebarShare))
    }
}

extension View {
    /// The surface of a control floating over the map: Liquid Glass where the
    /// system has it, which also answers Reduce Transparency and Increase
    /// Contrast by itself, and the blur it replaces where it does not.
    @ViewBuilder
    func mapControlSurface(cornerRadius: CGFloat, interactive: Bool = false) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius)
        if #available(iOS 26, *) {
            glassEffect(Glass.regular.interactive(interactive), in: shape)
        } else {
            background(.regularMaterial, in: shape)
        }
    }
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
