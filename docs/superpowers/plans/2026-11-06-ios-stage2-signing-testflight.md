# iOS Stage 2: Signing and TestFlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ios.yml` modes `build` (signed `.ipa` artifact) and `testflight` (upload to App Store Connect, wait for processing) work from GitHub secrets, so the empty app reaches the owner's iPhone through TestFlight.

**Architecture:** Port NEARCOIL's proven signing chain (read-only reference: `G:\ClaudeCowork\NEARCOIL\tools\ios\{signing.sh,cleanup.sh,asc_builds.py,build_ipa.sh}` and its `.github/workflows/ios.yml`) to this repo's XcodeGen project. Secrets are checked on Linux first; macOS imports the certificate into a throwaway keychain, archives with manual signing, exports the `.ipa`, and uploads with `xcodebuild -exportArchive` + an App Store Connect API key. Processing is awaited on Linux. The app also gets the 1024 px App Store icon that an upload requires.

**Tech Stack:** GitHub Actions (`ubuntu-latest`, `xcode-27`), bash, Python 3 stdlib only (+ `resvg_py` locally for the icon tool), XcodeGen, xcodebuild, openssl.

**Spec:** `docs/superpowers/specs/2026-11-06-ios-port-design.md` (section 4 "CI", stage 2 row: "Signing + TestFlight (owner: App ID, profile, secrets) — First build on the owner's iPhone").

## Global Constraints

- Bundle ID `com.nikitapetrovapps.timelineexporter`, team `87H7UW6D73`, `MARKETING_VERSION` stays `1.0.0` (from `ios/project.yml`; CI never writes it back).
- Repository secrets, exactly these names: `APPLE_TEAM_ID`, `IOS_BUNDLE_ID`, `IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64`, `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`, `APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID`, `APPSTORE_PRIVATE_KEY` (PEM text of the `.p8`, not base64).
- `validate` needs **no** secrets (it runs on every iOS-touching push to master and must stay green before the owner adds them). `build` needs Team/Bundle ID + certificate + password + profile; `testflight` additionally the three `APPSTORE_*`.
- `testflight` runs only from `refs/heads/master` (the spec's "main"; this repo's default branch is master).
- Build number (`CFBundleVersion`) = `YYMMDD.HHMMSS.attempt` in UTC, leading zero of HHMMSS dropped; an optional `build_number` input overrides it; checked against App Store Connect (read-only) before the macOS job and again right before upload.
- Signing manual, never automatic: no certificate or profile is created or changed by CI. No secret value is ever printed; signing material lives only in `$RUNNER_TEMP` and is removed by an `if: always()` cleanup step.
- The upload never submits for review and never adds external testers.
- Every action stays pinned by full SHA with a version comment, using the pins already in `ios.yml` (`checkout 3d3c42e5…`, `setup-java 77dd5b2c…`, `setup-gradle 8da46b53…`, `upload-artifact 043fb46d…`).
- Failures must be readable without a GitHub login: every failing step emits a `::error title=…::` annotation (or goes through `tools/ci_annotate.py`).
- Code, comments, docs and commit messages in English. Commits on master, long-form messages (imperative title, blank line, why-paragraphs, final `Checked:` line), no attribution lines. Never `git push`.
- Windows host: Python with non-ASCII output needs `PYTHONIOENCODING=utf-8`.

---

### Task 1: App Store icon

An App Store upload is rejected without a 1024×1024 opaque app icon (ITMS-90704/90717). The Android icon is a vector (`app/src/main/res/drawable/ic_launcher_background.xml` = solid `#1976D2`; `ic_launcher_foreground.xml` = three white paths in a 108×108 viewport, one stroked). Render the same artwork at 1024 px for iOS.

**Files:**
- Create: `tools/ios/make_app_icon.py`
- Create: `tools/ios/test_make_app_icon.py`
- Create: `ios/TimelineExporter/Resources/Assets.xcassets/Contents.json`
- Create: `ios/TimelineExporter/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json`
- Create: `ios/TimelineExporter/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` (generated, committed)
- Modify: `ios/project.yml` (app target settings)

