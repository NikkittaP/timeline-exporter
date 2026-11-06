package io.github.nikkittap.timelineexporter.parser

import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.InputStream

// JVM-only InputStream entry points, kept so the Android app calls the parser
// exactly as it did when it ran on Jackson. Each is a thin wrapper over the
// kotlinx-io Source overload in TimelineParser.kt, which is the one shared
// code (and later iOS) calls.

/**
 * Parse straight from a stream (used for real files). The stream is closed
 * when the parse finishes or fails, as it was with Jackson. Bytes are pulled
 * from [input] a buffer segment (8 KiB) at a time, so a counting wrapper
 * around it still sees read progress advance steadily through the file.
 */
fun parseTimeline(input: InputStream, onProgress: (ParserStage) -> Unit = {}): ParsedTimeline =
    parseTimeline(input.asSource().buffered(), onProgress)

/** See the Source overload. Does not close [input]. */
fun findKeyOffsets(input: InputStream): KeyOffsets =
    findKeyOffsets(input.asSource().buffered())
