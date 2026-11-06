#!/usr/bin/env python3
"""Sanity-check a built Timeline Exporter .ipa before it is uploaded.

    check_ipa.py <ipa> --bundle-id ID --version V --build B

Exit 0 when every check passes, 1 otherwise. Each problem is printed as
"ERROR: ..." and as a GitHub annotation ("::error title=IPA check::...").
Stdlib only (zipfile + plistlib).
"""

import argparse
import plistlib
import re
import sys
import zipfile

APP = "TimelineExporter.app"
PAYLOAD = f"Payload/{APP}"
MIN_OS = "17.0"


def _esc(text: str) -> str:
    """Escape annotation text like tools/ci_annotate.py (the text comes from the .ipa)."""
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def icon_names(info: dict) -> set:
    """CFBundleIconName at the top level (macOS shape) and in the iOS CFBundleIcons dicts."""
    names = {info.get("CFBundleIconName")}
    for key in ("CFBundleIcons", "CFBundleIcons~ipad"):
        icons = info.get(key)
        primary = icons.get("CFBundlePrimaryIcon") if isinstance(icons, dict) else None
        if isinstance(primary, dict):
            names.add(primary.get("CFBundleIconName"))
    return names


def ipa_errors(ipa_path: str, bundle_id: str, version: str, build: str) -> list[str]:
    try:
        zf = zipfile.ZipFile(ipa_path)
    except (OSError, zipfile.BadZipFile) as e:
        return [f"cannot open {ipa_path} as a zip archive: {e}"]
    with zf:
        names = zf.namelist()
        apps = sorted({m.group(0) for n in names if (m := re.match(r"^Payload/[^/]+\.app/", n))})
        if apps != [f"{PAYLOAD}/"]:
            return [f"expected exactly one {PAYLOAD}/ in the .ipa, found: "
                    + (", ".join(apps) if apps else "no .app directory")]

        errors = []
        try:
            info = plistlib.loads(zf.read(f"{PAYLOAD}/Info.plist"))
        except Exception as e:  # missing or unparsable plist
            errors.append(f"{PAYLOAD}/Info.plist is missing or unreadable: {e!r}")
            info = None

        if info is not None:
            def expect(key, want):
                got = info.get(key)
                if got != want:
                    errors.append(f"Info.plist {key} is {got!r}, expected {want!r}")

            expect("CFBundleIdentifier", bundle_id)
            expect("CFBundleShortVersionString", version)
            expect("CFBundleVersion", build)
            expect("MinimumOSVersion", MIN_OS)
            if "AppIcon" not in icon_names(info):
                errors.append("Info.plist has no CFBundleIconName 'AppIcon' (top level or in CFBundleIcons)")
            family = info.get("UIDeviceFamily")
            if not isinstance(family, list) or 1 not in family or 2 not in family:
                errors.append(f"Info.plist UIDeviceFamily is {family!r}, expected iPhone and iPad (1 and 2)")
            if info.get("ITSAppUsesNonExemptEncryption") is not False:
                errors.append("Info.plist ITSAppUsesNonExemptEncryption is "
                              f"{info.get('ITSAppUsesNonExemptEncryption')!r}, expected false")

        try:
            if zf.getinfo(f"{PAYLOAD}/embedded.mobileprovision").file_size == 0:
                errors.append("embedded.mobileprovision is empty")
        except KeyError:
            errors.append("embedded.mobileprovision is missing (the .ipa is not signed with a profile)")

        if f"{PAYLOAD}/Assets.car" not in names:
            errors.append("Assets.car is missing (the compiled asset catalog with the app icon)")

        if any(n.startswith(f"{PAYLOAD}/Frameworks/Shared.framework/") for n in names):
            errors.append("Frameworks/Shared.framework is embedded; Shared is static and must not be")
        return errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ipa")
    ap.add_argument("--bundle-id", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--build", required=True)
    args = ap.parse_args()
    errors = ipa_errors(args.ipa, args.bundle_id, args.version, args.build)
    for e in errors:
        print(f"ERROR: {e}")
        print(f"::error title=IPA check::{_esc(e)}")
    if errors:
        return 1
    print("IPA check: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
