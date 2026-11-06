#!/usr/bin/env bash
# Removes everything related to signing and the App Store Connect key: the
# temporary keychain, installed profiles, files in $RUNNER_TEMP. Runs as the
# job's last step with if: always(), after success and failure alike. GitHub
# runners are throwaway, but nothing relies on that: no later step should find
# signing material.
set -uo pipefail

if [[ -n "${IOS_KEYCHAIN:-}" && -e "$IOS_KEYCHAIN" ]]; then
  security delete-keychain "$IOS_KEYCHAIN" && echo "temporary keychain deleted"
fi
if [[ -n "${IOS_PROFILE_UUID:-}" ]]; then
  rm -f "$HOME/Library/MobileDevice/Provisioning Profiles/$IOS_PROFILE_UUID.mobileprovision" \
        "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles/$IOS_PROFILE_UUID.mobileprovision"
  echo "provisioning profile removed"
fi
rm -rf "${RUNNER_TEMP:?}/signing" "${RUNNER_TEMP:?}/asc"
# ExportOptions-*.plist hold nothing secret, but have no business next to the archive.
rm -f "${BUILD_DIR:-/nonexistent}"/ExportOptions-*.plist
exit 0