**Interfaces:**
- Produces: `vector_to_svg(background_xml: str, foreground_xml: str) -> str` (an SVG document, viewBox `0 0 108 108`, background rect first, then each `<path>` with `fill`/`stroke`/`stroke-width`/`stroke-linecap`/`stroke-linejoin` mapped from the `android:` attributes; `fillColor`/`strokeColor` of the form `#AARRGGBB` become `#RRGGBB` + opacity). `main()` writes the PNG.
- Asset catalog name `AppIcon`; build setting `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon`.

- [ ] **Step 1: Write failing tests** in `tools/ios/test_make_app_icon.py` (stdlib `unittest`, runnable as `python3 tools/ios/test_make_app_icon.py`, same style as `tools/ios/test_simulator_udid.py`):
  - a minimal background vector (one path, `fillColor="#1976D2"`) and a foreground with one filled and one stroked path → the SVG contains `viewBox="0 0 108 108"`, the background path before the foreground ones, `fill="#1976D2"`, the stroke attributes, and `fill="none"` on the stroke-only path;
  - `#80FFFFFF` → `fill="#FFFFFF"` with `fill-opacity="0.502"` (3 decimals);
  - the real files under `app/src/main/res/drawable/` convert without error and give exactly 1 + 3 `<path` elements.
  The tests must not import `resvg_py` (CI preflight has no such package): keep rendering in a function only `main()` calls, importing `resvg_py` lazily there.
- [ ] **Step 2: Run** `python tools/ios/test_make_app_icon.py` → FAIL (module missing).
- [ ] **Step 3: Implement** `tools/ios/make_app_icon.py`: parse both XMLs with `xml.etree.ElementTree` (namespace `http://schemas.android.com/apk/res/android`), build the SVG, and in `main()` render it with `resvg_py.svg_to_bytes(svg_string=..., width=1024, height=1024)`, then flatten to opaque RGB with Pillow (App Store icons must have no alpha) and save to the appiconset path. Usage line at the top: `pip install resvg-py pillow; python tools/ios/make_app_icon.py`. Default paths relative to the repo root (`Path(__file__).resolve().parents[2]`).
- [ ] **Step 4: Run tests** → PASS. Run the tool; check with Pillow that the PNG is `(1024, 1024)`, mode `RGB`, the corner pixel is `(25, 118, 210)` and the centre region contains white pixels. Look at the image (Read tool on the PNG) and compare with `playstore/ic_launcher-playstore.png`: same artwork, sharper.
- [ ] **Step 5: Asset catalog.** `Assets.xcassets/Contents.json`: `{"info":{"author":"xcode","version":1}}`. `AppIcon.appiconset/Contents.json` — single-size iOS icon:
  ```json
  {"images":[{"filename":"AppIcon-1024.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}
  ```
  In `ios/project.yml`, app target `settings.base`: add `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon`. The catalog is already inside the `TimelineExporter` sources path, so XcodeGen picks it up. Update the comment at the top of `project.yml` ("Signing arrives in stage 2.") to say signing comes from CI (`tools/ios/build_ipa.sh`).
- [ ] **Step 6: Add the new test file** to the "CI script tests" step of `.github/workflows/ios.yml` (`python3 tools/ios/test_make_app_icon.py`). Without `resvg_py` it must still pass there.
- [ ] **Step 7: Commit** (`tools/ios/make_app_icon.py`, its test, the asset catalog, `ios/project.yml`, `ios.yml`).

### Task 2: Signing, build-number and IPA scripts

Port the NEARCOIL scripts. Read each NEARCOIL file in full before porting; keep their checks and messages, change only what is listed.

**Files:**
- Create: `tools/ios/asc_builds.py` — copy of NEARCOIL's, unchanged except the docstring examples use version `1.0.0`.
- Create: `tools/ios/test_asc_builds.py`
- Create: `tools/ios/signing.sh` — copy of NEARCOIL's; comment example profile name becomes `"Timeline Exporter App Store"`. On every `ERROR:` line also emit an annotation: define `fail() { echo "ERROR: $*"; echo "::error title=Signing::$*"; exit 1; }` and use it for the error exits (multi-line hints stay as plain `echo` before `fail`). The Python heredoc prints `ERROR:` lines; additionally print `::error title=Signing::<msg>` for each.
- Create: `tools/ios/cleanup.sh` — copy of NEARCOIL's.
- Create: `tools/ios/build_ipa.sh` — NEARCOIL's, adapted (below).
- Create: `tools/ios/check_ipa.py`
- Create: `tools/ios/test_check_ipa.py`

