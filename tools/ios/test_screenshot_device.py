#!/usr/bin/env python3
"""Tests for screenshot_device.py. Run: python3 tools/ios/test_screenshot_device.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screenshot_device  # noqa: E402

DT = "com.apple.CoreSimulator.SimDeviceType."


def dtype(name):
    return {"name": name, "identifier": DT + name.replace(" ", "-").replace("(", "").replace(")", "")}


def runtime(ver, types=None, available=True):
    r = {"platform": "iOS", "version": ver, "isAvailable": available,
         "identifier": "com.apple.CoreSimulator.SimRuntime.iOS-" + ver.replace(".", "-")}
    if types is not None:
        r["supportedDeviceTypes"] = [{"identifier": t["identifier"]} for t in types]
    return r


PRO_MAX_16 = dtype("iPhone 16 Pro Max")
PRO_MAX_17 = dtype("iPhone 17 Pro Max")
IPHONE_17 = dtype("iPhone 17")
IPAD_M4 = dtype("iPad Pro 13-inch (M4)")
IPAD_M5 = dtype("iPad Pro 13-inch (M5)")
IPAD_11 = dtype("iPad Pro 11-inch (M5)")
ALL = [PRO_MAX_16, PRO_MAX_17, IPHONE_17, IPAD_M4, IPAD_M5, IPAD_11]


class Pick(unittest.TestCase):
    def test_newest_pro_max_on_newest_runtime(self):
        data = {"devicetypes": ALL, "runtimes": [runtime("26.4", ALL), runtime("27.0", ALL)]}
        self.assertEqual((PRO_MAX_17["identifier"], runtime("27.0")["identifier"]),
                         screenshot_device.pick(data, "iphone"))

    def test_newest_13_inch_ipad(self):
        data = {"devicetypes": ALL, "runtimes": [runtime("27.0", ALL)]}
        self.assertEqual(IPAD_M5["identifier"], screenshot_device.pick(data, "ipad")[0])

    def test_runtime_newer_than_the_sdk_is_skipped(self):
        data = {"devicetypes": ALL, "runtimes": [runtime("27.0", ALL), runtime("28.0", ALL)]}
        self.assertEqual(runtime("27.0")["identifier"], screenshot_device.pick(data, "iphone", (27, 0))[1])

    def test_falls_back_to_an_older_model_the_runtime_supports(self):
        data = {"devicetypes": ALL, "runtimes": [runtime("26.4", [PRO_MAX_16, IPAD_M4])]}
        self.assertEqual(PRO_MAX_16["identifier"], screenshot_device.pick(data, "iphone")[0])
        self.assertEqual(IPAD_M4["identifier"], screenshot_device.pick(data, "ipad")[0])

    def test_unavailable_runtime_and_other_platforms_are_skipped(self):
        tv = runtime("27.0", ALL)
        tv["platform"] = "tvOS"
        data = {"devicetypes": ALL, "runtimes": [tv, runtime("27.1", ALL, available=False), runtime("26.4", ALL)]}
        self.assertEqual(runtime("26.4")["identifier"], screenshot_device.pick(data, "iphone")[1])

    def test_runtime_without_a_supported_list_is_trusted(self):
        data = {"devicetypes": ALL, "runtimes": [runtime("27.0")]}
        self.assertEqual(PRO_MAX_17["identifier"], screenshot_device.pick(data, "iphone")[0])

    def test_nothing_suitable_exits(self):
        with self.assertRaises(SystemExit):
            screenshot_device.pick({"devicetypes": [IPHONE_17], "runtimes": [runtime("27.0")]}, "iphone")
        with self.assertRaises(SystemExit):
            screenshot_device.pick({"devicetypes": ALL, "runtimes": [runtime("28.0", ALL)]}, "ipad", (27, 0))


if __name__ == "__main__":
    unittest.main()
