#!/usr/bin/env python3
"""Tests for make_app_icon.py. Run: python3 tools/ios/test_make_app_icon.py"""
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_app_icon  # noqa: E402

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
HEAD = f'<vector {NS} android:viewportWidth="108" android:viewportHeight="108">'
BG = HEAD + '<path android:fillColor="#1976D2" android:pathData="M0,0 H108 V108 H0 Z" /></vector>'
FG = (
    HEAD
    + '<path android:strokeColor="#FFFFFF" android:strokeWidth="6" android:strokeLineCap="round"'
    ' android:strokeLineJoin="round" android:pathData="M 1,2 L 3,4" />'
    '<path android:fillColor="#FFFFFF" android:pathData="M 5,5 h 2 z" /></vector>'
)
DRAWABLE = Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"


class VectorToSvg(unittest.TestCase):
    def setUp(self):
        self.svg = make_app_icon.vector_to_svg(BG, FG)

    def test_viewbox(self):
        self.assertIn('viewBox="0 0 108 108"', self.svg)

    def test_background_first(self):
        self.assertLess(self.svg.index('fill="#1976D2"'), self.svg.index("stroke="))

    def test_stroke_attributes(self):
        for part in ('stroke="#FFFFFF"', 'stroke-width="6"',
                     'stroke-linecap="round"', 'stroke-linejoin="round"'):
            self.assertIn(part, self.svg)

    def test_stroke_only_path_has_no_fill(self):
        stroke_path = [p for p in self.svg.split("<path")[1:] if "stroke=" in p][0]
        self.assertIn('fill="none"', stroke_path)

    def test_alpha_color(self):
        fg = HEAD + '<path android:fillColor="#80FFFFFF" android:pathData="M0,0 Z" /></vector>'
        svg = make_app_icon.vector_to_svg(BG, fg)
        self.assertIn('fill="#FFFFFF"', svg)
        self.assertIn('fill-opacity="0.502"', svg)

    def test_real_files(self):
        bg = (DRAWABLE / "ic_launcher_background.xml").read_text(encoding="utf-8")
        fg = (DRAWABLE / "ic_launcher_foreground.xml").read_text(encoding="utf-8")
        self.assertEqual(4, make_app_icon.vector_to_svg(bg, fg).count("<path"))


if __name__ == "__main__":
    unittest.main()
