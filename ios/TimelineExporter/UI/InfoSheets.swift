import SwiftUI
import UIKit

// MARK: Help

/// Where the Timeline file comes from and how it gets here, for someone who
/// has never exported anything: what must already be true (in bold), saving
/// the file from Google Maps straight into this app through the share sheet,
/// the Save to Files fallback, turning Timeline on, and what to do when a step
/// is missing. Saving comes before turning Timeline on because most people who
/// open Help already have it on. The Android steps through system Settings do
/// not exist on iPhone; Google Maps is the way (support.google.com/maps/answer/6258979).
struct HelpSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section(L.s("ios_requirements_title")) {
                    Requirements(withDevice: true)
                }
                Section(L.s("ios_export_title")) {
                    NumberedSteps(keys: ["ios_maps_profile", "ios_export_step2", "ios_export_step3",
                                         "ios_export_step4"])
                    Button(L.s("help_method_maps_button")) {
                        // A universal link: the Google Maps app if it is installed.
                        openURL(URL(string: "https://maps.google.com/")!)
                    }
                }
                Section(L.s("ios_import_title")) {
                    NumberedSteps(keys: ["ios_import_fallback", "help_import_step1", "help_import_step2"])
                }
                Section {
                    NumberedSteps(keys: ["ios_maps_profile", "ios_turn_on_step2", "ios_turn_on_step3",
                                         "ios_turn_on_step4"])
                } header: {
                    Text(L.s("ios_turn_on_title"))
                } footer: {
                    Text(L.s("ios_turn_on_note"))
                }
                Section(L.s("ios_trouble_title")) {
                    ForEach(["ios_trouble_no_export", "ios_trouble_alt_path", "ios_trouble_few_days",
                             "ios_trouble_android"], id: \.self) { key in
                        Text(L.md(key)).font(.callout)
                    }
                }
                Section(L.s("help_notes_title")) {
                    ForEach(["help_note_file_location", "help_note_slow_first"], id: \.self) { key in
                        Text(L.s(key)).font(.callout)
                    }
                }
            }
            .navigationTitle(L.s("help_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("help_dismiss")) { dismiss() }
                }
            }
        }
    }
}

// MARK: Help & Feedback

/// The two ways to write to the developer, both under Help & Feedback.
enum FeedbackKind: String, Identifiable {
    /// The idea ballot and a note of one's own. No technical details.
    case feature
    /// What went wrong, with the technical details the user can leave out.
    case problem

    var id: String { rawValue }

    var titleKey: String { self == .feature ? "ios_suggest_title" : "ios_problem_title" }
    var symbol: String { self == .feature ? "lightbulb" : "exclamationmark.bubble" }
}

/// Suggest a Feature or Report a Problem. Nothing leaves the app by itself:
/// the button opens a draft in the mail app, which the user reads, edits and
/// sends there. No analytics, no backend.
struct FeedbackSheet: View {
    @Environment(\.dismiss) private var dismiss
    let kind: FeedbackKind
    @State private var selected: Set<Int> = []
    @State private var note = ""
    @State private var includeDetails = true
    @State private var noMailApp = false
    private let diagnostics: String

