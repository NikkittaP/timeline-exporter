#!/usr/bin/env python3
"""Print the UDID of an available iPhone simulator on the newest iOS runtime.

Usage: xcrun simctl list devices available -j | simulator_udid.py [SDK_VERSION]
Runtime keys look like com.apple.CoreSimulator.SimRuntime.iOS-27-0.
SDK_VERSION ("27.0" or "27", e.g. from `xcrun --sdk iphonesimulator
--show-sdk-version`) caps the runtime: a runtime newer than the Xcode's SDK
is skipped, because Xcode cannot run tests on it. No argument means no cap.
"""
import json
import re
import sys


def runtime_version(key):
    m = re.search(r"iOS-(\d+)-(\d+)", key)
    return (int(m.group(1)), int(m.group(2))) if m else (-1, -1)


def parse_cap(text):
    if not text:
        return None
    m = re.match(r"(\d+)(?:\.(\d+))?", text.strip())
    if not m:
        sys.exit(f"bad SDK version: {text!r}")
    return (int(m.group(1)), int(m.group(2) or 0))


def pick_udid(data, cap=None):
    devices = data["devices"]
    runtimes = [k for k in devices if "iOS" in k and (cap is None or runtime_version(k) <= cap)]
    for runtime in sorted(runtimes, key=runtime_version, reverse=True):
        for d in devices[runtime]:
            if d.get("isAvailable", True) and d["name"].startswith("iPhone"):
                return d["udid"]
    sys.exit("no available iPhone simulator" + (f" on iOS {cap[0]}.{cap[1]} or older" if cap else ""))


def main():
    cap = parse_cap(sys.argv[1]) if len(sys.argv) > 1 else None
    print(pick_udid(json.load(sys.stdin), cap))


if __name__ == "__main__":
    main()
