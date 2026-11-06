#!/usr/bin/env bash
# App Store signing on a throwaway runner: a temporary keychain with the Apple
# Distribution certificate and the App Store provisioning profile installed.
#
# Input (environment, all GitHub secrets): IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64,
# IOS_DISTRIBUTION_CERTIFICATE_PASSWORD, IOS_PROVISIONING_PROFILE_BASE64,
# APPLE_TEAM_ID, IOS_BUNDLE_ID.
# Output ($GITHUB_ENV): IOS_PROFILE_UUID, IOS_PROFILE_NAME, IOS_KEYCHAIN.
#
# Checked before the build, so no macOS minutes go to an archive that cannot
# be signed:
#   - the .p12 opens with the password and holds an Apple Distribution identity
#     of this team TOGETHER with its private key (`find-identity -v` lists only
#     certificate+key pairs whose key matches the certificate);
#   - the certificate has not expired;
#   - the profile is App Store (no device list, no get-task-allow), of the same
#     team and Bundle ID, not expired, and contains exactly this certificate.
#
# Secret contents are never printed: only the profile's UUID, name, Team ID,
# App ID and expiry are read. Everything lives in $RUNNER_TEMP/signing; the
# .p12 is deleted right after import (and on error), tools/ios/cleanup.sh
# removes the keychain and profile at the end of the job.
set -euo pipefail

# Messages must be fixed text: they go into an annotation unescaped except for % and newlines.
fail() {
  local msg="$*"
  echo "ERROR: $msg"
  msg="${msg//'%'/%25}"
  msg="${msg//$'\r'/}"
  echo "::error title=Signing::${msg//$'\n'/%0A}"
  exit 1
}

missing=0
for v in IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64 IOS_DISTRIBUTION_CERTIFICATE_PASSWORD \
         IOS_PROVISIONING_PROFILE_BASE64 APPLE_TEAM_ID IOS_BUNDLE_ID; do
  if [[ -z "${!v:-}" ]]; then
    echo "ERROR: GitHub secret $v is empty or not configured."
    echo "::error title=Signing::GitHub secret $v is empty or not configured."
    missing=1
  fi
done
[[ $missing == 0 ]] || exit 1

WORK="${RUNNER_TEMP:?}/signing"
rm -rf "$WORK"
(umask 077 && mkdir "$WORK")
P12="$WORK/dist.p12"
PROFILE="$WORK/profile.mobileprovision"
trap 'rm -f "$P12"' EXIT
decode() {  # $1 = secret name, $2 = file
  if ! printf '%s' "${!1}" | tr -d ' \r\n' | base64 --decode > "$2" 2> /dev/null \
      || [[ ! -s "$2" ]]; then
    fail "secret $1 is not valid base64."
  fi
}
(umask 077 && decode IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64 "$P12" \
            && decode IOS_PROVISIONING_PROFILE_BASE64 "$PROFILE")

# --- profile: metadata only -----------------------------------------------
if ! security cms -D -i "$PROFILE" > "$WORK/profile.plist" 2> /dev/null; then
  fail "IOS_PROVISIONING_PROFILE_BASE64 does not decode to a .mobileprovision."
fi
python3 - "$WORK/profile.plist" "$WORK/profile.env" "$WORK/profile_certs" <<'PY'
import datetime, hashlib, os, plistlib, shlex, sys
src, out, certs_dir = sys.argv[1:4]
p = plistlib.load(open(src, "rb"))
team = os.environ["APPLE_TEAM_ID"]
bundle = os.environ["IOS_BUNDLE_ID"]
ent = p.get("Entitlements", {})
app_id = ent.get("application-identifier", "")
teams = p.get("TeamIdentifier", [])
errors = []
if team not in teams:
    errors.append("provisioning profile Team ID does not match APPLE_TEAM_ID.")
if app_id != f"{team}.{bundle}":
    errors.append("provisioning profile App ID does not match APPLE_TEAM_ID.IOS_BUNDLE_ID "
                  "(wildcard or another app's profile).")
if "ProvisionedDevices" in p or p.get("ProvisionsAllDevices") or ent.get("get-task-allow"):
    errors.append("provisioning profile is not an App Store distribution profile "
                  "(it lists devices or allows debugging).")
if "iOS" not in p.get("Platform", ["iOS"]):
    errors.append(f"provisioning profile platform is {p.get('Platform')}, not iOS.")
exp = p["ExpirationDate"].replace(tzinfo=datetime.timezone.utc)
days = (exp - datetime.datetime.now(datetime.timezone.utc)).days
if days < 0:
    errors.append(f"provisioning profile expired on {exp:%Y-%m-%d}.")
