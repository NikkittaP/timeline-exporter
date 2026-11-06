#!/usr/bin/env python3
"""Turn a failed Gradle run into GitHub annotations.

Job logs on GitHub are visible only to signed-in users, but annotations are
public and readable through the REST API (check-runs/{id}/annotations). This
script prints the failure as annotations so it can be read without a login.

Usage: ci_annotate.py GRADLE_LOG [TEST_RESULTS_ROOT ...]

GitHub keeps at most 10 error annotations per step, so the order is: Gradle's
"What went wrong" block, Kotlin compile errors, then failed tests.
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


def main() -> None:
    log_path = sys.argv[1]
    roots = sys.argv[2:] or ["."]
    try:
        with open(log_path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        lines = []

    budget = MAX_ANNOTATIONS
    for block in what_went_wrong(lines)[:2]:
        emit("Gradle failure", block)
        budget -= 1
    errors = compile_errors(lines)
    if errors and budget > 0:
        emit(f"Kotlin compile errors ({len(errors)})", "\n".join(errors))
        budget -= 1
    tests = failed_tests(roots)
    for name, body in tests[: max(budget - 1, 0)]:
        emit(f"Test failed {name}", body)
    if len(tests) > max(budget - 1, 0) and budget > 0:
        rest = [n for n, _ in tests[max(budget - 1, 0):]]
        emit(f"{len(rest)} more failed tests", "\n".join(rest))
    if not (lines or tests):
        emit("CI failure", "No Gradle log and no test results were found.")


if __name__ == "__main__":
    main()
