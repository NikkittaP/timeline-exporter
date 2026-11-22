package io.github.nikkittap.timelineexporter.ui

/**
 * The folder a saved export went to, as the Files app shows it, so the
 * message after saving can say where to look. The save dialog only hands
 * back a document URI; its document id names the folder for local storage
 * ("primary:Download/Timeline.gpx" → "Download") and for the Downloads
 * provider's raw paths. Every other provider (Drive, msf: ids, the top of a
 * storage volume) gives opaque ids, and then this is null and the message
 * shows the file name alone.
 */
internal fun savedFolderName(authority: String?, documentId: String?): String? {
    if (documentId == null) return null
    val path = when (authority) {
        "com.android.externalstorage.documents" -> documentId.substringAfter(':', "")
        "com.android.providers.downloads.documents" ->
            if (documentId.startsWith("raw:")) documentId.removePrefix("raw:") else return null
        else -> return null
    }
    val folder = path.trimEnd('/').substringBeforeLast('/', "")
    return folder.substringAfterLast('/').takeIf { it.isNotBlank() }
}
