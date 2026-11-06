#!/usr/bin/env python3
"""Tests for simulator_udid.py. Run: python3 tools/ios/test_simulator_udid.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import simulator_udid  # noqa: E402

RT = "com.apple.CoreSimulator.SimRuntime.iOS-{}-{}"


def dev(name, udid, available=True):
    return {"name": name, "udid": udid, "isAvailable": available}


DEVICES = {
    RT.format(26, 4): [dev("iPhone 16", "OLD-PHONE")],
    RT.format(27, 0): [dev("iPad Pro", "IPAD-27"), dev("iPhone 17", "PHONE-27")],
    RT.format(28, 0): [dev("iPhone 18", "PHONE-28")],
    "com.apple.CoreSimulator.SimRuntime.tvOS-28-0": [dev("iPhone fake", "TV")],
}


class PickUdid(unittest.TestCase):
    def test_newest_iphone_without_cap(self):
        self.assertEqual("PHONE-28", simulator_udid.pick_udid({"devices": DEVICES}))

    def test_ipad_is_skipped(self):
        d = {RT.format(27, 0): [dev("iPad Pro", "IPAD"), dev("iPhone 17", "PHONE")]}
        self.assertEqual("PHONE", simulator_udid.pick_udid({"devices": d}))

    def test_runtime_above_cap_is_skipped(self):
        self.assertEqual("PHONE-27", simulator_udid.pick_udid({"devices": DEVICES}, (27, 0)))

    def test_cap_allows_equal_runtime(self):
        self.assertEqual("PHONE-28", simulator_udid.pick_udid({"devices": DEVICES}, (28, 0)))

    def test_unavailable_device_is_skipped(self):
        d = {RT.format(27, 0): [dev("iPhone 17", "GONE", False)], RT.format(26, 4): [dev("iPhone 16", "OK")]}
        self.assertEqual("OK", simulator_udid.pick_udid({"devices": d}))

    def test_nothing_available_exits(self):
        with self.assertRaises(SystemExit):
            simulator_udid.pick_udid({"devices": {}})

    def test_everything_above_cap_exits(self):
        with self.assertRaises(SystemExit):
            simulator_udid.pick_udid({"devices": DEVICES}, (25, 0))

    def test_parse_cap(self):
        self.assertEqual((27, 0), simulator_udid.parse_cap("27.0"))
        self.assertEqual((27, 0), simulator_udid.parse_cap("27"))
        self.assertEqual((26, 4), simulator_udid.parse_cap("26.4.1"))
        self.assertIsNone(simulator_udid.parse_cap(None))


if __name__ == "__main__":
    unittest.main()