**Interfaces:**
- `asc_builds.py format|check|wait` as in NEARCOIL (env `APPSTORE_KEY_ID`, `APPSTORE_ISSUER_ID`, `APPSTORE_PRIVATE_KEY`, `IOS_BUNDLE_ID`). Functions `format_errors(build) -> list[str]`, `key_tuple(build) -> tuple[int,int,int]`, `der_to_raw(sig: bytes) -> bytes`.
- `signing.sh`: input env `IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64`, `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`, `APPLE_TEAM_ID`, `IOS_BUNDLE_ID`; output to `$GITHUB_ENV`: `IOS_PROFILE_UUID`, `IOS_PROFILE_NAME`, `IOS_KEYCHAIN`.
- `build_ipa.sh <out-dir> archive|upload`. Env: `APPLE_TEAM_ID`, `IOS_BUNDLE_ID`, `IOS_PROFILE_NAME`, `IOS_KEYCHAIN`, `BUILD_NUMBER`; upload also `APPSTORE_*`. Produces `<out-dir>/TimelineExporter.xcarchive`, `<out-dir>/ipa/TimelineExporter.ipa`, logs in `<out-dir>/logs/xcodebuild-{archive,export,upload}.log`.
- `check_ipa.py <ipa> --bundle-id ID --version V --build B` → exit 0 / 1, prints `ERROR: …` and `::error title=IPA check::…` per problem, `IPA check: all checks passed` on success. Function `ipa_errors(ipa_path, bundle_id, version, build) -> list[str]`.

**build_ipa.sh changes from NEARCOIL:**
- The project is fixed: `PROJECT="$REPO/ios/TimelineExporter.xcodeproj"` where `REPO` = `$(cd "$(dirname "$0")/../.." && pwd)`; scheme `TimelineExporter`. Drop the `xcodebuild -list` scheme detection and the "one .xcodeproj in $OUT" check; instead fail with `ERROR: ios/TimelineExporter.xcodeproj not found - run xcodegen generate in ios/ first.` if it is missing.
- Archive path `$OUT/TimelineExporter.xcarchive`.
- `xcodebuild archive` additionally gets `CURRENT_PROJECT_VERSION="$BUILD_NUMBER"` (required env; fail if empty), and `-destination 'generic/platform=iOS'` as before. Keep `CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM CODE_SIGN_IDENTITY="Apple Distribution" PROVISIONING_PROFILE_SPECIFIER OTHER_CODE_SIGN_FLAGS="--keychain $IOS_KEYCHAIN"`. The scheme's archive builds the app target only (the test target is `[test]`), so these settings never hit the test bundle.
- After export, rename the single `.ipa` in `$OUT/ipa` to `TimelineExporter.ipa` (fail if there is not exactly one).
- Error lines also go out as annotations: `echo "::error title=xcodebuild archive::see ios-build-logs/xcodebuild-archive.log"` etc.; the detailed errors come from `ci_annotate.py --xcodebuild` in the workflow (Task 3).
- Error messages say "ios-build-logs" artifact (same name Task 3 uses).

**check_ipa.py checks** (stdlib `zipfile` + `plistlib`):
1. exactly one `Payload/*.app/` directory, named `TimelineExporter.app`;
2. its `Info.plist`: `CFBundleIdentifier == bundle_id`, `CFBundleShortVersionString == version`, `CFBundleVersion == build`, `MinimumOSVersion == "17.0"`, `UIDeviceFamily` contains 1 and 2, `ITSAppUsesNonExemptEncryption is False`, `CFBundleIconName == "AppIcon"`;
3. `Payload/TimelineExporter.app/embedded.mobileprovision` exists and is non-empty;
4. `Payload/TimelineExporter.app/Assets.car` exists (compiled icon catalog);
5. no `Payload/TimelineExporter.app/Frameworks/Shared.framework/` entries (Shared is static and must not be embedded).

