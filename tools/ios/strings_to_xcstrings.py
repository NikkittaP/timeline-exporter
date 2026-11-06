#!/usr/bin/env python3
"""Android string resources -> ios/TimelineExporter/Resources/Localizable.xcstrings.

The Android res/values*/strings.xml files stay the one source of translations.
ios/strings/values*/strings.xml (same format) adds iOS-only strings and
overrides Android ones whose wording is Android-specific; a key there wins.

  python3 tools/ios/strings_to_xcstrings.py generate   # rewrite the catalog
  python3 tools/ios/strings_to_xcstrings.py check      # fail if it is stale (CI)

Conversions:
  %1$s -> %1$@, %s -> %@, %1$d -> %1$lld, %d -> %lld (Swift passes String / Int).
  <plurals> become plural variations. Their single argument is the count, so
  %1$s in a plural becomes %1$lld: iOS picks the form from that integer, and
  String.localizedStringWithFormat groups its digits for the locale.
  A key missing from a language gets the English text, because iOS does not
  fall back to the development language one key at a time.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ANDROID_RES = os.path.join(ROOT, "app", "src", "main", "res")
IOS_RES = os.path.join(ROOT, "ios", "strings")
OUT = os.path.join(ROOT, "ios", "TimelineExporter", "Resources", "Localizable.xcstrings")

# values-<qualifier> -> Apple language code.
SPECIAL = {"pt-rBR": "pt-BR", "zh-rCN": "zh-Hans", "zh-rTW": "zh-Hant"}
PLURAL_ORDER = ["zero", "one", "two", "few", "many", "other"]


def language_of(dirname):
    if dirname == "values":
        return "en"
    q = dirname[len("values-"):]
    return SPECIAL.get(q, q)


def unescape(raw):
    """Android resource text -> plain text (after XML entities are decoded)."""
    s = raw
    quoted = len(s) >= 2 and s.startswith('"') and s.endswith('"')
    if quoted:
        s = s[1:-1]
    else:
        s = re.sub(r"\s+", " ", s).strip()
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "n":
                out.append("\n")
            elif n == "t":
                out.append("\t")
            elif n == "u" and re.match(r"[0-9a-fA-F]{4}", s[i + 2:i + 6]):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            else:
                out.append(n)  # \' \" \\ \@ \?
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def convert_format(text, plural=False):
    def repl(m):
        pos, conv = m.group(1) or "", m.group(2)
        if conv == "s":
            return f"%{pos}lld" if plural else f"%{pos}@"
        return f"%{pos}lld"

    # %% stays; %1$s / %s / %1$d / %d convert.
    parts = text.split("%%")
    return "%%".join(re.sub(r"%(\d+\$)?([sd])", repl, p) for p in parts)


def element_text(el):
    return "".join(el.itertext())


def read_file(path):
    """{key: str | {quantity: str}} from one strings.xml."""
    tree = ET.parse(path)
    result = {}
    for el in tree.getroot():
        name = el.get("name")
        if el.tag == "string":
            result[name] = convert_format(unescape(element_text(el)))
        elif el.tag == "plurals":
            forms = {}
            for item in el.findall("item"):
                forms[item.get("quantity")] = convert_format(unescape(element_text(item)), plural=True)
            result[name] = forms
    return result


def read_tree(res_dir):
    """{language: {key: value}} for every values*/strings.xml under res_dir."""
    langs = {}
    if not os.path.isdir(res_dir):
        return langs
    for d in sorted(os.listdir(res_dir)):
        path = os.path.join(res_dir, d, "strings.xml")
        if d.startswith("values") and os.path.isfile(path):
            langs.setdefault(language_of(d), {}).update(read_file(path))
    return langs


def unit(value):
    return {"stringUnit": {"state": "translated", "value": value}}


def localization(value):
    if isinstance(value, dict):
        return {"variations": {"plural": {q: unit(value[q]) for q in PLURAL_ORDER if q in value}}}
    return unit(value)


def build(android_res=ANDROID_RES, ios_res=IOS_RES):
    langs = read_tree(android_res)
    for lang, values in read_tree(ios_res).items():
        langs.setdefault(lang, {}).update(values)
    english = langs["en"]
    strings = {}
    for key in sorted(english):
        locs = {}
        for lang in sorted(langs):
            value = langs[lang].get(key, english[key])
            if isinstance(english[key], dict) and not isinstance(value, dict):
                value = english[key]
            if not isinstance(english[key], dict) and isinstance(value, dict):
                value = english[key]
            locs[lang] = localization(value)
        strings[key] = {"extractionState": "manual", "localizations": locs}
    return {"sourceLanguage": "en", "strings": strings, "version": "1.0"}


def render(catalog):
    # Xcode's own layout (" : " separators, two-space indent), so a catalog
    # Xcode re-saves shows no spurious diff.
    return json.dumps(catalog, ensure_ascii=False, indent=2, separators=(",", " : ")) + "\n"


def main(argv):
    if len(argv) != 2 or argv[1] not in ("generate", "check"):
        print(__doc__)
        return 2
    text = render(build())
    if argv[1] == "generate":
        with open(OUT, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"wrote {os.path.relpath(OUT, ROOT)}")
        return 0
    try:
        with open(OUT, encoding="utf-8") as f:
            current = f.read()
    except FileNotFoundError:
        current = ""
    if current != text:
        print("ERROR: Localizable.xcstrings is stale: run python3 tools/ios/strings_to_xcstrings.py generate")
        return 1
    print("Localizable.xcstrings is up to date.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
