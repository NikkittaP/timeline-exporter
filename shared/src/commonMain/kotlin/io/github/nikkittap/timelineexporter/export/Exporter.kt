package io.github.nikkittap.timelineexporter.export

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
     * Write the file contents into [out].
     *
     * Streaming rather than returning a String, because the string was the
     * single most expensive thing this app did. A 284,000-point track is a
     * ~30 MB GPX, and building that in memory cost an 85 MB StringBuilder, a
     * 60 MB copy of it (a Java char is two bytes) and a 30 MB byte array —
     * 175 MB of garbage for a 30 MB file, on top of the ~96 MB the parsed
     * timeline already holds. Written straight into the destination's own
     * buffered writer, the cost is the buffer.
     *
     * Implementations must therefore append as they go and never assemble the
     * whole document first; [StreamingExportTest] enforces it.
     *
     * [content] is either a track or a list of places — every implementation
     * must handle both, and an exhaustive `when` over the sealed type is what
     * guarantees a new content kind cannot be added without each format being
     * updated to write it.
     *
     * [name] is a human label embedded in the output where the format supports
     * one (the track's `<name>`, the KML `<Document><name>`). CSV ignores it.
     */
    fun export(content: ExportContent, name: String, out: Appendable)
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
