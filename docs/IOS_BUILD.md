# Timeline Exporter on iOS: build and TestFlight

No Mac needed. The Gradle build of `shared`, the Xcode project, the signed
`.ipa` and the TestFlight upload all run on GitHub's `xcode-27` runner
(`.github/workflows/ios.yml`). Development stays on Windows. The workflow is
laid out like NEARCOIL's and uses the same scripts (`tools/ios/`) and secret
names. The source layout is in `ios/README.md`.

```text
preflight (ubuntu): secrets set, IDs match ios/project.yml, build number,
                    script tests, shared JVM tests
ios (xcode-27):     signing -> Shared.xcframework -> XcodeGen -> simulator tests
                    -> archive + .ipa -> IPA check -> upload (testflight only)
testflight-status (ubuntu): wait for processing, read-only
```

## What is configured

| | |
|---|---|
| Version | `MARKETING_VERSION` in `ios/project.yml` (CI never writes it back) |
| Team ID | `87H7UW6D73` (same team as NEARCOIL) |
| Bundle ID | `com.nikitapetrovapps.timelineexporter` |
| iOS | 17.0+, iPhone and iPad |
| Encryption | `ITSAppUsesNonExemptEncryption = NO`, so no export-compliance question |

`EXPECTED_APPLE_TEAM_ID` / `EXPECTED_IOS_BUNDLE_ID` at the top of `ios.yml`
are not secrets. The run compares the secrets with them and fails with
"differs from EXPECTED_..." on a typo, instead of a rejection from Apple half
an hour later. The "Project IDs" step also checks that `ios/project.yml` uses
the same two values.

## Running the workflow

GitHub -> Actions -> **iOS** -> Run workflow -> branch `master` -> `mode`
(optional `build_number`, see below):

| mode | What happens | Secrets |
|---|---|---|
| `validate` | preflight, Shared simulator tests, `Shared.xcframework`, XcodeGen, unsigned device build, XCTests on the simulator. No `.ipa` | none |
| `build` | the same checks and tests, plus the signing check, signed archive, `.ipa`, IPA check, artifact `TimelineExporter-iOS-<version>-<build>` (3 days) | Team ID, Bundle ID, certificate + password, profile |
| `testflight` | `build` plus the upload to App Store Connect and a wait for processing. **master only** | all eight |

`validate` also runs by itself on every push to master that touches iOS paths
(`ios/**`, `tools/ios/**`, `shared/src/**`, `ios.yml` and a few more). Nothing
is submitted for review and no external tester is invited: the build just
appears in TestFlight -> Builds.

The usual sequence is `validate` once, then `testflight` directly (it
includes everything `build` does).

### Expected success lines

| mode | Where | Line |
|---|---|---|
| `validate` | preflight "Required secrets" | `validate needs no secrets.` |
| `validate` | "Project IDs" | `ios/project.yml: team ..., bundle ...` (masked as `***` once the secrets exist) |
| `build`, `testflight` | "Signing" | `Certificate/private-key pair and provisioning profile verified.` |
| `build`, `testflight` | "Check IPA" | `IPA check: all checks passed` |
| `testflight` | preflight "Build number vs App Store Connect (read-only)" | `Build number <n> is new and higher than every build of <version>.` |
| `testflight` | "Upload to App Store Connect (TestFlight)" | `Upload accepted by App Store Connect. Processing for TestFlight takes several minutes.` |
| `testflight` | "TestFlight processing" job | `<version> (<build>): PROCESSING` repeating, then `VALID` and `Build processed: TestFlight -> Builds. It was not sent to external testers.` |

The "TestFlight processing" job gives up after 20 minutes with `Still
processing - that is normal; Apple reports the result by e-mail.` and still
succeeds. Check TestFlight -> Builds later.

### Build number

`CFBundleVersion` = `YYMMDD.HHMMSS.attempt` (UTC start of the run, leading
zero of HHMMSS dropped). It only grows and "Re-run failed jobs" gets a new,
higher number. `build_number` (optional input) overrides it. `asc_builds.py
check` compares the number with App Store Connect (read-only) in preflight and
again right before the upload.

## GitHub secrets

Settings -> Secrets and variables -> Actions -> Repository secrets. Exactly
these names:

