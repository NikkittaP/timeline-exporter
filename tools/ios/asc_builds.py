#!/usr/bin/env python3
"""iOS build number: Apple's format, and App Store Connect (read-only).

    asc_builds.py format <number>
    asc_builds.py check --version 1.0.0 --build 261019.153012.1
    asc_builds.py wait  --version 1.0.0 --build 261019.153012.1 [--minutes 20]

format - the format only, no network. check - also asks App Store Connect
which build numbers the app already has and stops if ours was used before or
is not above the highest of the same version: Apple would reject such an
upload, but only after the whole macOS build. wait - polls until the uploaded
build is processed (VALID), fails on INVALID/FAILED, gives up quietly after
--minutes (Apple then reports by e-mail).

CFBundleVersion format (Apple): one to three non-negative integers separated
by dots, the first above zero, digits and dots only, at most 18 characters.
On top of that: no leading zeros (tools disagree on comparing them) and every
part within int32, in case something in the chain reads it as an int.

Environment for check/wait: APPSTORE_KEY_ID, APPSTORE_ISSUER_ID,
APPSTORE_PRIVATE_KEY (.p8 text), IOS_BUNDLE_ID. Only openssl is needed - no
Python packages (the macOS runner has none). The JWT is signed by openssl;
the key sits in a 0600 file for the signing call only and is deleted at once.
Neither the key nor the token is printed.
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com/v1"
BUILD_RE = re.compile(r"^[1-9][0-9]*(\.(0|[1-9][0-9]*)){0,2}$")
INT32 = 2**31 - 1


def format_errors(build: str) -> list[str]:
    if not BUILD_RE.fullmatch(build):
        return [f"'{build}': needs 1-3 integers separated by dots, the first > 0, no leading zeros"]
    errors = []
    if len(build) > 18:
        errors.append(f"'{build}': {len(build)} characters, Apple allows at most 18")
    if any(int(p) > INT32 for p in build.split(".")):
        errors.append(f"'{build}': a part is above {INT32}")
    return errors


def key_tuple(build: str) -> tuple[int, ...]:
    parts = [int(p) for p in build.split(".")]
    return tuple(parts + [0] * (3 - len(parts)))


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(sig: bytes) -> bytes:
    """openssl's ECDSA signature (DER SEQUENCE{r, s}) -> r||s of 32 bytes each (JWS ES256)."""
    if sig[0] != 0x30:
        raise ValueError("unexpected signature encoding")
    i = 2 if sig[1] < 0x80 else 2 + (sig[1] & 0x7F)
    out = b""
    for _ in range(2):
        if sig[i] != 0x02:
            raise ValueError("unexpected signature encoding")
        n = sig[i + 1]
        out += sig[i + 2:i + 2 + n].lstrip(b"\x00").rjust(32, b"\x00")
        i += 2 + n
    return out


