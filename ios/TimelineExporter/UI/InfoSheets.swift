import SwiftUI
import UIKit

// MARK: Help

/// Where Timeline.json comes from and how it gets here. The Android steps
/// through system Settings do not exist on iPhone: Google Maps is the way.
struct HelpSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L.s("help_intro"))
                }
                Section("Google Maps") {
                    Steps(keys: (1...6).map { "help_method_maps_step\($0)" })
                    Button(L.s("help_method_maps_button")) {
                        // A universal link: the Google Maps app if it is installed.
                        openURL(URL(string: "https://maps.google.com/")!)
                    }
                }
                Section(L.s("help_import_title")) {
                    Steps(keys: ["help_import_step1", "help_import_step2"])
                }
                Section(L.s("help_notes_title")) {
                    ForEach(["help_note_file_location", "help_note_eu_csv", "help_note_slow_first",
                             "help_note_csv_local_time"], id: \.self) { key in
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

private struct Steps: View {
    let keys: [String]

    var body: some View {
        ForEach(Array(keys.enumerated()), id: \.offset) { index, key in
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("\(index + 1)")
                    .font(.callout.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 18)
                Text(L.s(key))
            }
        }
    }
}

// MARK: Feedback

/// The feature ballot and a free-text note, sent as a GitHub issue or an
/// email the user still edits. Nothing leaves the app by itself.
struct FeedbackSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var selected: Set<Int> = []
    @State private var other = ""
    @State private var diagnostics: String
    @State private var failure: String?

    init(formatName: String?, pointCount: Int?) {
        _diagnostics = State(initialValue: Feedback.diagnostics(formatName: formatName, pointCount: pointCount))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(Feedback.options.indices, id: \.self) { i in
                        Toggle(L.s(Feedback.options[i].key), isOn: Binding(
                            get: { selected.contains(i) },
                            set: { on in if on { selected.insert(i) } else { selected.remove(i) } }
                        ))
                    }
                } header: {
                    Text(L.s("feedback_intro")).textCase(nil)
                }
                Section(L.s("feedback_other_label")) {
                    TextField(L.s("feedback_other_label"), text: $other, axis: .vertical)
                        .lineLimit(3...8)
                }
                Section {
                    TextField(L.s("feedback_diagnostics_label"), text: $diagnostics, axis: .vertical)
                        .font(.footnote.monospaced())
                        .lineLimit(3...10)
                } header: {
                    Text(L.s("feedback_diagnostics_label"))
                } footer: {
                    Text(L.s("feedback_diagnostics_note"))
                }
                Section {
                    Button {
                        send(Feedback.githubURL(body: message(of: selected)), failure: "feedback_toast_no_browser")
                    } label: {
                        Label(L.s("feedback_send_github"), systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                    Button {
                        send(Feedback.mailURL(body: message(of: selected)), failure: "feedback_toast_no_email")
                    } label: {
                        Label(L.s("feedback_send_email"), systemImage: "envelope")
                    }
                } footer: {
                    Text(L.s("feedback_channel_hint"))
                }
            }
            .navigationTitle(L.s("feedback_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L.s("feedback_dismiss")) { dismiss() }
                }
            }
            .alert(failure ?? "", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
                Button("OK") { failure = nil }
            }
        }
    }

    private func message(of selected: Set<Int>) -> String {
        Feedback.body(selected: selected.sorted(), other: other, diagnostics: diagnostics)
    }

    private func send(_ url: URL?, failure key: String) {
        guard let url else { return }
        UIApplication.shared.open(url) { ok in
            if !ok { failure = L.s(key) }
        }
    }
}

/// The text side of feedback, kept apart from the view for the tests.
enum Feedback {
    static let email = "nikitapetrovapps@gmail.com"
    static let newIssue = "https://github.com/NikkittaP/timeline-exporter/issues/new"

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

    /// English and machine-ish: it goes to an issue tracker, not to the user.
    static func diagnostics(formatName: String?, pointCount: Int?) -> String {
        var lines = [
            "App: \(appVersion) iOS",
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
        let diag = diagnostics.trimmingCharacters(in: .whitespacesAndNewlines)
        if !diag.isEmpty {
            if !out.isEmpty { out += "\n" }
            out += "---\n" + diag + "\n"
        }
        return out
    }

    static func githubURL(body: String) -> URL? {
        var c = URLComponents(string: newIssue)
        c?.queryItems = [
            URLQueryItem(name: "title", value: L.s("feedback_message_subject")),
            URLQueryItem(name: "body", value: body),
        ]
        return c?.url
    }

    /// mailto with subject and body percent-encoded by hand: URLComponents
    /// leaves "+" and "&" alone, which mail apps read as a space and a split.
    static func mailURL(body: String) -> URL? {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: allowed) ?? "" }
        return URL(string: "mailto:\(email)?subject=\(enc(L.s("feedback_message_subject")))&body=\(enc(body))")
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
