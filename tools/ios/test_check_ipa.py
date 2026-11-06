#!/usr/bin/env python3
"""Tests for check_ipa.py with synthetic .ipa files."""

import contextlib
import io
import os
import plistlib
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_ipa  # noqa: E402

APP = "Payload/TimelineExporter.app"
BUNDLE, VERSION, BUILD = "com.example.timeline", "1.0.0", "261106.93012.1"


def good_info():
    return {
        "CFBundleIdentifier": BUNDLE,
        "CFBundleShortVersionString": VERSION,
        "CFBundleVersion": BUILD,
        "MinimumOSVersion": "17.0",
        "UIDeviceFamily": [1, 2],
        "ITSAppUsesNonExemptEncryption": False,
        "CFBundleIcons": {"CFBundlePrimaryIcon": {"CFBundleIconName": "AppIcon"}},
    }


class CheckIpaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def make(self, info=None, drop=(), extra=None, app=APP):
        files = {
            f"{app}/Info.plist": plistlib.dumps(good_info() if info is None else info),
            f"{app}/embedded.mobileprovision": b"x",
            f"{app}/Assets.car": b"x",
        }
        for d in drop:
            del files[f"{app}/{d}"]
        files.update(extra or {})
        path = os.path.join(self.tmp.name, "t.ipa")
        with zipfile.ZipFile(path, "w") as z:
            for n, data in files.items():
                z.writestr(n, data)
        return path

    def errors(self, path):
        return check_ipa.ipa_errors(path, BUNDLE, VERSION, BUILD)

    def one_error(self, path, needle):
        errs = self.errors(path)
        self.assertEqual(len(errs), 1, errs)
        self.assertIn(needle, errs[0])

    def test_valid(self):
        self.assertEqual(self.errors(self.make()), [])

    def test_wrong_bundle_id(self):
        info = good_info()
        info["CFBundleIdentifier"] = "com.other"
        self.one_error(self.make(info), "CFBundleIdentifier")

    def test_wrong_build(self):
        info = good_info()
        info["CFBundleVersion"] = "1"
        self.one_error(self.make(info), "CFBundleVersion")

    def test_missing_profile(self):
        self.one_error(self.make(drop=("embedded.mobileprovision",)), "embedded.mobileprovision")

    def test_missing_assets_car(self):
        self.one_error(self.make(drop=("Assets.car",)), "Assets.car")

    def test_embedded_shared_framework(self):
        path = self.make(extra={f"{APP}/Frameworks/Shared.framework/Shared": b"x"})
        self.one_error(path, "Shared.framework")

    def test_encryption_key_missing(self):
        info = good_info()
        del info["ITSAppUsesNonExemptEncryption"]
        self.one_error(self.make(info), "ITSAppUsesNonExemptEncryption")

    def test_iphone_only(self):
        info = good_info()
        info["UIDeviceFamily"] = [1]
        self.one_error(self.make(info), "UIDeviceFamily")

    def test_top_level_icon_name_accepted(self):
        info = good_info()
        del info["CFBundleIcons"]
        info["CFBundleIconName"] = "AppIcon"
        self.assertEqual(self.errors(self.make(info)), [])

    def test_ipad_icon_name_accepted(self):
        info = good_info()
        info["CFBundleIcons~ipad"] = info.pop("CFBundleIcons")
        self.assertEqual(self.errors(self.make(info)), [])

    def test_icon_name_missing(self):
        info = good_info()
        del info["CFBundleIcons"]
        self.one_error(self.make(info), "AppIcon")

    def test_icon_name_wrong(self):
        info = good_info()
        info["CFBundleIcons"]["CFBundlePrimaryIcon"]["CFBundleIconName"] = "Other"
        self.one_error(self.make(info), "AppIcon")

    def test_wrong_version_and_min_os(self):
        info = good_info()
        info["CFBundleShortVersionString"] = "2.0"
        self.one_error(self.make(info), "CFBundleShortVersionString")
        info = good_info()
        info["MinimumOSVersion"] = "16.0"
        self.one_error(self.make(info), "MinimumOSVersion")

    def test_empty_profile_and_non_zip(self):
        self.one_error(self.make(extra={f"{APP}/embedded.mobileprovision": b""}), "empty")
        bad = os.path.join(self.tmp.name, "bad.ipa")
        with open(bad, "wb") as f:
            f.write(b"nope")
        self.one_error(bad, "zip")

    def test_annotation_is_escaped(self):
        path = self.make(app="Payload/100%" + chr(10) + "::error::x.app")
        argv = ["check_ipa.py", path, "--bundle-id", BUNDLE, "--version", VERSION, "--build", BUILD]
        out = io.StringIO()
        with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(out):
            self.assertEqual(check_ipa.main(), 1)
        ann = [l for l in out.getvalue().splitlines() if l.startswith("::error title=IPA check::")]
        self.assertEqual(len(ann), 1)
        self.assertIn("100%25%0A::error::x.app", ann[0])
        self.assertNotIn(chr(10), ann[0])

    def test_wrong_app_name(self):
        self.one_error(self.make(app="Payload/Other.app"), "exactly one")

    def test_main_exit_codes(self):
        good = self.make()
        argv = ["check_ipa.py", good, "--bundle-id", BUNDLE, "--version", VERSION, "--build", BUILD]
        with mock.patch.object(sys, "argv", argv):
            self.assertEqual(check_ipa.main(), 0)
        with mock.patch.object(sys, "argv", argv[:-1] + ["2"]):
            self.assertEqual(check_ipa.main(), 1)


if __name__ == "__main__":
    unittest.main()
