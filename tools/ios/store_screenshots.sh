#!/usr/bin/env bash
# Raw App Store screenshots from one simulator, every language.
#
#   tools/ios/store_screenshots.sh iphone|ipad [LOCALES] [SCENES]
#
# LOCALES  comma list of tools/store_shots locales (en-US,ru-RU,...); empty = all
# SCENES   comma list of ScreenshotMode scenes (map,days,...); empty = all
#
# Needs the generated ios/TimelineExporter.xcodeproj (xcodegen) and
# Shared.xcframework. Writes fastlane/screenshots_raw/<locale>/<kind>/<name>.png,
# which `node tools/store_shots/render.mjs --platform ios` frames. Run from the
# repository root, on macOS. Failures are annotated for GitHub Actions.
set -euo pipefail

kind="${1:?usage: store_screenshots.sh iphone|ipad [LOCALES] [SCENES]}"
locales="${2:-}"
scenes="${3:-}"
root="$(pwd)"
config="$root/tools/store_shots/config.json"
logs="${LOGS:-$root/build/ios/logs}"
mkdir -p "$logs"

fail() {
  echo "::error title=Screenshots ($kind)::$1"
  exit 1
}

[[ -f "$config" ]] || fail "run from the repository root ($config not found)"
[[ -d ios/TimelineExporter.xcodeproj ]] || fail "ios/TimelineExporter.xcodeproj is missing: run xcodegen first"

if [[ -z "$locales" ]]; then
  locales="$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["locales"]))' "$config")"
fi

# --- the simulator: a fresh one, so no earlier run's state leaks in.
sdk="$(xcrun --sdk iphonesimulator --show-sdk-version)"
pick="$(xcrun simctl list -j | python3 tools/ios/screenshot_device.py "$kind" "$sdk")" \
  || fail "no simulator for $kind on iOS $sdk (tools/ios/screenshot_device.py)"
read -r devtype runtime <<< "$pick"
name="Store screenshots $kind"
xcrun simctl list devices -j | python3 -c '
import json, sys
for devs in json.load(sys.stdin)["devices"].values():
    for d in devs:
        if d["name"] == sys.argv[1]:
            print(d["udid"])' "$name" | while read -r old; do xcrun simctl delete "$old" || true; done
udid="$(xcrun simctl create "$name" "$devtype" "$runtime")" || fail "simctl create $devtype $runtime failed"
echo "Simulator: $udid ($devtype, $runtime)"
trap 'xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true; xcrun simctl delete "$udid" >/dev/null 2>&1 || true' EXIT

xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b >/dev/null
xcrun simctl ui "$udid" appearance light
# The status bar every store screenshot shows.
xcrun simctl status_bar "$udid" override --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100

# --- build once, then one test run per language.
derived="$root/build/ios/screens-$kind"
log="$logs/screens-build-$kind.log"
if ! (cd ios && xcodebuild build-for-testing -project TimelineExporter.xcodeproj -scheme StoreScreenshots \
    -configuration Debug -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived" \
    CODE_SIGNING_ALLOWED=NO) > "$log" 2>&1; then
  grep -E "error:|\*\* " "$log" | head -n 40 || true
  tail -n 30 "$log"
  fail "build-for-testing failed, see $(basename "$log")"
fi

failed=()
IFS=',' read -r -a list <<< "$locales"
for locale in "${list[@]}"; do
  language="$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["ios"]["locales"][sys.argv[2]]["language"])' "$config" "$locale")" \
    || fail "no ios.locales entry for $locale in config.json"
  out="$root/fastlane/screenshots_raw/$locale/$kind"
  rm -rf "$out"
  mkdir -p "$out"
  log="$logs/screens-$kind-$locale.log"
  echo "== $locale ($language)"
  if ! (cd ios && TEST_RUNNER_SCREENSHOT_DIR="$out" TEST_RUNNER_SCREENSHOT_LANGUAGE="$language" \
      TEST_RUNNER_SCREENSHOT_SCENES="$scenes" \
      xcodebuild test-without-building -project TimelineExporter.xcodeproj -scheme StoreScreenshots \
      -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived" \
      -only-testing:TimelineExporterUITests/StoreScreenshots \
      -resultBundlePath "$root/build/ios/screens-$kind-$locale.xcresult") > "$log" 2>&1; then
    grep -E "error:|failed|\*\* TEST" "$log" | head -n 20 || true
    echo "::warning title=Screenshots ($kind $locale)::the capture test failed, see $(basename "$log")"
    failed+=("$locale")
  fi
  ls "$out"
done

count="$(find "$root/fastlane/screenshots_raw" -path "*/$kind/*.png" | wc -l | tr -d ' ')"
echo "$count raw $kind screenshot(s)"
if ((${#failed[@]})); then
  fail "the capture failed for: ${failed[*]}"
fi
