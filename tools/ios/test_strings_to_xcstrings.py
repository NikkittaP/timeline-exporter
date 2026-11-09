#!/usr/bin/env python3
"""Tests for strings_to_xcstrings.py. Run: python3 tools/ios/test_strings_to_xcstrings.py"""
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import strings_to_xcstrings as s2x  # noqa: E402


def write(root, folder, body):
    os.makedirs(os.path.join(root, folder), exist_ok=True)
    with open(os.path.join(root, folder, "strings.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + body + "\n</resources>\n")


class Unescape(unittest.TestCase):
    def test_escapes(self):
        self.assertEqual("it's \"x\"\nnext\\", s2x.unescape(r"it\'s \"x\"\nnext\\"))

    def test_whitespace_collapses_unless_quoted(self):
        self.assertEqual("a b", s2x.unescape("  a \n   b "))
        self.assertEqual("  a  ", s2x.unescape('"  a  "'))

    def test_unicode_escape(self):
        self.assertEqual("x" + chr(0xA0) + "y", s2x.unescape("x" + "\\" + "u00a0y"))


class Formats(unittest.TestCase):
    def test_strings_and_ints(self):
        self.assertEqual("%1$@ of %2$lld, %@ %lld 100%%", s2x.convert_format("%1$s of %2$d, %s %d 100%%"))

    def test_plural_count_is_an_integer(self):
        self.assertEqual("%1$lld places", s2x.convert_format("%1$s places", plural=True))


class Build(unittest.TestCase):
    def test_catalog(self):
        with tempfile.TemporaryDirectory() as tmp:
            android = os.path.join(tmp, "res")
            ios = os.path.join(tmp, "ios")
            write(android, "values", """
                <string name="hello">Hello %1$s</string>
                <string name="only_en" translatable="false">X</string>
                <string name="android_word">Android settings</string>
                <plurals name="days"><item quantity="one">%1$d day</item><item quantity="other">%1$d days</item></plurals>
            """)
            write(android, "values-ru", """
                <string name="hello">Привет %1$s</string>
                <plurals name="days"><item quantity="one">%1$d день</item><item quantity="few">%1$d дня</item>
                <item quantity="many">%1$d дней</item><item quantity="other">%1$d дня</item></plurals>
            """)
            write(android, "values-zh-rTW", '<string name="hello">你好 %1$s</string>')
            write(ios, "values", '<string name="android_word">iOS settings</string><string name="ios_only">Files</string>')
            write(ios, "values-ru", '<string name="ios_only">Файлы</string>')
            cat = s2x.build(android, ios)

        strings = cat["strings"]
        self.assertEqual("en", cat["sourceLanguage"])
        self.assertEqual("Привет %1$@", strings["hello"]["localizations"]["ru"]["stringUnit"]["value"])
        self.assertEqual("你好 %1$@", strings["hello"]["localizations"]["zh-Hant"]["stringUnit"]["value"])
        # Missing in a language: English, not the key.
        self.assertEqual("X", strings["only_en"]["localizations"]["ru"]["stringUnit"]["value"])
        # iOS overrides and additions.
        self.assertEqual("iOS settings", strings["android_word"]["localizations"]["en"]["stringUnit"]["value"])
        self.assertEqual("Файлы", strings["ios_only"]["localizations"]["ru"]["stringUnit"]["value"])
        self.assertEqual("Files", strings["ios_only"]["localizations"]["zh-Hant"]["stringUnit"]["value"])
        ru = strings["days"]["localizations"]["ru"]["variations"]["plural"]
        self.assertEqual(["one", "few", "many", "other"], list(ru))
        self.assertEqual("%1$lld дней", ru["many"]["stringUnit"]["value"])
        zh = strings["days"]["localizations"]["zh-Hant"]["variations"]["plural"]
        self.assertEqual("%1$lld days", zh["other"]["stringUnit"]["value"])


class Committed(unittest.TestCase):
    def test_catalog_in_the_repository_is_current(self):
        with open(s2x.OUT, encoding="utf-8") as f:
            self.assertEqual(s2x.render(s2x.build()), f.read(),
                             "run python3 tools/ios/strings_to_xcstrings.py generate")

    def test_every_android_language_is_there(self):
        langs = set(next(iter(s2x.build()["strings"].values()))["localizations"])
        self.assertEqual(18, len(langs), sorted(langs))
        self.assertIn("zh-Hans", langs)
        self.assertIn("pt-BR", langs)

    def test_swift_counts_only_plural_strings(self):
        """L.plural passes an Int: into a plain "%1$@" string that is a crash."""
        ios = os.path.normpath(os.path.join(os.path.dirname(s2x.OUT), "..", ".."))
        strings = s2x.build()["strings"]
        used = set()
        for folder, _, names in os.walk(ios):
            for name in names:
                if name.endswith(".swift"):
                    with open(os.path.join(folder, name), encoding="utf-8") as f:
                        used.update(re.findall(r'\bL\.plural\(\s*"([^"]+)"', f.read()))
        self.assertTrue(used)
        wrong = sorted(k for k in used if "variations" not in strings[k]["localizations"]["en"])
        self.assertEqual([], wrong, "not plurals in the catalog: use L.f with a formatted number")


if __name__ == "__main__":
    unittest.main()
