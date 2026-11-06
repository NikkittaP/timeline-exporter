#!/usr/bin/env python3
"""Tests for ci_annotate.py. Run: python3 tools/test_ci_annotate.py"""
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ci_annotate  # noqa: E402

XCODE_LOG = """\
CompileSwift normal arm64 /src/ios/TimelineExporter/Shared/SharedCheck.swift
/src/ios/TimelineExporter/Shared/SharedCheck.swift:17:22: error: cannot find 'TimelineParserKt' in scope
/src/ios/TimelineExporter/Shared/SharedCheck.swift:17:22: error: cannot find 'TimelineParserKt' in scope
Undefined symbols for architecture arm64:
  "_kfun:foo", referenced from:
ld: symbol(s) not found for architecture arm64
clang: error: linker command failed with exit code 1 (use -v to see invocation)
Test Case '-[TimelineExporterTests.SharedLinkTests testBundledSampleParsesThroughTheSharedCore]' started.
/src/ios/TimelineExporterTests/SharedLinkTests.swift:9: error: -[TimelineExporterTests.SharedLinkTests testBundledSampleParsesThroughTheSharedCore] : XCTAssertEqual failed: ("A") is not equal to ("B")
Test Case '-[TimelineExporterTests.SharedLinkTests testBundledSampleParsesThroughTheSharedCore]' failed (0.010 seconds).
** TEST FAILED **
"""


class XcodebuildParsing(unittest.TestCase):
    def setUp(self):
        self.lines = XCODE_LOG.splitlines()

    def test_errors_are_unique_and_include_link_errors(self):
        errors = ci_annotate.xcodebuild_errors(self.lines)
        self.assertEqual(1, sum("cannot find 'TimelineParserKt'" in e for e in errors))
        self.assertTrue(any(e.startswith("Undefined symbols") for e in errors))
        self.assertTrue(any(e.startswith("ld: ") for e in errors))
        self.assertTrue(any(e.startswith("clang: error") for e in errors))
        self.assertFalse(any("XCTAssertEqual" in e for e in errors), "assertions belong to test failures")

    def test_failed_tests_carry_the_assertion(self):
        failures = ci_annotate.xcodebuild_test_failures(self.lines)
        self.assertEqual(2, len(failures))
        self.assertTrue(any("XCTAssertEqual failed" in f for f in failures))
        self.assertTrue(any(f.startswith("Test Case '-[") and f.endswith("seconds).") for f in failures))

    def test_cli_reads_xcodebuild_log_without_gradle_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "xcodebuild.log")
            with open(log, "w", encoding="utf-8") as f:
                f.write(XCODE_LOG)
            out = io.StringIO()
            with redirect_stdout(out):
                ci_annotate.main([os.path.join(tmp, "missing-gradle.log"), tmp, "--xcodebuild", log])
            text = out.getvalue()
        self.assertIn("title=xcodebuild errors", text)
        self.assertIn("title=XCTest failures", text)
        self.assertNotIn("No Gradle log", text)

    def test_unrecognised_failure_shows_the_log_tail(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = os.path.join(tmp, "xcodebuild.log")
            with open(log, "w", encoding="utf-8") as f:
                f.write("line 1\nsomething odd happened\n** BUILD FAILED **\n")
            out = io.StringIO()
            with redirect_stdout(out):
                ci_annotate.main([os.path.join(tmp, "missing-gradle.log"), "--xcodebuild", log])
        self.assertIn("something odd happened", out.getvalue())

    def test_ld_warnings_are_not_errors(self):
        errors = ci_annotate.xcodebuild_errors(
            ["ld: warning: ignoring duplicate libraries: '-lc++'", "ld: symbol(s) not found"])
        self.assertEqual(["ld: symbol(s) not found"], errors)

    def test_succeeded_log_produces_no_annotation(self):
        for marker in ("** BUILD SUCCEEDED **", "** TEST SUCCEEDED **",
                       "** ARCHIVE SUCCEEDED **", "** EXPORT SUCCEEDED **"):
            with tempfile.TemporaryDirectory() as tmp:
                log = os.path.join(tmp, "xcodebuild.log")
                with open(log, "w", encoding="utf-8") as f:
                    f.write("ld: warning: ignoring duplicate libraries\nCompile ok\n" + marker + "\n")
                out = io.StringIO()
                with redirect_stdout(out):
                    ci_annotate.main([os.path.join(tmp, "missing-gradle.log"), tmp, "--xcodebuild", log])
            self.assertNotIn("xcodebuild log tail", out.getvalue(), marker)
            self.assertNotIn("title=xcodebuild", out.getvalue(), marker)


if __name__ == "__main__":
    unittest.main()
