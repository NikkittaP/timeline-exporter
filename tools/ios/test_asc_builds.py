#!/usr/bin/env python3
"""Tests for asc_builds.py: build-number format, ordering, DER->JWS signature. No network."""

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import asc_builds  # noqa: E402


class FormatTest(unittest.TestCase):
    def test_accepts(self):
        for b in ("261106.93012.1", "1", "1.0.0", "2147483647.0.1"):
            self.assertEqual(asc_builds.format_errors(b), [], b)

    def test_rejects(self):
        for b in ("0.1", "01.2", "1.2.3.4", "1.a", "", "1..2", "-1",
                  "1" * 19, "2147483648.1", "1.2147483648"):
            self.assertTrue(asc_builds.format_errors(b), b)

    def test_rejects_trailing_newline(self):
        # re.match with $ would let "1.2\n" through into $GITHUB_OUTPUT.
        for b in ("1.2\n", "1\n", "261106.93012.1\n"):
            self.assertTrue(asc_builds.format_errors(b), repr(b))

    def test_length_limit(self):
        self.assertEqual(asc_builds.format_errors("261106.93012.1234"), [])  # 17 chars
        self.assertTrue(asc_builds.format_errors("1" * 19))


class KeyTupleTest(unittest.TestCase):
    def test_padding(self):
        self.assertEqual(asc_builds.key_tuple("1.2"), (1, 2, 0))
        self.assertEqual(asc_builds.key_tuple("7"), (7, 0, 0))

    def test_ordering_is_numeric(self):
        self.assertGreater(asc_builds.key_tuple("261106.93012.2"),
                           asc_builds.key_tuple("261106.93012.1"))
        self.assertGreater(asc_builds.key_tuple("10"), asc_builds.key_tuple("9.9.9"))


class DerToRawTest(unittest.TestCase):
    @staticmethod
    def der(r: bytes, s: bytes) -> bytes:
        body = b"\x02" + bytes([len(r)]) + r + b"\x02" + bytes([len(s)]) + s
        return b"\x30" + bytes([len(body)]) + body

    def test_full_length_with_sign_padding(self):
        r = b"\x00" + b"\x81" * 32
        s = b"\x7f" * 32
        raw = asc_builds.der_to_raw(self.der(r, s))
        self.assertEqual(len(raw), 64)
        self.assertEqual(raw, b"\x81" * 32 + s)

    def test_short_integers_left_padded(self):
        raw = asc_builds.der_to_raw(self.der(b"\x01\x02", b"\x05"))
        self.assertEqual(raw, b"\x00" * 30 + b"\x01\x02" + b"\x00" * 31 + b"\x05")

    def test_long_form_sequence_length(self):
        r = b"\x00" + b"\xff" * 32
        s = b"\x00" + b"\xee" * 32
        body = b"\x02\x21" + r + b"\x02\x21" + s
        der = b"\x30\x81" + bytes([len(body)]) + body
        self.assertEqual(asc_builds.der_to_raw(der), b"\xff" * 32 + b"\xee" * 32)

    def test_garbage(self):
        with self.assertRaises(ValueError):
            asc_builds.der_to_raw(b"\x31\x00")


class MainTest(unittest.TestCase):
    def run_main(self, *args):
        with mock.patch.object(sys, "argv", ["asc_builds.py", *args]):
            return asc_builds.main()

    def test_format_ok(self):
        self.assertEqual(self.run_main("format", "261106.93012.1"), 0)

    def test_format_bad(self):
        self.assertEqual(self.run_main("format", "0.1"), 1)


if __name__ == "__main__":
    unittest.main()