- [ ] **Step 1: Write failing tests.**
  - `test_asc_builds.py`: `format_errors` accepts `261106.93012.1`, `1`, `1.0.0`; rejects `0.1`, `01.2`, `1.2.3.4`, `1.a`, a 19-character number, a part above 2^31-1. `key_tuple("1.2") == (1, 2, 0)` and `key_tuple("261106.93012.2") > key_tuple("261106.93012.1")`. `der_to_raw` on a hand-built DER `30 len 02 21 00<32 bytes r> 02 20 <32 bytes s>` returns r‖s of 64 bytes, and short integers are left-padded to 32 bytes. `main()` with `format 261106.93012.1` returns 0 and with `format 0.1` returns 1 (patch `sys.argv`). No network.
  - `test_check_ipa.py`: build a synthetic `.ipa` in a temp dir with `zipfile` + `plistlib.dumps` holding a valid Info.plist, a 1-byte `embedded.mobileprovision` and `Assets.car` → `ipa_errors` is `[]`. Then one case per check (wrong bundle id, wrong build, missing profile, missing Assets.car, an embedded `Frameworks/Shared.framework/Shared`, encryption key missing) → exactly one matching error each.
- [ ] **Step 2: Run** both → FAIL (modules missing).
- [ ] **Step 3: Implement** the six files. `chmod +x` is not needed (the workflow calls `bash …`), but give the `.sh` files LF line endings (check `.gitattributes`; add `*.sh text eol=lf` if absent).
- [ ] **Step 4: Run** `python tools/ios/test_asc_builds.py`, `python tools/ios/test_check_ipa.py` → PASS. Run `bash -n` on the three `.sh` files. If `shellcheck` is available, run it; otherwise note that it was not.
- [ ] **Step 5: Add** both new test files to the "CI script tests" step of `ios.yml`.
- [ ] **Step 6: Commit.**

### Task 3: ios.yml build and testflight modes

Replace the stage-1 "Mode" stub with NEARCOIL's mode layout, adapted to this repo. Keep every validate step from stage 1 as it is (same names, same commands), so validate stays green.

**Files:**
- Modify: `.github/workflows/ios.yml`
- Modify: `tools/ci_annotate.py` only if a new log needs it (it should not: `--xcodebuild` takes any number of logs and skips missing/succeeded ones — verify this in the code before relying on it).

**Interfaces:**
- Consumes: Task 2's scripts and env contracts; Task 1's asset catalog (nothing to do here).
- Produces: workflow inputs `mode` (validate|build|testflight, default validate) and `build_number` (string, optional); preflight outputs `build_base`, `build_auto`, `marketing_version`; ios job output `build_number`; artifact `TimelineExporter-iOS-<version>-<build>` (`.ipa`, 3 days); artifact `ios-build-logs` on failure.

**Changes:**
- [ ] **Step 1: Header comment** — describe the three modes as real, the secrets each needs (Global Constraints list), "testflight master only", "Uploaded builds are never submitted for review", and point to `docs/IOS_BUILD.md`.
- [ ] **Step 2: Inputs and env.** Add input `build_number` (as NEARCOIL). Workflow `env`: keep `MODE`, `XCODE_MAJOR`; add `EXPECTED_APPLE_TEAM_ID: 87H7UW6D73`, `EXPECTED_IOS_BUNDLE_ID: com.nikitapetrovapps.timelineexporter` with NEARCOIL's comment (guard against a typo in the secrets).
- [ ] **Step 3: Preflight steps, in this order:**
  1. `Trusted ref check` — `if: env.MODE == 'testflight' && github.ref != 'refs/heads/master'` → `::error title=testflight::testflight runs only from master (requested: $GITHUB_REF)` and exit 1.
  2. `Required secrets` — NEARCOIL's step with `HAS_*` booleans, except: `validate` needs nothing; `build` needs `APPLE_TEAM_ID IOS_BUNDLE_ID IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64 IOS_DISTRIBUTION_CERTIFICATE_PASSWORD IOS_PROVISIONING_PROFILE_BASE64`; `testflight` adds the three `APPSTORE_*`. Each missing one also prints `::error title=Missing secret::<NAME> (Settings -> Secrets and variables -> Actions)`.
  3. checkout (existing).
  4. `Secrets match the project` — `if: env.MODE != 'validate'`; with `APPLE_TEAM_ID`/`IOS_BUNDLE_ID` from secrets: compare to `EXPECTED_*` and fail with an annotation that names which one differs (never print the value). Also read `PRODUCT_BUNDLE_IDENTIFIER` and `DEVELOPMENT_TEAM` from `ios/project.yml` (with `grep`/`sed` or Python `re`; no PyYAML assumption) and check they equal `EXPECTED_*` — this check runs in **every** mode (it needs no secret).
  5. `Marketing version` (id `version`) — every mode: read `MARKETING_VERSION` from `ios/project.yml`, require `^[0-9]+\.[0-9]+\.[0-9]+$`, output `marketing_version`.
  6. `Build number` (id `number`) — every mode: NEARCOIL's step with `REQUESTED: ${{ inputs.build_number }}` and `python3 tools/ios/asc_builds.py format "$n"`; on a format error emit `::error title=Build number::…`.
  7. `Build number vs App Store Connect (read-only)` — `if: env.MODE == 'testflight'`, as NEARCOIL with `VERSION` from step 5.
  8. Then the existing setup-java, setup-gradle, "CI script tests", "Shared tests (JVM)", "Failure annotations" steps unchanged.
  Delete the stage-1 `Mode` step.
