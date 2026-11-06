#!/usr/bin/env python3
"""Print the UDID of an available iPhone simulator on the newest iOS runtime.

Usage: xcrun simctl list devices available -j | simulator_udid.py
Runtime keys look like com.apple.CoreSimulator.SimRuntime.iOS-27-0.
"""
import json
import re
import sys


def runtime_version(key):
    m = re.search(r"iOS-(\d+)-(\d+)", key)
    return (int(m.group(1)), int(m.group(2))) if m else (-1, -1)


def main():
    devices = json.load(sys.stdin)["devices"]
    for runtime in sorted((k for k in devices if "iOS" in k), key=runtime_version, reverse=True):
        for d in devices[runtime]:
            if d.get("isAvailable", True) and d["name"].startswith("iPhone"):
                print(d["udid"])
                return
    sys.exit("no available iPhone simulator")


if __name__ == "__main__":
    main()
