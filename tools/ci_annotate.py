#!/usr/bin/env python3
"""Turn a failed Gradle run into GitHub annotations.

Job logs on GitHub are visible only to signed-in users, but annotations are
public and readable through the REST API (check-runs/{id}/annotations). This
script prints the failure as annotations so it can be read without a login.

Usage: ci_annotate.py GRADLE_LOG [TEST_RESULTS_ROOT ...] [--xcodebuild LOG ...]

GitHub keeps at most 10 error annotations per step, so the order is: Gradle's
"What went wrong" block, Kotlin compile errors, then failed tests.
xcodebuild logs add compile/link errors and failed XCTests; a log with neither adds its last lines.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 10
MAX_LEN = 3500


def escape(text: str) -> str:
    text = text[:MAX_LEN]
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(title: str, message: str) -> None:
    prop = escape(title).replace(":", "%3A").replace(",", "%2C")
    print(f"::error title={prop}::{escape(message)}")


def what_went_wrong(lines):
    out = []
    for i, line in enumerate(lines):
        if line.startswith("* What went wrong:"):
            block = []
            for nxt in lines[i + 1:]:
                if nxt.startswith("* Try:") or nxt.startswith("* Exception is:"):
                    break
                block.append(nxt)
            out.append("\n".join(block).strip())
    return out


def compile_errors(lines):
    return [l for l in lines if re.match(r"^e: ", l)][:20]


def failed_tests(roots):
    found = []
    for root in roots:
        for path in glob.glob(os.path.join(root, "**", "TEST-*.xml"), recursive=True):
            try:
                tree = ET.parse(path)
            except ET.ParseError:
                continue
            for case in tree.iter("testcase"):
                for tag in ("failure", "error"):
                    node = case.find(tag)
                    if node is not None:
                        name = f"{case.get('classname')}.{case.get('name')}"
                        body = (node.get("message") or "") + "\n" + (node.text or "")
                        found.append((name, body.strip()))
    return found


XCODE_ERROR = re.compile(r"(: error: |^error: |^clang: error|^ld: |^Undefined symbols)")
XCTEST_LINE = re.compile(r"(^Test Case '-\[.*\]' failed|: error: -\[.*\] : )")


def xcodebuild_errors(lines):
    """Compile and link errors, each once, in log order (xcodebuild repeats them)."""
    seen, out = set(), []
    for line in lines:
        line = line.strip()
        if XCODE_ERROR.search(line) and not XCTEST_LINE.search(line) and line not in seen:
            seen.add(line)
            out.append(line)
    return out[:30]


def xcodebuild_test_failures(lines):
    return [l.strip() for l in lines if XCTEST_LINE.search(l.strip())][:30]


def read_lines(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return []


def main(argv=None) -> None:
    args = list(sys.argv[1:] if argv is None else argv)
    xcode_logs = []
    while "--xcodebuild" in args:
        i = args.index("--xcodebuild")
        xcode_logs.append(args[i + 1])
        del args[i:i + 2]
    log_path = args[0]
    roots = args[1:] or ["."]
    lines = read_lines(log_path)

    budget = MAX_ANNOTATIONS
    for block in what_went_wrong(lines)[:2]:
        emit("Gradle failure", block)
        budget -= 1
    errors = compile_errors(lines)
    if errors and budget > 0:
        emit(f"Kotlin compile errors ({len(errors)})", "\n".join(errors))
        budget -= 1

    xcode_seen = False
    for path in xcode_logs:
        xlines = read_lines(path)
        if not xlines or budget <= 0:
            continue
        xcode_seen = True
        name = os.path.basename(path)
        xerrors = xcodebuild_errors(xlines)
        xtests = xcodebuild_test_failures(xlines)
        if xerrors:
            emit(f"xcodebuild errors ({name})", "\n".join(xerrors))
            budget -= 1
        if xtests and budget > 0:
            emit(f"XCTest failures ({name})", "\n".join(xtests))
            budget -= 1
        if not (xerrors or xtests) and budget > 0:
            emit(f"xcodebuild log tail ({name})", "\n".join(xlines[-40:]))
            budget -= 1

    tests = failed_tests(roots)
    for name, body in tests[: max(budget - 1, 0)]:
        emit(f"Test failed {name}", body)
    if len(tests) > max(budget - 1, 0) and budget > 0:
        rest = [n for n, _ in tests[max(budget - 1, 0):]]
        emit(f"{len(rest)} more failed tests", "\n".join(rest))
    if not (lines or tests or xcode_seen):
        emit("CI failure", "No Gradle log and no test results were found.")


if __name__ == "__main__":
    main()