def token() -> str:
    key_id = os.environ["APPSTORE_KEY_ID"].strip()
    issuer = os.environ["APPSTORE_ISSUER_ID"].strip()
    pem = os.environ["APPSTORE_PRIVATE_KEY"].replace("\\n", "\n").replace("\r", "").strip() + "\n"
    if "-----BEGIN PRIVATE KEY-----" not in pem:
        sys.exit("ERROR: APPSTORE_PRIVATE_KEY is not the PEM text of AuthKey_<KeyID>.p8")
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    payload = b64url(json.dumps({"iss": issuer, "iat": now, "exp": now + 600,
                                 "aud": "appstoreconnect-v1"}).encode())
    signing_input = f"{header}.{payload}".encode()
    fd, path = tempfile.mkstemp(suffix=".p8")
    try:
        os.chmod(path, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(pem)
        r = subprocess.run(["openssl", "dgst", "-sha256", "-sign", path],
                           input=signing_input, capture_output=True)
    finally:
        os.unlink(path)
    if r.returncode != 0 or not r.stdout:
        sys.exit("ERROR: openssl could not sign with APPSTORE_PRIVATE_KEY (not an EC P-256 key?)")
    return f"{header}.{payload}.{b64url(der_to_raw(r.stdout))}"


def get(url: str, jwt: str) -> dict:
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {jwt}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = "; ".join(x.get("detail", "") for x in json.load(e).get("errors", []))
        except Exception:
            pass
        hint = {401: "the API key is wrong or revoked, or APPSTORE_KEY_ID/ISSUER_ID are not its own",
                403: "the key has no access to this app"}.get(e.code, "")
        sys.exit(f"ERROR: App Store Connect HTTP {e.code} {hint} {detail}".rstrip())


def app_id(jwt: str, bundle: str) -> str:
    apps = get(f"{API}/apps?" + urllib.parse.urlencode(
        {"filter[bundleId]": bundle, "fields[apps]": "bundleId"}), jwt)["data"]
    apps = [a for a in apps if a["attributes"]["bundleId"] == bundle]
    if not apps:
        sys.exit("ERROR: no app with IOS_BUNDLE_ID in App Store Connect (or the API key has no access).")
    return apps[0]["id"]


def existing_builds(jwt: str, app: str) -> list[tuple[str, str, str]]:
    """[(version, number, processing state)] of every build of the app."""
    url = f"{API}/builds?" + urllib.parse.urlencode({
        "filter[app]": app, "limit": "200",
        "fields[builds]": "version,processingState,preReleaseVersion",
        "include": "preReleaseVersion", "fields[preReleaseVersions]": "version",
        "filter[processingState]": "PROCESSING,FAILED,INVALID,VALID"})
    found = []
    while url:
        page = get(url, jwt)
        versions = {i["id"]: i["attributes"]["version"] for i in page.get("included", [])
                    if i["type"] == "preReleaseVersions"}
        for b in page["data"]:
            rel = (b.get("relationships", {}).get("preReleaseVersion", {}).get("data") or {})
            found.append((versions.get(rel.get("id"), "?"), b["attributes"]["version"],
                          b["attributes"].get("processingState", "?")))
        url = page.get("links", {}).get("next")
    return found


def check(app: str, version: str, build: str) -> int:
    builds = existing_builds(token(), app)
    same = [n for v, n, _ in builds if v == version]
    print(f"App Store Connect: {len(builds)} build(s) in total, {len(same)} of version {version}"
          + (f", highest {max(same, key=key_tuple)}" if same else ""))
    if build in {n for _, n, _ in builds}:
        print(f"ERROR: build number {build} is already used in App Store Connect.")
        return 1
    if same and key_tuple(build) <= key_tuple(max(same, key=key_tuple)):
        print(f"ERROR: build number {build} is not higher than {max(same, key=key_tuple)} "
              f"already uploaded for {version}; Apple would reject the upload.")
        return 1
    print(f"Build number {build} is new and higher than every build of {version}.")
    return 0


def wait(app: str, version: str, build: str, minutes: int) -> int:
    deadline = time.time() + 60 * minutes
    while True:
        # A fresh token each round: one lives 10 minutes.
        state = next((s for v, n, s in existing_builds(token(), app)
                      if v == version and n == build), None)
        print(f"{version} ({build}): {state or 'not visible in App Store Connect yet'}", flush=True)
        if state == "VALID":
            print("Build processed: TestFlight -> Builds. It was not sent to external testers.")
            return 0
        if state in ("INVALID", "FAILED"):
            print(f"ERROR: App Store Connect rejected the build ({state}); the reason is in "
                  "Apple's e-mail and in TestFlight -> Builds.")
            return 1
        if time.time() > deadline:
            print("Still processing - that is normal; Apple reports the result by e-mail.")
            return 0
        time.sleep(60)


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("format")
    f.add_argument("build")
    for name in ("check", "wait"):
        c = sub.add_parser(name)
        c.add_argument("--version", required=True)
        c.add_argument("--build", required=True)
        if name == "wait":
            c.add_argument("--minutes", type=int, default=20)
    args = ap.parse_args()

    errors = format_errors(args.build)
    for e in errors:
        print(f"ERROR: build number {e}")
    if errors:
        return 1
    if args.cmd == "format":
        print(f"Build number {args.build}: format ok")
        return 0

    for v in ("APPSTORE_KEY_ID", "APPSTORE_ISSUER_ID", "APPSTORE_PRIVATE_KEY", "IOS_BUNDLE_ID"):
        if not os.environ.get(v, "").strip():
            print(f"ERROR: {v} is not set")
            return 1
    app = app_id(token(), os.environ["IOS_BUNDLE_ID"].strip())
    if args.cmd == "check":
        return check(app, args.version, args.build)
    return wait(app, args.version, args.build, args.minutes)


if __name__ == "__main__":
    sys.exit(main())
