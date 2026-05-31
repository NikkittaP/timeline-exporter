package io.github.nikkittap.timelineexporter.export

import io.github.nikkittap.timelineexporter.parser.PathPoint

/**
 * Strategy interface for converting parsed Timeline data into an external file
 * format. Each format is one implementation; the UI iterates [AllExporters]
 * to build its format picker.
 *
 * Adding a new format = create one new object implementing this interface
 * and add it to [AllExporters]. UI and ViewModel changes: zero.
 */
interface Exporter {
    /** User-facing name shown in menus, e.g. "GPX". */
    val displayName: String

    /** File extension WITHOUT the leading dot, e.g. "gpx". */
    val fileExtension: String

    /** MIME type passed to SAF CreateDocument, e.g. "application/gpx+xml". */
    val mimeType: String

    /**
     * Produce the file contents as a UTF-8 String.
     * [trackName] is a human label embedded in the output (where the format
     * supports it). CSV ignores it; XML/JSON-ish formats embed it.
     */
    fun export(points: List<PathPoint>, trackName: String): String
}

/**
 * The full set of formats the app supports. Order = order shown in UI menu.
 */
val AllExporters: List<Exporter> = listOf(
    GpxExporter,
    KmlExporter,
    GeoJsonExporter,
    CsvExporter,
)