| Secret | Format | Used by |
|---|---|---|
| `APPLE_TEAM_ID` | text, 10 characters | `build`, `testflight` |
| `IOS_BUNDLE_ID` | text | `build`, `testflight` |
| `IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64` | the `.p12` in **base64** | `build`, `testflight` |
| `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` | text | `build`, `testflight` |
| `IOS_PROVISIONING_PROFILE_BASE64` | App Store `.mobileprovision` **for this Bundle ID**, in **base64** | `build`, `testflight` |
| `APPSTORE_KEY_ID` | text, 10 characters | `testflight` |
| `APPSTORE_ISSUER_ID` | UUID | `testflight` |
| `APPSTORE_PRIVATE_KEY` | the **text** of `AuthKey_<KeyID>.p8`, pasted as is (`-----BEGIN PRIVATE KEY-----` ...). **Not** base64 | `testflight` |

Team ID and Bundle ID are secrets, so GitHub masks them as `***` in logs and
summaries, also inside paths and the profile name. That is expected.

### Shared with NEARCOIL and Timeline-only

- **Shared (same values as in NEARCOIL)**: `APPLE_TEAM_ID`, the Apple
  Distribution certificate (`..._P12_BASE64` + password), the App Store
  Connect Team API key (`APPSTORE_*`).
- **Timeline-only**: `IOS_BUNDLE_ID`, the App Store Connect app record and the
  provisioning profile (an App Store profile is tied to one Bundle ID).

GitHub never shows a secret value back, so keep the original files (`.p12`
and its password, `AuthKey_<KeyID>.p8`, Key ID, Issuer ID). Secrets are per
repository: copy the values over from the originals.

Keep the `.p12`, `.p8` and `.mobileprovision` outside the repository folder
(for example in `Documents\Apple`); `.gitignore` excludes them as a safety
net only.

