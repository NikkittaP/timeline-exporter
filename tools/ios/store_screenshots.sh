#!/usr/bin/env bash
# Raw App Store screenshots from one simulator, every language.
#
#   tools/ios/store_screenshots.sh iphone|ipad [LOCALES] [SCENES]
#
# LOCALES  comma list of tools/store_shots locales (en-US,ru-RU,...); empty = all
# SCENES   comma list of ScreenshotMode scenes (map,days,...); empty = all
# SHARD    environment, optional "i/n": take every n-th locale from the i-th
#          (1-based), so n runners share the languages
# ORIENTATION  environment, optional "landscape": turns the simulator and writes
#          to <locale>/<kind>-landscape, apart from the store set
#
# One xcodebuild test run takes every language: a run per language spent
# about a minute and a half each on start-up and the result bundle.
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
shots="$kind${ORIENTATION:+-$ORIENTATION}"

fail() {
  echo "::error title=Screenshots ($kind)::$1"
  exit 1
}

[[ -f "$config" ]] || fail "run from the repository root ($config not found)"
[[ -d ios/TimelineExporter.xcodeproj ]] || fail "ios/TimelineExporter.xcodeproj is missing: run xcodegen first"

if [[ -z "$locales" ]]; then
  locales="$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1]))["locales"]))' "$config")"
fi
if [[ -n "${SHARD:-}" ]]; then
  locales="$(python3 -c '
import sys
i, n = (int(x) for x in sys.argv[2].split("/"))
print(",".join(sys.argv[1].split(",")[i - 1::n]))' "$locales" "$SHARD")" || fail "SHARD must be i/n, got '$SHARD'"
  [[ -n "$locales" ]] || { echo "Shard $SHARD has no locales"; exit 0; }
fi
# locale=language pairs for the test, e.g. en-US=en,zh-CN=zh-Hans.
pairs="$(python3 -c '
import json, sys
ios = json.load(open(sys.argv[1]))["ios"]["locales"]
missing = [l for l in sys.argv[2].split(",") if l not in ios]
if missing:
    sys.exit("no ios.locales entry for " + ", ".join(missing))
print(",".join(l + "=" + ios[l]["language"] for l in sys.argv[2].split(",")))' "$config" "$locales")"   || fail "config.json lacks ios.locales entries for some of: $locales"
echo "Locales: $locales"

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

boot_simulator() {
  xcrun simctl boot "$udid" &&
    xcrun simctl bootstatus "$udid" -b >/dev/null &&
    xcrun simctl ui "$udid" appearance light &&
    # The status bar every store screenshot shows.
    xcrun simctl status_bar "$udid" override --time "9:41" --dataNetwork wifi --wifiMode active --wifiBars 3 \
      --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
}
boot_simulator || fail "the simulator $udid did not boot"

# --- build once.
derived="$root/build/ios/screens-$kind"
log="$logs/screens-build-$kind.log"
if ! (cd ios && xcodebuild build-for-testing -project TimelineExporter.xcodeproj -scheme StoreScreenshots \
    -configuration Debug -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived" \
    CODE_SIGNING_ALLOWED=NO) > "$log" 2>&1; then
  grep -E "error:|\*\* " "$log" | head -n 40 || true
  tail -n 30 "$log"
  fail "build-for-testing failed, see $(basename "$log")"
fi

# --- run once: every language in one test.
IFS=',' read -r -a list <<< "$locales"
for locale in "${list[@]}"; do
  rm -rf "$root/fastlane/screenshots_raw/$locale/$shots"
done
suffix="${SHARD:+-${SHARD/\//of}}"
log="$logs/screens-$kind$suffix.log"
bundle="$root/build/ios/screens-$kind$suffix.xcresult"
# The macOS image can lose the simulator before the test runner is installed:
# SimLaunchHost crashes (SIGBUS in dyld) and takes the booted device with it,
# and xcodebuild reports "Mach error -308 - (ipc/mig) server died". Nothing has
# been captured at that point, so a fresh boot and another run lose nothing.
attempts=3
for ((attempt = 1; ; attempt++)); do
  status=0
  (cd ios && TEST_RUNNER_SCREENSHOT_DIR="$root/fastlane/screenshots_raw" TEST_RUNNER_SCREENSHOT_KIND="$shots"     TEST_RUNNER_SCREENSHOT_ORIENTATION="${ORIENTATION:-}" \
      TEST_RUNNER_SCREENSHOT_LOCALES="$pairs" TEST_RUNNER_SCREENSHOT_SCENES="$scenes" \
      xcodebuild test-without-building -project TimelineExporter.xcodeproj -scheme StoreScreenshots \
      -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived" \
      -only-testing:TimelineExporterUITests/StoreScreenshots \
      -resultBundlePath "$bundle") > "$log" 2>&1 || status=$?
  ((status)) && ((attempt < attempts)) && grep -q "Failed to install or launch the test runner" "$log" || break

  echo "::warning title=Screenshots ($kind)::attempt $attempt of $attempts: the test runner could not be installed, the simulator went down; booting it again"
  # Kept for the logs artifact, and xcodebuild refuses an existing bundle path.
  mv "$log" "${log%.log}-attempt$attempt.log"
  mv "$bundle" "${bundle%.xcresult}-attempt$attempt.xcresult" 2>/dev/null || true
  xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
  # CoreSimulatorService needs about 20 s to reconnect to a new SimLaunchHost.
  sleep 30
  boot_simulator || fail "the simulator $udid did not boot again after attempt $attempt"
done

for locale in "${list[@]}"; do
  # A run that died early leaves folders missing, and pipefail must not stop
  # the script here: the reason is reported below.
  n="$({ find "$root/fastlane/screenshots_raw/$locale/$shots" -name '*.png' 2>/dev/null || true; } | wc -l | tr -d ' ')"
  echo "== $locale: $n shot(s)"
done
count="$({ find "$root/fastlane/screenshots_raw" -path "*/$shots/*.png" 2>/dev/null || true; } | wc -l | tr -d ' ')"
echo "$count raw $shots screenshot(s)"

if ((status)); then
  # The test's messages read "[de-DE] 03_map_fullscreen: the map never appeared".
  errors="$(grep -oE "failed - \[[^]]+\] .*|error: .*" "$log" | sed 's/^failed - //' | sort -u || true)"
  echo "$errors" | head -n 40
  # No "[locale]" line at all is the case to explain: the test runner crashed.
  { echo "$errors" | grep -E '^\[' || true; } | head -n 20 | while read -r line; do
    echo "::warning title=Screenshots ($kind)::$line"
  done
  bad="$(echo "$errors" | grep -oE '^\[[^]]+\]' | tr -d '[]' | sort -u | tr '\n' ' ' || true)"
  tail -n 15 "$log"
  fail "the capture failed${bad:+ for: $bad}(see $(basename "$log"))"
fi
