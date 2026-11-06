package io.github.nikkittap.timelineexporter

import io.github.nikkittap.timelineexporter.parser.parseTimeline
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS app bundles ios/TimelineExporter/Resources/sample-timeline.json and its
 * XCTest (ios/TimelineExporterTests/SharedLinkTests.swift) expects these counts.
 * Checking them here keeps the two in step without a Mac.
 */
class IosSampleTimelineTest {
    @Test
    fun `the iOS sample parses to three points in one segment`() {
        // Gradle runs tests with the module directory (shared/) as working directory.
        val json = File("../ios/TimelineExporter/Resources/sample-timeline.json").readText()
        val parsed = parseTimeline(json)
        assertEquals(3, parsed.pathPoints.size)
        assertEquals(1, parsed.totalSegments)
    }
}
