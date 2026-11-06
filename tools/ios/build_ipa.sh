#!/usr/bin/env bash
# XcodeGen project (ios/TimelineExporter.xcodeproj) -> .xcarchive -> signed .ipa,
# and optionally the upload to App Store Connect.
#
#   tools/ios/build_ipa.sh <out-dir> archive   - archive + .ipa
#   tools/ios/build_ipa.sh <out-dir> upload    - upload the archive built before
#
# Environment: APPLE_TEAM_ID, IOS_BUNDLE_ID, IOS_PROFILE_NAME, IOS_KEYCHAIN
# (from signing.sh) and BUILD_NUMBER (CFBundleVersion, archive only);
# upload also needs APPSTORE_KEY_ID, APPSTORE_ISSUER_ID,
# APPSTORE_PRIVATE_KEY - the .p8 text as is (PEM, NOT base64).
#
# The upload is `xcodebuild -exportArchive` with destination=upload and an App
# Store Connect API key (-authenticationKey*): the same path Xcode's Organizer
# uses, not deprecated by Apple - unlike altool, which fastlane pilot relies on.
# No Ruby or gems on the expensive macOS runner either. The upload neither
# submits for review nor sends to external testers: the build just appears in
# TestFlight -> Builds.
set -euo pipefail

OUT="$(cd "${1:?usage: build_ipa.sh <dir> archive|upload}" && pwd)"
STEP="${2:?usage: build_ipa.sh <dir> archive|upload}"
LOGS="$OUT/logs"
ARCHIVE="$OUT/TimelineExporter.xcarchive"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PROJECT="$REPO/ios/TimelineExporter.xcodeproj"
SCHEME=TimelineExporter
mkdir -p "$LOGS"

export_options() {  # $1 = export | upload
  cat > "$OUT/ExportOptions-$1.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>$1</string>
  <key>teamID</key><string>$APPLE_TEAM_ID</string>
  <key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>Apple Distribution</string>
  <key>provisioningProfiles</key>
  <dict><key>$IOS_BUNDLE_ID</key><string>$IOS_PROFILE_NAME</string></dict>
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
  <key>testFlightInternalTestingOnly</key><false/>
</dict>
</plist>
PLIST
  plutil -lint "$OUT/ExportOptions-$1.plist" > /dev/null
}