    init(kind: FeedbackKind, formatName: String?, pointCount: Int?) {
        self.kind = kind
        diagnostics = Feedback.diagnostics(formatName: formatName, pointCount: pointCount)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FeedbackHeader(kind: kind)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))

                switch kind {
                case .feature: featureSections
                case .problem: problemSections
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) { actionBar }
            .navigationTitle(L.s(kind.titleKey))
            .navigationBarTitleDisplayMode(.inline)
            // The header says it in large type; the bar keeps it for VoiceOver
            // and for when the header has scrolled away.
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("feedback_dismiss")) { dismiss() }
                }
            }
            .alert(L.s("feedback_toast_no_email"), isPresented: $noMailApp) {
                Button(L.s("ios_feedback_copy_address")) {
                    UIPasteboard.general.string = Feedback.email
                }
                Button("OK", role: .cancel) {}
            } message: {
                Text(Feedback.email)
            }
        }
    }

    @ViewBuilder private var featureSections: some View {
        Section {
            ForEach(Feedback.options.indices, id: \.self) { i in
                CheckRow(title: L.s(Feedback.options[i].key), checked: selected.contains(i)) {
                    if selected.contains(i) { selected.remove(i) } else { selected.insert(i) }
                }
            }
        } header: {
            Text(L.s("ios_suggest_ideas"))
        }
        Section {
            TextField(L.s("ios_suggest_other_placeholder"), text: $note, axis: .vertical)
                .lineLimit(3...8)
        } header: {
            Text(L.s("ios_suggest_other"))
        }
    }

    @ViewBuilder private var problemSections: some View {
        Section {
            TextField(L.s("ios_problem_placeholder"), text: $note, axis: .vertical)
                .lineLimit(4...10)
        } header: {
            Text(L.s("ios_problem_label"))
        }
        Section {
            Toggle(L.s("ios_problem_include_details"), isOn: $includeDetails.animation())
            if includeDetails {
                Text(diagnostics)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } footer: {
            Text(L.s("ios_problem_details_note"))
        }
    }

    /// The one action, pinned under the form with the promise beneath it.
    private var actionBar: some View {
        VStack(spacing: 8) {
            Button(action: compose) {
                Label(L.s("ios_feedback_compose"), systemImage: "envelope")
                    .font(.body.weight(.semibold))
                    .actionLabelWidth()
            }
            .prominentActionStyle()
            .controlSize(.large)
            .disabled(!canCompose)
            .accessibilityIdentifier("feedbackCompose")
            Text(L.s("ios_feedback_privacy"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    /// An idea needs something ticked or written; a problem may be described
    /// in the mail app just as well.
    private var canCompose: Bool {
        switch kind {
        case .feature: !selected.isEmpty || !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .problem: true
        }
    }

    private func compose() {
        let url: URL?
        switch kind {
        case .feature:
            url = Feedback.mailURL(
                subject: L.s("feedback_message_subject"),
                body: Feedback.body(selected: selected.sorted(), other: note, diagnostics: Feedback.appLine)
            )
        case .problem:
            url = Feedback.mailURL(
                subject: L.s("ios_problem_subject"),
                body: Feedback.problemBody(text: note, diagnostics: includeDetails ? diagnostics : "")
            )
        }
        guard let url else { return }
        UIApplication.shared.open(url) { ok in
            if !ok { noMailApp = true }
        }
    }
}

/// The sheet's own icon, title and a word from the developer.
private struct FeedbackHeader: View {
    let kind: FeedbackKind

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: kind.symbol)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.tint)
                .frame(width: 64, height: 64)
                .modifier(HeaderBadge())
                .accessibilityHidden(true)
            Text(L.s(kind.titleKey))
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(L.s(kind == .feature ? "ios_suggest_intro" : "ios_problem_intro"))
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

/// A tinted disc behind the header icon: glass where the system has it.
private struct HeaderBadge: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, *) {
            content.glassEffect(.regular.tint(Color.accentColor.opacity(0.18)), in: Circle())
        } else {
            content.background(Color.accentColor.opacity(0.14), in: Circle())
        }
    }
}

/// One idea of the ballot: a row that is ticked, as in the system's own
/// multiple-choice lists, rather than a switch.
private struct CheckRow: View {
    let title: String
    let checked: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 12) {
                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(checked ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                    .contentTransition(.symbolEffect(.replace))
                    .accessibilityHidden(true)
                Text(title)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: checked)
        .accessibilityAddTraits(checked ? .isSelected : [])
    }
}

/// The text side of feedback, kept apart from the view for the tests.
enum Feedback {
    static let email = "nikitapetrovapps@gmail.com"

    /// [outgoing] is English on purpose, character for character the English
    /// label, so a request matches its row of FEEDBACK.md whatever the language.
    struct Option {
        let key: String
        let outgoing: String
    }

    static let options = [
        Option(key: "feedback_option_file_per_trip", outgoing: "A separate file per trip instead of one large file"),
        Option(key: "feedback_option_trip_distance", outgoing: "Choose how far from home counts as a trip"),
        Option(key: "feedback_option_stats", outgoing: "Monthly and yearly totals"),
        Option(key: "feedback_option_place_names", outgoing: "Real names for places that show only coordinates"),
        Option(key: "feedback_option_area", outgoing: "Keep only points inside an area on the map"),
        Option(key: "feedback_option_elevation_speed", outgoing: "Elevation and speed in GPX"),
        Option(key: "feedback_option_merge", outgoing: "Merging several Timeline files"),
        Option(key: "feedback_option_photo_geotag", outgoing: "Geotagging photos by time"),
        Option(key: "feedback_option_owner", outgoing: "Show which account the export came from"),
    ]

