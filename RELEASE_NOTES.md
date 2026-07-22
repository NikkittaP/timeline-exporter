# Release Notes — v1.4.0 (versionCode 7)

## Highlights
Maintenance release. Google Play Billing Library was upgraded from 7.1.1 to
9.1.0 to meet Google's requirement that all app updates published after
**31 August 2026** use Billing Library 8 or later. Nothing changes for people
who never open the tip jar — parsing, filtering and export are untouched.

## Changed (technical)
- **Play Billing Library 7.1.1 → 9.1.0** (`com.android.billingclient:billing-ktx`).
  Went straight to 9 rather than 8: the breaking change is identical for both,
  but PBL 8 is itself retired on 31 August 2027, so 9 buys roughly another year.
- **`queryProductDetailsAsync` callback rewritten** for the PBL 9 signature.
  The second parameter is now a non-null `QueryProductDetailsResult` exposing
  `productDetailsList` and `unfetchedProductList`, replacing PBL 7's nullable
  `List<ProductDetails>`.
- **Unfetched products are now logged.** If Play can't return a tip product
  (not configured, not active, unavailable in the user's country), the product
  ID, type and status code go to logcat with `Log.w`. Previously such products
  were silently dropped by `mapNotNull` and the tip jar simply rendered short
  with no explanation.
- **Automatic service reconnection enabled** (`enableAutoServiceReconnection()`).
  The library now re-establishes a dropped billing connection on its own instead
  of waiting for the user to hit retry. `onBillingServiceDisconnected()` is
  log-only by design — calling `startConnection()` there would fight the
  library's own retry.
- **Blocked-Play-Store errors now classify correctly.** In PBL 9, a Play Store
  blocked by the system (for example OEM kids mode) reports
  `BILLING_UNAVAILABLE` instead of a generic `ERROR`. `TipJarViewModel` already
  routed `BILLING_UNAVAILABLE` to `TipJarState.Unavailable`, so this scenario
  starts being handled properly with no code change.
- ProGuard rules unchanged — verified that billing ships its own consumer rules
  and the new PBL 9 result types need none. Documented in `proguard-rules.pro`.

## Not included
Sub-response codes (`PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS`,
`USER_INELIGIBLE`) from `launchBillingFlow()` are available in PBL 9 but not
consumed yet. Deliberately deferred to keep this release a minimal, easily
revertable diff.

## No changes to
Parser, filter, export formats, map, localization, UI. No new permissions, no
new dependencies, still no analytics or telemetry.

## Pre-release checklist
- [ ] `./gradlew testDebugUnitTest` — parser tests still green (billing is not
      covered by unit tests)
- [ ] `./gradlew assembleRelease` — confirms R8 builds against PBL 9 without
      new keep rules
- [ ] Confirm no compile warnings about removed PBL APIs
- [ ] Upload AAB to **internal testing**, install as a licensed tester, and
      verify: tip jar lists all three products with localized prices; a purchase
      completes and is consumed; the thank-you state appears; reopening the
      dialog shows products again (not ITEM_ALREADY_OWNED)
- [ ] Airplane mode → open tip jar → restore network: verify auto-reconnection
      recovers without an app restart
- [ ] Check logcat (`TipJarVM`) for "Product not fetched" warnings — none
      expected if all three SKUs are active