print(f"Profile: '{p['Name']}' UUID {p['UUID']}, expires {exp:%Y-%m-%d}"
      + (f" - WARNING: {days} days left" if 0 <= days < 30 else ""))
for e in errors:
    print("ERROR: " + e)
    print("::error title=Signing::" + e.replace("%", "%25").replace("\r", "").replace("\n", "%0A"))
if errors:
    sys.exit(1)
os.makedirs(certs_dir)
for c in p.get("DeveloperCertificates", []):
    open(os.path.join(certs_dir, hashlib.sha1(c).hexdigest().upper()), "wb").close()
# Profile names usually contain spaces ("Timeline Exporter App Store"): quoted for source below.
with open(out, "w") as f:
    f.write(f"IOS_PROFILE_UUID={shlex.quote(p['UUID'])}\n"
            f"IOS_PROFILE_NAME={shlex.quote(p['Name'])}\n")
PY
# shellcheck disable=SC1091
source "$WORK/profile.env"

for dir in "$HOME/Library/MobileDevice/Provisioning Profiles" \
           "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
  mkdir -p "$dir"
  cp "$PROFILE" "$dir/$IOS_PROFILE_UUID.mobileprovision"
done
rm -f "$PROFILE"

# --- temporary keychain ---------------------------------------------------
KC="$WORK/signing.keychain-db"
# Random keychain password, never printed, lives only in this process:
# codesign does not ask for it after unlock.
KC_PASS=$(openssl rand -hex 24)
security create-keychain -p "$KC_PASS" "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p "$KC_PASS" "$KC"
if ! security import "$P12" -k "$KC" -f pkcs12 -P "$IOS_DISTRIBUTION_CERTIFICATE_PASSWORD" \
      -T /usr/bin/codesign -T /usr/bin/security > /dev/null 2>&1; then
  echo "       The .p12 may use an encryption macOS does not accept (rebuild it with -legacy)."
  fail "could not import the .p12 - wrong IOS_DISTRIBUTION_CERTIFICATE_PASSWORD or unsupported .p12 encryption"
fi
rm -f "$P12"
# Without this codesign hangs in CI on an invisible key-access dialog.
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KC_PASS" "$KC" > /dev/null
unset KC_PASS
# Temporary keychain first in the search list, keep the existing ones.
existing=$(security list-keychains -d user | sed -e 's/^[[:space:]]*"//' -e 's/"$//')
# shellcheck disable=SC2086
security list-keychains -d user -s "$KC" $existing

# Only "valid" identities: certificate + matching private key, not revoked or expired.
identities=$(security find-identity -v -p codesigning "$KC")
if ! match=$(printf '%s\n' "$identities" | grep "Apple Distribution: .*($APPLE_TEAM_ID)"); then
  echo "       Either the certificate is of another type/team, it is expired or revoked,"
  echo "       or the .p12 lacks the private key that matches the certificate."
  printf '%s\n' "$identities" | sed -E 's/[0-9A-F]{40}/<sha1>/'
  fail "no valid 'Apple Distribution' identity of team APPLE_TEAM_ID in the .p12."
fi
if [[ $(printf '%s\n' "$match" | wc -l) -gt 1 ]]; then
  fail "the .p12 holds several Apple Distribution identities; export only one."
fi
sha1=$(printf '%s\n' "$match" | awk '{print $2}')
if [[ ! -e "$WORK/profile_certs/$sha1" ]]; then
  echo "       Edit the profile in Apple Developer, select this certificate, download it and update"
  echo "       IOS_PROVISIONING_PROFILE_BASE64."
  fail "the Apple Distribution certificate in the .p12 is not included in the provisioning profile."
fi
not_after=$(security find-certificate -Z -a -p "$KC" | awk -v s="$sha1" '
  /^SHA-1 hash:/ {keep = ($3 == s)} keep {print}' | openssl x509 -noout -enddate | cut -d= -f2) \
  || not_after="(unknown)"
echo "Signing identity: $(printf '%s\n' "$match" | sed -E 's/^.*"(.*)".*$/\1/'), valid until $not_after"
echo "Certificate/private-key pair and provisioning profile verified."

{
  echo "IOS_PROFILE_UUID=$IOS_PROFILE_UUID"
  echo "IOS_PROFILE_NAME=$IOS_PROFILE_NAME"
  echo "IOS_KEYCHAIN=$KC"
} >> "${GITHUB_ENV:?}"