- [ ] **Step 4: ios job.** Name stays `iOS (${{ inputs.mode || 'validate' }})`. Add `outputs.build_number`; job env adds `APPLE_TEAM_ID`/`IOS_BUNDLE_ID` from secrets, `BUILD_NUMBER` (NEARCOIL's expression with `needs.preflight.outputs.*`), `MARKETING_VERSION: ${{ needs.preflight.outputs.marketing_version }}`, `BUILD_DIR: ${{ github.workspace }}/build/ios`. Keep `LOGS` = `$BUILD_DIR/logs` (the same path as now). Steps:
  1. `Build number` (id `number`) — prints version/build/attempt, writes the output.
  2. `Toolchain` (existing; also append `XCODE_VERSION`, `IPHONEOS_SDK` (`xcrun --sdk iphoneos --show-sdk-version`), `MACOS_VERSION` to `$GITHUB_ENV` for the summary; fail with an annotation if the iphoneos SDK is missing).
  3. checkout (existing).
  4. `Signing` — `if: env.MODE != 'validate'`, the three signing secrets as env, `run: bash tools/ios/signing.sh`. Before Gradle, so a bad certificate fails in a minute.
  5. setup-java, setup-gradle, Shared tests + xcframework, XcodeGen (existing).
  6. `Unsigned device build` — add `if: env.MODE == 'validate'`.
  7. `Simulator tests` — runs in every mode (the build must not ship untested code).
  8. `Archive and export IPA` — `if: env.MODE != 'validate'`: `bash tools/ios/build_ipa.sh "$BUILD_DIR" archive`, then rename `ipa/TimelineExporter.ipa` to `ipa/TimelineExporter-iOS-${MARKETING_VERSION}-${BUILD_NUMBER}.ipa`.
  9. `Check IPA` — `if: env.MODE != 'validate'`: `python3 tools/ios/check_ipa.py "$BUILD_DIR"/ipa/*.ipa --bundle-id "$EXPECTED_IOS_BUNDLE_ID" --version "$MARKETING_VERSION" --build "$BUILD_NUMBER"`.
  10. `Upload IPA artifact` — `if: env.MODE != 'validate'`, NEARCOIL's step with the new name, `retention-days: 3`, `if-no-files-found: error`.
  11. `Upload to App Store Connect (TestFlight)` — `if: env.MODE == 'testflight'`, NEARCOIL's step (re-check with `asc_builds.py check`, then `build_ipa.sh "$BUILD_DIR" upload`), plus `IOS_BUNDLE_ID` in its env for the check.
  12. `Summary` — `if: always()`, NEARCOIL's table (Version, Build, Xcode, iphoneos SDK, macOS; no Godot row) with the title `### Timeline Exporter iOS - <mode>: <status>`; also emit one `::notice title=iOS <mode>::version <v>, build <b>, <status>` so the result is readable via the API.
  13. `Failure annotations` (existing) — add `--xcodebuild "$LOGS/xcodebuild-archive.log" --xcodebuild "$LOGS/xcodebuild-export.log" --xcodebuild "$LOGS/xcodebuild-upload.log"`.
  14. `Upload logs` (existing) — rename the artifact to `ios-build-logs`.
  15. `Remove signing material` — `if: always()`, `bash tools/ios/cleanup.sh` (guard: skip if the script is absent, i.e. failure before checkout: `if: always() && hashFiles('tools/ios/cleanup.sh') != ''`).
- [ ] **Step 5: testflight-status job** — NEARCOIL's, `needs: [preflight, ios]`, `if: inputs.mode == 'testflight'`, waits 20 minutes with `asc_builds.py wait`; on `INVALID/FAILED` also emit `::error title=TestFlight processing::…` (add this to `asc_builds.py wait` itself if Task 2 did not, keeping its tests green).
- [ ] **Step 6: Concurrency** — keep `ios-${{ github.ref }}`, no cancel. Update its comment (uploads are real now).
- [ ] **Step 7: Verify locally.** Parse the YAML with Python (`yaml.safe_load` if PyYAML is installed, otherwise note it). If `actionlint` is available run it; else note it. Re-read every `if:` — `inputs.mode` is empty on push, so conditions use `env.MODE`, except job-level `if:` (where `env` is not available: use `inputs.mode == 'testflight'`, which is correctly false on push). Confirm a push run still goes: preflight (no secrets needed) → ios validate with the unsigned build and simulator tests.
- [ ] **Step 8: Commit.**

### Task 4: Owner documentation

**Files:**
- Create: `docs/IOS_BUILD.md`
- Modify: `ios/README.md` (link to `docs/IOS_BUILD.md`, remove "signing in stage 2" wording)

Content of `docs/IOS_BUILD.md` (English, concise, modelled on NEARCOIL's `docs/IOS_BUILD.md` sections "GitHub secrets", "Running the workflow", "Build number", "First run", "TestFlight", "Security", "Troubleshooting" — read it first):
- What each mode does and which secrets it needs (table).
- The secrets table (format column: base64 vs PEM text) and which values are shared with NEARCOIL (Team ID, the Apple Distribution certificate + password, the App Store Connect Team API key) versus Timeline-only (Bundle ID, App Store provisioning profile).
- One-time Apple steps, numbered: register App ID `com.nikitapetrovapps.timelineexporter` (Explicit, no capabilities) in developer.apple.com → Identifiers; create the app in App Store Connect (platform iOS, name "Timeline Exporter", primary language English (U.S.), the Bundle ID, SKU `timelineexporter-ios`, user access Full Access); create an **App Store Connect** distribution provisioning profile for that App ID with the existing Apple Distribution certificate, named `Timeline Exporter App Store`; base64 it (Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("path"))`); add the secrets.
- Running: Actions → iOS → Run workflow → branch master → mode. Expected success lines per mode (the signing script's "Certificate/private-key pair and provisioning profile verified", "IPA check: all checks passed", "Upload accepted by App Store Connect", testflight-status `PROCESSING` → `VALID`).
- TestFlight: internal testing group, add yourself, install the TestFlight app on the iPhone, accept the invitation; builds expire after 90 days; export compliance is answered by `ITSAppUsesNonExemptEncryption = NO`.
- Build number rule.
- Troubleshooting table: the error message of each signing/upload check → the fix (profile App ID mismatch → wrong profile; certificate not in profile → edit the profile; .p12 import fails → wrong password or re-export with `-legacy`; HTTP 401/403 from App Store Connect; "no app with IOS_BUNDLE_ID" → create the app record first).
- Security: what never leaves `$RUNNER_TEMP`, cleanup step, masked Team/Bundle ID in logs.

- [ ] **Step 1:** Read NEARCOIL's `docs/IOS_BUILD.md` and the final `ios.yml` + scripts from Tasks 2-3 (the doc must quote their real messages).
- [ ] **Step 2:** Write the files.
- [ ] **Step 3:** Commit.

---

## Out of scope

UI, Swift adapter, real features (stage 3+); App Store listing and screenshots; in-app purchases; submitting for review; external testers; changing `MARKETING_VERSION`.
