package io.github.nikitapetroff.timelineexporter.export

/**
 * Escape the five characters that have special meaning in XML attribute or
 * element text. Short-circuits on the common case where nothing needs it.
 *
 * Shared between GpxExporter and KmlExporter — both emit XML.
 */
internal fun escapeXml(s: String): String {
    if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