Base64 on Windows (PowerShell), for both files; the result goes to the
clipboard, paste it into the secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\certificate.p12")) | Set-Clipboard
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\Timeline_Exporter_App_Store.mobileprovision")) | Set-Clipboard
```

`APPSTORE_PRIVATE_KEY` is not encoded: open `AuthKey_<KeyID>.p8` in Notepad,
copy everything and paste it into the secret.

## First run (one-time Apple steps)

1. developer.apple.com -> Certificates, Identifiers & Profiles -> Identifiers
   -> register an **Explicit** App ID `com.nikitapetrovapps.timelineexporter`.
   No capabilities.
2. App Store Connect -> Apps -> New App: platform iOS, name "Timeline
   Exporter", primary language English (U.S.), Bundle ID from step 1, SKU
   `timelineexporter-ios`, user access Full Access. App Store names are unique
   store-wide: if "Timeline Exporter" is taken, use e.g. "Timeline Exporter
   for Maps" (the home-screen name comes from the app, not this field).
3. Profiles -> new **App Store Connect** distribution profile for that App ID,
   with the existing Apple Distribution certificate, named `Timeline Exporter
   App Store`. Download the `.mobileprovision`.
4. Base64 the `.mobileprovision` (and the `.p12`) with the commands above and
   add all secrets from the table.
5. Actions -> iOS -> `validate`: green, no secrets needed.
6. Actions -> iOS -> `build`: the signing line, the IPA check and the artifact.
7. Actions -> iOS -> `testflight`: `Upload accepted ...`, then
   `PROCESSING` -> `VALID` in the "TestFlight processing" job.

## TestFlight

- App Store Connect -> Timeline Exporter -> TestFlight -> Internal Testing:
  create a group, add yourself (you must be an App Store Connect user).
- Install the TestFlight app on the iPhone and accept the invitation; the
  build appears there after processing (minutes to an hour, Apple e-mails).
- If a new build does not show up for testers, open the group -> Builds -> +
  and add it (only groups with Automatic Distribution get builds by themselves).
- Internal builds need no Beta App Review.
- A build expires after 90 days.
- An e-mail "ITMS-91053: Missing API declaration" after an upload is a
  warning, not a failure: TestFlight still works. The privacy manifest
  that silences it comes with a later stage.
- Export compliance is already answered by `ITSAppUsesNonExemptEncryption =
  NO`; App Store Connect does not ask.
- An App Store-signed `.ipa` does not install outside TestFlight and the App
  Store: do not send the artifact around.

## Security

- `*.p12`, `*.p8`, `*.mobileprovision`, `*.cer` are in `.gitignore`; keep them
  outside the repository folder anyway.
- Secrets reach only the steps that check or use them; the "Required secrets"
  step sees only "set / not set".
- Everything secret lives in `$RUNNER_TEMP` (`signing/`, `asc/`), never in
  `build/ios`, so the `.ipa` artifact and `ios-build-logs` hold none.
- The `.p12` is deleted right after import; the keychain password is random
  and never printed. The "Remove signing material" step (`cleanup.sh`, `if:
  always()`) deletes the temporary keychain, the installed profiles and the
  `.p8` copy.
- Team ID and Bundle ID are masked as `***` in logs.
- `contents: read` only, `persist-credentials: false`, no `pull_request`
  trigger; `testflight` refuses any ref but master ("testflight runs only from
  master").
- On failure the logs go to the artifact `ios-build-logs` (7 days).

## Troubleshooting

| Message | Fix |
|---|---|
| `MISSING <secret>` / `add the missing repository secrets` | The secret is absent or empty for this mode. |
| `secret APPLE_TEAM_ID differs from EXPECTED_APPLE_TEAM_ID in ios.yml` (same for `IOS_BUNDLE_ID`) | Typo in the secret, or the ID registered at Apple differs: fix the secret (or `EXPECTED_*` and `ios/project.yml`). |
| `DEVELOPMENT_TEAM / PRODUCT_BUNDLE_IDENTIFIER in ios/project.yml is ...` | `ios/project.yml` disagrees with `EXPECTED_*` in `ios.yml`. |
| `secret <name> is not valid base64.` | Re-encode the file with the PowerShell command above. |
| `IOS_PROVISIONING_PROFILE_BASE64 does not decode to a .mobileprovision.` | The secret holds another file; encode the downloaded `.mobileprovision`. |
| `could not import the .p12 - wrong IOS_DISTRIBUTION_CERTIFICATE_PASSWORD or unsupported .p12 encryption` | Check the password, or re-export the `.p12` with `openssl pkcs12 -export -legacy ...`. |
| `no valid 'Apple Distribution' identity of team APPLE_TEAM_ID in the .p12.` | Wrong type or team, expired or revoked certificate, or the `.p12` lacks its private key. |
| `provisioning profile App ID does not match APPLE_TEAM_ID.IOS_BUNDLE_ID (wildcard or another app's profile).` | Wrong profile (NEARCOIL's, or a wildcard): create the App Store profile for the Timeline App ID. |
| `provisioning profile Team ID does not match APPLE_TEAM_ID.` | Profile of another team. |
| `provisioning profile is not an App Store distribution profile (it lists devices or allows debugging).` | A Development or Ad Hoc profile: create an App Store Connect distribution one. |
| `the Apple Distribution certificate in the .p12 is not included in the provisioning profile.` | Edit the profile in Apple Developer, tick this certificate, download again, update the secret. |
| `provisioning profile expired on ...` | Regenerate the profile and update the secret. |
| `IPA check` errors (`Info.plist CFBundleVersion is ...`, `embedded.mobileprovision is missing`, ...) | The line says what is wrong; logs in `ios-build-logs`. |
| `xcodebuild archive failed` / `IPA export failed` | Read `xcodebuild-archive.log` / `xcodebuild-export.log` in the `ios-build-logs` artifact. |
| `APPSTORE_PRIVATE_KEY is not the PEM text of AuthKey_<KeyID>.p8` | The secret holds base64 or a path; paste the `.p8` text. |
| `App Store Connect HTTP 401 ...` | The API key is wrong or revoked, or the Key ID / Issuer ID are not its own. |
| `App Store Connect HTTP 403 ...` | The key has no access to this app; it needs the Admin or App Manager role. |
| `no app with IOS_BUNDLE_ID in App Store Connect (or the API key has no access).` | Create the app record first (step 2 above). |
| `build number <n> is already used` / `is not higher than ...` | Run again (new time) or pass a bigger `build_number`. |
| `App Store Connect rejected the upload` | The reason is at the end of the step and in `xcodebuild-upload.log`. Common: key role too low, version already submitted for review. |
| `App Store Connect rejected the build (INVALID/FAILED)` | The reason is in Apple's e-mail and in TestFlight -> Builds. |
| `the .p12 holds several Apple Distribution identities; export only one.` | Export the `.p12` again with only the one certificate and its key. |
| `provisioning profile platform is ..., not iOS.` | A macOS/tvOS profile: create an iOS App Store Connect profile. |
| `APPSTORE_KEY_ID must be the 10-character Key ID.` / `APPSTORE_ISSUER_ID must be the Issuer ID UUID.` | Copy them again from App Store Connect -> Users and Access -> Integrations -> App Store Connect API. |
| `openssl could not sign with APPSTORE_PRIVATE_KEY (not an EC P-256 key?)` | The secret is not the `.p8` text; paste the whole file including the BEGIN/END lines. |
| `Shared.xcframework not found ...` / `XcodeGen` errors | A build problem in the repository, not in your setup: tell the developer. |
| `testflight runs only from master` | Run the workflow on branch `master`. |
| `runner provides 'Xcode ...', the workflow expects Xcode 27` | The `xcode-27` image served another Xcode; no silent fallback by design. |