    static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    /// All a feature request carries besides the user's words: which build
    /// it was asked of.
    static var appLine: String { "App: \(appVersion) iOS" }

    /// English and machine-ish: it goes to the developer, not to the user.
    static func diagnostics(formatName: String?, pointCount: Int?) -> String {
        var lines = [
            appLine,
            "Device: \(deviceModel) · iOS \(UIDevice.current.systemVersion)",
            "Locale: \(Locale.current.identifier(.bcp47))",
        ]
        if let formatName { lines.append("File format: \(formatName)") }
        if let pointCount { lines.append("Points: \(pointCount)") }
        return lines.joined(separator: "\n")
    }

    static func body(selected: [Int], other: String, diagnostics: String) -> String {
        var out = ""
        // In the order the options are listed, whatever order they were ticked in.
        let chosen = Set(selected).sorted().filter { options.indices.contains($0) }.map { options[$0] }
        if !chosen.isEmpty {
            out += L.s("feedback_body_requested") + "\n"
            for o in chosen { out += "- \(o.outgoing)\n" }
        }
        let note = other.trimmingCharacters(in: .whitespacesAndNewlines)
        if !note.isEmpty {
            if !out.isEmpty { out += "\n" }
            out += L.s("feedback_body_notes") + "\n" + note + "\n"
        }
        return out + footer(diagnostics, after: out)
    }

    /// The description, then the details if the user kept them. An empty
    /// description leaves the label, a place to write in the mail app.
    static func problemBody(text: String, diagnostics: String) -> String {
        let note = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let out = L.s("ios_problem_body_label") + "\n" + (note.isEmpty ? "\n" : note + "\n")
        return out + footer(diagnostics, after: out)
    }

    private static func footer(_ diagnostics: String, after out: String) -> String {
        let diag = diagnostics.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !diag.isEmpty else { return "" }
        return (out.isEmpty ? "" : "\n") + "---\n" + diag + "\n"
    }

    /// mailto with subject and body percent-encoded by hand: URLComponents
    /// leaves "+" and "&" alone, which mail apps read as a space and a split.
    static func mailURL(subject: String, body: String) -> URL? {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        return URL(string: "mailto:\(email)?subject=\(enc(subject))&body=\(enc(body))")
    }

    /// "iPhone17,2" rather than "iPhone": the marketing name is not on the device.
    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        let id = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return id.isEmpty ? UIDevice.current.model : id
    }
}

// MARK: What's new

/// One release's notes: a string key holding one item per line.
struct WhatsNewEntry: Equatable {
    let version: String
    let key: String
}

/// Which notes to show, the iOS twin of the Android WhatsNew: after an update,
/// every release since the last one whose notes were seen; nothing on a fresh
/// install. iOS releases have their own numbers, from 1.0.0.
enum WhatsNew {
    /// Newest first. Empty until the first update after 1.0.0.
    static let notes: [WhatsNewEntry] = []

    static let seenKey = "whats_new_seen"

    static func compare(_ a: String, _ b: String) -> Int {
        let pa = a.split(separator: ".").map { Int($0) ?? 0 }
        let pb = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(pa.count, pb.count) {
            let x = i < pa.count ? pa[i] : 0
            let y = i < pb.count ? pb[i] : 0
            if x != y { return x < y ? -1 : 1 }
        }
        return 0
    }

    /// Newer than [seen], not newer than [current], newest first. No [seen]
    /// is a fresh install: nothing is new to it.
    static func pending(_ entries: [WhatsNewEntry], seen: String?, current: String) -> [WhatsNewEntry] {
        guard let seen else { return [] }
        return history(entries, current: current).filter { compare($0.version, seen) > 0 }
    }

    static func history(_ entries: [WhatsNewEntry], current: String) -> [WhatsNewEntry] {
        entries.filter { compare($0.version, current) <= 0 }.sorted { compare($0.version, $1.version) > 0 }
    }

    static var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }
}

struct WhatsNewSheet: View {
    @Environment(\.dismiss) private var dismiss
    let entries: [WhatsNewEntry]
    let title: String

    var body: some View {
        NavigationStack {
            List(entries, id: \.version) { entry in
                Section(entry.version) {
                    ForEach(L.s(entry.key).split(separator: "\n").map(String.init), id: \.self) { line in
                        Text(line)
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L.s("whats_new_close")) { dismiss() }
                }
            }
        }
    }
}
