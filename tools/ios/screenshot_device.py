#!/usr/bin/env python3
"""Print the device type and runtime to create a store-screenshot simulator from.

Usage: xcrun simctl list -j | screenshot_device.py iphone|ipad [SDK_VERSION]
Prints "<devicetype identifier> <runtime identifier>".

The App Store takes its largest sizes and scales them down for every
smaller device, so one of each is enough:
  iphone  the newest "iPhone N Pro Max" (6.9", 1320x2868)
  ipad    the newest "iPad Pro 13-inch (Mn)" (13", 2064x2752)
The runtime is the newest available iOS one that supports that device type
and is no newer than SDK_VERSION (Xcode cannot run tests on a newer one).
"""
import json
import re
import sys

PATTERNS = {
    "iphone": re.compile(r"^iPhone (\d+) Pro Max$"),
    "ipad": re.compile(r"^iPad Pro 13-inch \(M(\d+)\)$"),
}


def version(text):
    m = re.match(r"(\d+)(?:\.(\d+))?", text or "")
    return (int(m.group(1)), int(m.group(2) or 0)) if m else (-1, -1)


def pick(data, kind, cap=None):
    pattern = PATTERNS[kind]
    types = []
    for t in data.get("devicetypes", []):
        m = pattern.match(t.get("name", ""))
        if m:
            types.append((int(m.group(1)), t["identifier"]))
    if not types:
        sys.exit(f"no {kind} device type matching {pattern.pattern}")
    runtimes = [
        r for r in data.get("runtimes", [])
        if r.get("platform", "iOS") == "iOS" and r.get("isAvailable", True)
        and (cap is None or version(r.get("version")) <= cap)
    ]
    runtimes.sort(key=lambda r: version(r.get("version")), reverse=True)
    for _, identifier in sorted(types, reverse=True):
        for r in runtimes:
            supported = r.get("supportedDeviceTypes")
            # Older simctl lists no supportedDeviceTypes: take the runtime on trust.
            if supported is None or any(s.get("identifier") == identifier for s in supported):
                return identifier, r["identifier"]
    sys.exit(f"no available iOS runtime for a {kind} simulator"
             + (f" on iOS {cap[0]}.{cap[1]} or older" if cap else ""))


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in PATTERNS:
        sys.exit("usage: screenshot_device.py iphone|ipad [SDK_VERSION]")
    cap = version(sys.argv[2]) if len(sys.argv) > 2 else None
    print(*pick(json.load(sys.stdin), sys.argv[1], cap))


if __name__ == "__main__":
    main()