if [[ "$STEP" == archive ]]; then
  if [[ ! -d "$PROJECT" ]]; then
    echo "ERROR: ios/TimelineExporter.xcodeproj not found - run xcodegen generate in ios/ first."
    echo "::error title=xcodebuild archive::ios/TimelineExporter.xcodeproj not found - run xcodegen generate in ios/ first."
    exit 1
  fi
  if [[ -z "${BUILD_NUMBER:-}" ]]; then
    echo "ERROR: BUILD_NUMBER is empty (CFBundleVersion for the archive)."
    echo "::error title=xcodebuild archive::BUILD_NUMBER is empty"
    exit 1
  fi
  echo "Project: $(basename "$PROJECT"), scheme: $SCHEME, build $BUILD_NUMBER"

  # Signing is explicit: Manual, Apple Distribution, profile by name, the
  # temporary keychain from signing.sh. The project may say Automatic;
  # command-line settings override it. The scheme archives the app target only
  # (the test target is [test]), so these settings never reach the test bundle.
  # The full log (tens of thousands of lines) goes to a file and to the
  # artifact on failure; the console gets only errors and the result.
  if ! xcodebuild archive \
      -project "$PROJECT" -scheme "$SCHEME" -configuration Release \
      -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
      CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
      CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
      CODE_SIGN_IDENTITY="Apple Distribution" \
      PROVISIONING_PROFILE_SPECIFIER="$IOS_PROFILE_NAME" \
      OTHER_CODE_SIGN_FLAGS="--keychain $IOS_KEYCHAIN" \
      > "$LOGS/xcodebuild-archive.log" 2>&1 || [[ ! -d "$ARCHIVE" ]]; then
    echo "ERROR: xcodebuild archive failed - see ios-build-logs/xcodebuild-archive.log."
    echo "::error title=xcodebuild archive::see ios-build-logs/xcodebuild-archive.log"
    awk '/error:/ && n++ < 30' "$LOGS/xcodebuild-archive.log"
    tail -n 20 "$LOGS/xcodebuild-archive.log"
    exit 1
  fi
  tail -n 3 "$LOGS/xcodebuild-archive.log"
  plutil -p "$ARCHIVE/Info.plist" | grep -E "CFBundleShortVersionString|CFBundleVersion|SigningIdentity" || true

  export_options export
  if ! xcodebuild -exportArchive -archivePath "$ARCHIVE" \
      -exportOptionsPlist "$OUT/ExportOptions-export.plist" -exportPath "$OUT/ipa" \
      > "$LOGS/xcodebuild-export.log" 2>&1; then
    echo "ERROR: IPA export failed - see ios-build-logs/xcodebuild-export.log:"
    echo "::error title=xcodebuild export::see ios-build-logs/xcodebuild-export.log"
    tail -n 40 "$LOGS/xcodebuild-export.log"
    exit 1
  fi
  shopt -s nullglob
  ipas=("$OUT"/ipa/*.ipa)
  shopt -u nullglob
  if [[ ${#ipas[@]} -ne 1 ]]; then
    echo "ERROR: expected exactly one .ipa in $OUT/ipa, found ${#ipas[@]}."
    echo "::error title=xcodebuild export::expected exactly one .ipa in the export, found ${#ipas[@]}"
    exit 1
  fi
  [[ "${ipas[0]}" == "$OUT/ipa/TimelineExporter.ipa" ]] || mv "${ipas[0]}" "$OUT/ipa/TimelineExporter.ipa"
  ls -la "$OUT/ipa"

elif [[ "$STEP" == upload ]]; then
  for v in APPSTORE_KEY_ID APPSTORE_ISSUER_ID APPSTORE_PRIVATE_KEY; do
    if [[ -z "${!v:-}" ]]; then
      echo "ERROR: GitHub secret $v is empty or not configured."
      echo "::error title=Upload::GitHub secret $v is empty or not configured."
      exit 1
    fi
  done
  if [[ ! "$APPSTORE_KEY_ID" =~ ^[A-Z0-9]{10}$ ]]; then
    echo "ERROR: APPSTORE_KEY_ID must be the 10-character Key ID."
    echo "::error title=Upload::APPSTORE_KEY_ID must be the 10-character Key ID."
    exit 1
  fi
  if [[ ! "$APPSTORE_ISSUER_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
    echo "ERROR: APPSTORE_ISSUER_ID must be the Issuer ID UUID."
    echo "::error title=Upload::APPSTORE_ISSUER_ID must be the Issuer ID UUID."
    exit 1
  fi
  if [[ ! -d "$ARCHIVE" ]]; then
    echo "ERROR: no archive at $ARCHIVE - run the archive step first."
    echo "::error title=Upload::no archive at $ARCHIVE - run the archive step first."
    exit 1
  fi

  KEY_DIR="${RUNNER_TEMP:?}/asc"
  rm -rf "$KEY_DIR"
  (umask 077 && mkdir "$KEY_DIR")
  KEY="$KEY_DIR/AuthKey_$APPSTORE_KEY_ID.p8"
  trap 'rm -rf "$KEY_DIR"' EXIT
  # The secret is the PEM itself. If it was pasted as one line with literal
  # \n, the line breaks are restored; it is NOT base64-decoded.
  (umask 077 && printf '%s\n' "$APPSTORE_PRIVATE_KEY" | sed 's/\\n/\n/g' | tr -d '\r' > "$KEY")
  if ! head -n 1 "$KEY" | grep -q -- "-----BEGIN PRIVATE KEY-----" \
      || ! grep -q -- "-----END PRIVATE KEY-----" "$KEY"; then
    echo "ERROR: APPSTORE_PRIVATE_KEY is not the PEM text of AuthKey_<KeyID>.p8"
    echo "       (expected -----BEGIN PRIVATE KEY----- ... -----END PRIVATE KEY-----)."
    echo "::error title=Upload::APPSTORE_PRIVATE_KEY is not the PEM text of AuthKey_<KeyID>.p8"
    exit 1
  fi
  if ! openssl pkey -in "$KEY" -noout > /dev/null 2>&1; then
    echo "ERROR: APPSTORE_PRIVATE_KEY has PEM markers but is not a valid private key."
    echo "::error title=Upload::APPSTORE_PRIVATE_KEY has PEM markers but is not a valid private key."
    exit 1
  fi

  export_options upload
  # -allowProvisioningUpdates only lets xcodebuild go online with this key;
  # signing stays manual (signingStyle=manual), no profiles or certificates
  # are created or changed.
  if ! xcodebuild -exportArchive -archivePath "$ARCHIVE" \
      -exportOptionsPlist "$OUT/ExportOptions-upload.plist" -exportPath "$OUT/upload" \
      -allowProvisioningUpdates \
      -authenticationKeyPath "$KEY" \
      -authenticationKeyID "$APPSTORE_KEY_ID" \
      -authenticationKeyIssuerID "$APPSTORE_ISSUER_ID" \
      > "$LOGS/xcodebuild-upload.log" 2>&1; then
    echo "ERROR: App Store Connect rejected the upload - see ios-build-logs/xcodebuild-upload.log:"
    echo "::error title=xcodebuild upload::see ios-build-logs/xcodebuild-upload.log"
    grep -E "error|Error|ERROR" "$LOGS/xcodebuild-upload.log" | head -n 30 || true
    tail -n 30 "$LOGS/xcodebuild-upload.log"
    exit 1
  fi
  grep -iE "upload|export succeeded" "$LOGS/xcodebuild-upload.log" | tail -n 5 || true
  echo "Upload accepted by App Store Connect. Processing for TestFlight takes several minutes."
else
  echo "ERROR: unknown step '$STEP'"
  echo "::error title=build_ipa::unknown step '$STEP'"
  exit 1
fi
