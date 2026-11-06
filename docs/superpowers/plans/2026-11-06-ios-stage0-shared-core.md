# iOS port, stage 0: shared KMP core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the parser, filter, export and trip detection out of `app` into a Kotlin Multiplatform module `shared` whose `commonMain` compiles for JVM and iOS, with the Android app behaving byte-for-byte as before.

**Architecture:** Done in green steps. First a golden-hash test pins today's exports. Then the four packages move unchanged into `shared/src/jvmMain` (Android consumes the `jvm` target). Then Jackson is replaced by our own `JsonReader` over `kotlinx-io`, then `java.time`/`String.format`/`BigDecimal` are replaced with common code and everything moves to `commonMain`. The golden test must pass after every task.

**Tech Stack:** Kotlin 2.4.21 (KMP plugin), AGP 9.5.0 (built-in Kotlin in `app`), kotlinx-datetime 0.8.0 (`kotlin.time.Instant`), kotlinx-io-core 0.9.2, kotlin.test, JUnit 4, GitHub Actions (ubuntu + macOS).

**Spec:** `docs/superpowers/specs/2026-11-06-ios-port-design.md` (section 1 and stage 0).

## Global Constraints

- Android app output must not change: every export of the golden inputs hashes identically before and after (Task 2 test).
- Kotlin package stays `io.github.nikkittap.timelineexporter.*`; file and class names stay.
- `shared` targets: `jvm()`, `iosArm64()`, `iosSimulatorArm64()`. No Android target in `shared`; `app` consumes the `jvm` variant.
- JVM bytecode target 11 (matches `app`'s `JavaVersion.VERSION_11`).
- No Jackson and no `java.*` imports in `commonMain` at the end of the stage.
- Parse time of `F:/Timeline/Timeline_merged_big.json` with the new reader: at most 1.2× the Jackson baseline measured in Task 2.
- Real-file tests stay developer-local: skip (assume) when `F:/Timeline/...` files are missing; CI runs everything else.
- The UI helpers `MapImageLayout`, `RouteMotion`, `TrackDays`, `WhatsNewNotes` stay in `app` in this stage (they use `R` and locale formatting). Recorded in the spec in Task 7.
- iOS targets cannot compile on Windows. Locally, `gradlew` builds JVM only (`kotlin.native.ignoreDisabledTargets=true`); iOS compilation and tests run in CI on macOS (Task 7).
- Commits on `master`, long-form messages in the repository's style (what changed and why, a "Checked:" line).

## Model routing

| Task | Implementer | Reviewer |
|---|---|---|
| 1 Scaffold `shared` | sonnet | sonnet |
| 2 Golden hashes + benchmark baseline | sonnet | sonnet |
| 3 Move packages to `shared/jvmMain` | sonnet | sonnet |
| 4 `JsonReader` (TDD) | opus | opus |
| 5 Parser on `JsonReader` | opus | opus |
| 6 Common time/format, move to `commonMain` | opus | opus |
| 7 CI `shared.yml`, spec note, final check | sonnet | sonnet (final whole-stage review: opus) |

---

### Task 1: Scaffold the `shared` module

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`
- Create: `shared/build.gradle.kts`, `shared/src/commonMain/kotlin/io/github/nikkittap/timelineexporter/SharedInfo.kt`, `shared/src/commonTest/kotlin/io/github/nikkittap/timelineexporter/SharedInfoTest.kt`

**Interfaces:**
- Produces: Gradle project `:shared`; `app` has `implementation(project(":shared"))`; aliases `libs.plugins.kotlin.multiplatform`, `libs.kotlinx.datetime`, `libs.kotlinx.io.core`.

- [ ] **Step 1: Version catalog.** In `gradle/libs.versions.toml` add under `[versions]`:
```toml
kotlinxDatetime = "0.8.0"
kotlinxIo = "0.9.2"
```
under `[libraries]`:
```toml
kotlinx-datetime = { group = "org.jetbrains.kotlinx", name = "kotlinx-datetime", version.ref = "kotlinxDatetime" }
kotlinx-io-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-io-core", version.ref = "kotlinxIo" }
```
under `[plugins]`:
```toml
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

- [ ] **Step 2: Root build and settings.** `build.gradle.kts` plugins block gets `alias(libs.plugins.kotlin.multiplatform) apply false`. `settings.gradle.kts`: `include(":app", ":shared")` (replace the single include). `gradle.properties` append:
```properties
# iOS targets compile only on macOS (CI). On Windows Gradle skips them quietly.
kotlin.native.ignoreDisabledTargets=true
```

- [ ] **Step 3: `shared/build.gradle.kts`**
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}
```

- [ ] **Step 4: Smoke code and test.**
`SharedInfo.kt`:
```kotlin
package io.github.nikkittap.timelineexporter

/** Marks the shared module; lets the app and the iOS framework check they link it. */
object SharedInfo {
    const val MODULE = "shared"
}
```
`SharedInfoTest.kt`:
```kotlin
package io.github.nikkittap.timelineexporter

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInfoTest {
    @Test fun moduleName() = assertEquals("shared", SharedInfo.MODULE)
}
```

- [ ] **Step 5: App depends on shared.** In `app/build.gradle.kts` dependencies add `implementation(project(":shared"))`.

- [ ] **Step 6: Build.**
Run: `./gradlew :shared:jvmTest :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing unit tests pass. If `app` fails to resolve `:shared` (variant/attribute mismatch between the Android consumer and the `jvm` target), do not force attributes in `app`. Instead switch `shared` to also declare an Android library target with the AGP plugin `com.android.kotlin.multiplatform.library` (`kotlin { androidLibrary { namespace = "io.github.nikkittap.timelineexporter.shared"; compileSdk = 37; minSdk = 26 } }`), put the JVM-only code of Task 3 in a `jvmAndAndroidMain` intermediate source set, and record the change in the commit message.

- [ ] **Step 7: Commit** `Add an empty Kotlin Multiplatform module, shared` (body: why — the iOS port spec; targets; the ignoreDisabledTargets line; Checked: the build command above).

---

### Task 2: Golden export hashes and a parse benchmark

**Files:**
- Create: `app/src/test/java/io/github/nikkittap/timelineexporter/golden/GoldenExportTest.kt`
- Create: `app/src/test/resources/golden/export-hashes.txt` (generated)
- Create: `app/src/test/java/io/github/nikkittap/timelineexporter/golden/ParseBenchmarkTest.kt`

**Interfaces:**
- Consumes: today's API — `parseTimeline(InputStream)`, `AllExporters`, `ExportContent.Track/Trips/Places`, `detectTrips`, `exportTrips`, `placeEntries`, `ExportPlace`.
- Produces: `export-hashes.txt` lines `<input>|<format>|<content>|<sha256>`; Tasks 3–6 must keep it passing. (This test moves with the code in Task 3.)

- [ ] **Step 1: Write `GoldenExportTest`.** Inputs: the inline fixtures already used by `TimelineParserFormatsTest` (copy two or three of its JSON strings as named fixtures) plus developer-local files `F:/Timeline/Timeline.json`, `Timeline_1.json`, `Timeline_2.json`, `Timeline_merged.json`, `Timeline_merged_compact.json`, `Timeline_merged_nosignals.json`, `Timeline_merged_big.json` (each skipped if missing). For each input: parse; then for each exporter in `AllExporters` hash the output of:
  - `ExportContent.Track(points, colorByDay = false, fallbackZone = ZONE)`
  - `ExportContent.Track(points, colorByDay = true, fallbackZone = ZONE)`
  - `ExportContent.Trips(exportTrips(found.trips, points, ZONE))` when `detectTrips(parsed, ZONE)` is `TripDetection.Found`
  - `ExportContent.Places(...)` built from `parsed.placeEntries(fallbackZone = ZONE)`, mapping each `PlaceEntry` to `ExportPlace` exactly as `exportPlaces` in `ui/MainScreen.kt` does, with the label = what `PlaceLabels` falls back to when the user named nothing (read both before writing the mapping).
  - `ZONE = ZoneId.of("Europe/Berlin")`, document name `"golden"`.
  Hash: SHA-256 hex of the UTF-8 output. Record mode when `System.getProperty("golden.record") == "true"`: write the file (sorted lines). Otherwise compare line by line; inputs missing locally are skipped, not failed; a line in the file whose input is present but no longer produced fails.
  Pass the property in `app/build.gradle.kts`: `tasks.withType<Test>().configureEach { systemProperty("golden.record", System.getProperty("golden.record") ?: "false") }`.

- [ ] **Step 2: Record.** Run: `./gradlew :app:testDebugUnitTest --tests "*GoldenExportTest*" -Dgolden.record=true` then without the flag. Expected: file written; second run PASS.

- [ ] **Step 3: Benchmark.** `ParseBenchmarkTest` (skipped unless `-Dbench=true` and the big file exists): parse `Timeline_merged_big.json` 1 warm-up + 5 timed runs via `BufferedInputStream`, print median ms and `points.size`. Run it and paste the median into the commit message (the baseline for Task 5).

- [ ] **Step 4: Commit** `Pin the exports with golden hashes before the shared move`.

---

### Task 3: Move the core packages into `shared/jvmMain`

**Files:**
- Move (`git mv`): `app/src/main/java/io/github/nikkittap/timelineexporter/{parser,filter,export,trips}/` → `shared/src/jvmMain/kotlin/io/github/nikkittap/timelineexporter/{...}/`
- Move tests: `app/src/test/java/io/github/nikkittap/timelineexporter/{parser,filter,export,trips,golden}/` → `shared/src/jvmTest/kotlin/...`; `app/src/test/resources/golden/` → `shared/src/jvmTest/resources/golden/`
- Modify: `shared/build.gradle.kts`, `app/build.gradle.kts`, UI files that used `internal` core declarations.

**Interfaces:**
- Produces: the same public API as before, now from `:shared`. Declarations used from `app` that were `internal` become public (keep a one-line KDoc noting it is public for the app module).

- [ ] **Step 1:** `git mv` the four packages and their tests (keeps history). `ui/FeedbackBallotTest.kt`, `ui/MapImageLayoutTest.kt` and other `ui` tests stay in `app`.
- [ ] **Step 2:** `shared/build.gradle.kts` `jvmMain.dependencies { implementation(libs.jackson.core) }`; `jvmTest.dependencies { implementation(libs.kotlinx.serialization.json) }`; move the golden `systemProperty` block and the bench property to `shared` (`tasks.withType<Test>()`). Remove `jackson.core` from `app` dependencies.
- [ ] **Step 3:** Compile: `./gradlew :shared:compileKotlinJvm :app:compileDebugKotlin`. For every "cannot access internal" error in `app`, make that declaration public in `shared`. Tests in `shared` that read `src/main/res` (none expected in these packages) stay in `app`. Real-file tests keep their absolute `F:/Timeline` paths.
- [ ] **Step 4:** Run: `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Expected: all pass, `GoldenExportTest` passes with the moved hash file, test count equal to before (340 total across both modules — compare with the count from Task 2's run).
- [ ] **Step 5: Commit** `Move the parser, filter, export and trips into shared`.

---

### Task 4: `JsonReader`, a streaming JSON tokenizer in commonMain (TDD)

**Files:**
- Create: `shared/src/commonMain/kotlin/io/github/nikkittap/timelineexporter/json/JsonReader.kt`
- Create: `shared/src/commonTest/kotlin/io/github/nikkittap/timelineexporter/json/JsonReaderTest.kt`
- Create: `shared/src/jvmTest/kotlin/io/github/nikkittap/timelineexporter/json/JsonReaderVsJacksonTest.kt`

**Interfaces (exact; Task 5 codes against these):**
```kotlin
package io.github.nikkittap.timelineexporter.json

enum class JsonToken {
    START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY, FIELD_NAME,
    VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE, VALUE_NULL,
}

class JsonParseException(message: String, val byteOffset: Long) : Exception(message)

class JsonReader(source: kotlinx.io.Source) : AutoCloseable {
    companion object { fun of(text: String): JsonReader }
    /** Next token, or null at end of input. Throws JsonParseException on malformed input. */
    fun nextToken(): JsonToken?
    fun currentToken(): JsonToken?
    /** Field name when on FIELD_NAME, or the name of the field whose value is current; null at top level/in arrays. */
    fun currentName(): String?
    /** Text of VALUE_STRING, the literal of a number, "true"/"false"; null for structural tokens and VALUE_NULL. */
    val valueAsString: String?
    val longValue: Long      // VALUE_NUMBER_INT only, else throws IllegalStateException
    val doubleValue: Double  // VALUE_NUMBER_INT or VALUE_NUMBER_FLOAT
    /** On START_OBJECT/START_ARRAY: advance to the matching END token. Otherwise no-op. Must not build strings for skipped content. */
    fun skipChildren()
    /** Bytes consumed from the source so far (for progress). */
    val bytesRead: Long
    override fun close()
}
```
Semantics mirror Jackson's `JsonParser` as `TimelineParser` uses it (`currentName()` behaves like Jackson's: on a value token inside an object it returns that value's field name). Strings: full JSON escapes including `\uXXXX` and surrogate pairs; input is UTF-8. Numbers: integer literals that overflow Long are VALUE_NUMBER_FLOAT (as Jackson reports big ints as INT, `longOrNull` callers only need Long range; document the difference). Leading UTF-8 BOM skipped. Reads through a 64 KiB buffer; no per-token allocations except the returned strings.

- [ ] **Step 1: Failing tests** in `JsonReaderTest` (kotlin.test), one per behaviour:
```kotlin
private fun tokens(json: String): List<Pair<JsonToken, String?>> = JsonReader.of(json).use { r ->
    buildList { while (true) { val t = r.nextToken() ?: break; add(t to (if (t == JsonToken.FIELD_NAME) r.currentName() else r.valueAsString)) } }
}

@Test fun objectWithScalars() = assertEquals(
    listOf(JsonToken.START_OBJECT to null, JsonToken.FIELD_NAME to "a", JsonToken.VALUE_NUMBER_INT to "1",
        JsonToken.FIELD_NAME to "b", JsonToken.VALUE_STRING to "x", JsonToken.FIELD_NAME to "c", JsonToken.VALUE_NULL to null,
        JsonToken.FIELD_NAME to "d", JsonToken.VALUE_TRUE to "true", JsonToken.END_OBJECT to null),
    tokens("""{"a":1,"b":"x","c":null,"d":true}"""))

@Test fun escapesAndUnicode() = assertEquals(
    "q\"\\/\b\f\n\r\t é 😀 °",
    JsonReader.of("\"q\\\"\\\\\\/\\b\\f\\n\\r\\t \\u00e9 \\ud83d\\ude00 °\"").use { it.nextToken(); it.valueAsString })

@Test fun numbers() { JsonReader.of("[-0.5e3, 12345678901234, 1.0, -7]").use { r ->
    r.nextToken()
    r.nextToken(); assertEquals(JsonToken.VALUE_NUMBER_FLOAT, r.currentToken()); assertEquals(-500.0, r.doubleValue)
    r.nextToken(); assertEquals(JsonToken.VALUE_NUMBER_INT, r.currentToken()); assertEquals(12345678901234L, r.longValue)
    r.nextToken(); assertEquals(JsonToken.VALUE_NUMBER_FLOAT, r.currentToken())
    r.nextToken(); assertEquals(-7L, r.longValue)
} }

@Test fun currentNameOnValue() { JsonReader.of("""{"k":[1]}""").use { r ->
    r.nextToken(); r.nextToken(); r.nextToken()
    assertEquals(JsonToken.START_ARRAY, r.currentToken()); assertEquals("k", r.currentName())
} }

@Test fun skipChildren() { JsonReader.of("""{"a":{"b":[1,{"c":2}],"d":"}"},"e":3}""").use { r ->
    r.nextToken(); r.nextToken(); r.nextToken(); r.skipChildren()
    assertEquals(JsonToken.END_OBJECT, r.currentToken())
    assertEquals(JsonToken.FIELD_NAME, r.nextToken()); assertEquals("e", r.currentName())
} }

@Test fun bomAndWhitespace() = assertEquals(listOf(JsonToken.START_ARRAY to null, JsonToken.END_ARRAY to null), tokens("\uFEFF \n[ ]\t"))

@Test fun truncatedThrows() { assertFailsWith<JsonParseException> { tokens("""{"a":[1,2""") } }
@Test fun garbageThrows() { assertFailsWith<JsonParseException> { tokens("""{"a" 1}""") } }

@Test fun bytesReadCountsUtf8() { JsonReader.of("[\"é\"]").use { r -> while (r.nextToken() != null) {}; assertEquals(6L, r.bytesRead) } }
```
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest --tests "*JsonReaderTest*"` — FAIL (unresolved `JsonReader`).
- [ ] **Step 3: Implement `JsonReader`.** A hand-written state machine: a context stack (object/array + "expecting name/value/comma"), a byte buffer refilled from `source.readAtMostTo`, UTF-8 decoding only inside string tokens (fast path: copy ASCII bytes until `"` or `\\`), number scanning into a reusable `StringBuilder` then `toLong()`/`toDouble()`. `skipChildren` counts depth and skips string contents without decoding (honouring `\\` escapes). Errors carry `bytesRead`.
- [ ] **Step 4:** Run the tests — PASS.
- [ ] **Step 5: Differential test (jvmTest).** `JsonReaderVsJacksonTest`: for every inline fixture used by the parser tests and each developer-local `F:/Timeline/*.json` (skip if absent), walk both Jackson and `JsonReader` token by token and assert equal token type, `currentName()`, and `valueAsString` (for numbers compare `doubleValue`). Run — PASS.
- [ ] **Step 6: Commit** `Add JsonReader, a streaming JSON reader for shared code`.

---

### Task 5: `TimelineParser` on `JsonReader`

**Files:**
- Modify: `shared/src/jvmMain/kotlin/io/github/nikkittap/timelineexporter/parser/TimelineParser.kt`
- Modify: `app/src/main/java/io/github/nikkittap/timelineexporter/ui/TimelineViewModel.kt` (only if the `InputStream` entry point signature changes — it should not in this task)
- Modify: `shared/build.gradle.kts` (drop Jackson from `jvmMain`; keep it in `jvmTest` for the differential test)

**Interfaces:**
- Consumes: `JsonReader`, `JsonToken` (Task 4).
- Produces: `fun parseTimeline(source: kotlinx.io.Source, onProgress: (ParserStage) -> Unit = {}): ParsedTimeline` and `fun findKeyOffsets(source: kotlinx.io.Source): KeyOffsets` as the new primary entry points; the existing `parseTimeline(InputStream, …)`, `parseTimeline(String, …)` and `findKeyOffsets(InputStream)` stay as thin JVM wrappers (`input.asSource().buffered()`) so `app` is untouched.

- [ ] **Step 1:** Replace `import com.fasterxml.jackson.core.*` with `io.github.nikkittap.timelineexporter.json.*`; `JsonParser` → `JsonReader` in every helper signature; `jsonFactory.createParser(x)` → `JsonReader(source)` / `JsonReader.of(text)`. The token helpers (`valueAsStringOrNull`, `longOrNull`, `doubleOrNull`) keep their bodies. Keep the 64 KiB-chunk byte scan in `findKeyOffsets` but read via `Source.readAtMostTo(ByteArray)`.
- [ ] **Step 2:** Run `./gradlew :shared:jvmTest :app:testDebugUnitTest`. Expected: all parser tests and `GoldenExportTest` PASS. A failing golden line means a tokenizer difference: reproduce it in `JsonReaderVsJacksonTest` first, fix `JsonReader`, re-run.
- [ ] **Step 3: Benchmark** with `-Dbench=true`; the median must be ≤ 1.2× Task 2's. If not: profile (`JsonReader` string decoding and `skipChildren` are the hot paths), optimise, re-run. Put both numbers in the commit message.
- [ ] **Step 4:** Smoke on the emulator or phone: open `Timeline_merged_big.json` in a debug build, watch the progress bar move and the map draw (as the user would). Note the result in the commit.
- [ ] **Step 5: Commit** `Parse Timeline files with JsonReader instead of Jackson`.

---

### Task 6: Common time and formatting; move everything to `commonMain`

**Files:**
- Move: `shared/src/jvmMain/kotlin/...{parser,filter,export,trips}` → `shared/src/commonMain/kotlin/...`; the JVM `InputStream` wrappers from Task 5 go to `shared/src/jvmMain/kotlin/io/github/nikkittap/timelineexporter/parser/TimelineParserJvm.kt`.
- Move tests that use only kotlin.test-compatible APIs → `shared/src/commonTest`; tests needing `java.nio`, `java.io.File`, `DocumentBuilderFactory`, kotlinx.serialization, Jackson, `assumeTrue` or real files stay in `jvmTest`.
- Create: `shared/src/commonMain/kotlin/io/github/nikkittap/timelineexporter/export/NumberFormat.kt`
- Modify: UI files in `app` that touch these types (`DateRangeDialog`, `Format`, `MainScreen`, `MapImageExport`, `MapImageLayout`, `MapImageRenderer`, `RememberedSettings`, `TimelineMap`, `TimelineViewModel`, `TipPromptPolicy`, `TrackDays`, `TripsContent`).

**Interfaces (replacement table, applied mechanically across the moved code):**

| JVM | Common |
|---|---|
| `java.time.Instant` | `kotlin.time.Instant` |
| `Instant.ofEpochMilli(x)` / `ofEpochSecond(x)` / `Instant.EPOCH` | `Instant.fromEpochMilliseconds(x)` / `fromEpochSeconds(x)` / `Instant.fromEpochSeconds(0)` |
| `instant.epochSecond` / `.nano` / `.toEpochMilli()` | `.epochSeconds` / `.nanosecondsOfSecond` / `.toEpochMilliseconds()` |
| `Duration.between(a, b)` | `b - a` (`kotlin.time.Duration`) |
| `ZoneId` / `ZoneId.systemDefault()` | `kotlinx.datetime.TimeZone` / `TimeZone.currentSystemDefault()` |
| `zone.rules.getOffset(i).totalSeconds` | `zone.offsetAt(i).totalSeconds` |
| `ZoneOffset.ofTotalSeconds(s)` | `UtcOffset(seconds = s)` |
| `LocalDate`, `ofEpochDay`, `toEpochDay`, `LocalDate.MIN` | `kotlinx.datetime.LocalDate`, `LocalDate.fromEpochDays`, `toEpochDays`, `LocalDate.MIN` (kotlinx-datetime 0.8) |
| `instant.atZone(z).toLocalDate()` / `atOffset(o).toLocalDate()` | `instant.toLocalDateTime(z).date` / `instant.toLocalDateTime(FixedOffsetTimeZone(o)).date` |
| `OffsetDateTime.parse(t)` | `Instant.parse(t)` for the instant; offset via `DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(t).toUtcOffset()` |
| `DateTimeFormatter.ISO_INSTANT.format(i)` (fallback in `appendIsoInstant`) | `i.toString()` — verified equal by `IsoInstantTest` |
| `Math.floorDiv` / `floorMod` | `Long.floorDiv` / `Long.mod` |
| `String.format(Locale.ROOT, "%.Nf", x)` | `formatFixed(x, N)` (below) |
| `BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()` | `plainDecimal(v)` (below) |
| `java.io.InputStream` in common signatures | `kotlinx.io.Source` |

`NumberFormat.kt` (common, pure):
```kotlin
package io.github.nikkittap.timelineexporter.export

/** `String.format(Locale.ROOT, "%.<decimals>f", value)` — HALF_UP on the exact decimal value, '-' sign, no grouping. */
fun formatFixed(value: Double, decimals: Int): String

/** `BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()` — shortest repr digits, exponent expanded, trailing zeros dropped ("0" for zero). */
fun plainDecimal(value: Double): String
```
`formatFixed` must match Java exactly, including values like 0.125/2.5/1e-7 and negatives that round to zero ("-0.0" in Java for `%.1f` of -0.04 — keep Java's output). `plainDecimal` builds on `value.toString()`; on Kotlin/Native that string can differ from the JVM's for some values, so the common test pins expected strings, not the JVM call.

- [ ] **Step 1: Failing tests** `shared/src/commonTest/.../export/NumberFormatTest.kt` with pinned expectations (generate them by running `String.format(Locale.ROOT, ...)` / `BigDecimal` once in a scratch JVM test, then hard-code):
```kotlin
@Test fun fixed() {
    assertEquals("0.1", formatFixed(0.05, 1)); assertEquals("2.5", formatFixed(2.45, 1))
    assertEquals("13", formatFixed(12.5, 0)); assertEquals("-0.0", formatFixed(-0.04, 1))
    assertEquals("48.1234568", formatFixed(48.12345675, 7)); assertEquals("0.0000001", formatFixed(1e-7, 7))
}
@Test fun plain() {
    assertEquals("48.123", plainDecimal(48.123)); assertEquals("0.0000001", plainDecimal(1e-7))
    assertEquals("12300000", plainDecimal(1.23e7)); assertEquals("0", plainDecimal(0.0)); assertEquals("-13.5", plainDecimal(-13.5))
}
```
Before hard-coding, check each expected value against the JVM call in the scratch test; fix the expectation if the JVM says otherwise (the JVM is the reference). Plus a jvmTest `NumberFormatVsJvmTest` comparing both functions with `String.format`/`BigDecimal` over 100,000 random coordinates and distances.
- [ ] **Step 2:** Run — FAIL. **Step 3:** Implement both functions (decimal-string arithmetic on the shortest repr for `plainDecimal`; for `formatFixed`, Java rounds the exact binary value HALF_UP, so derive digits from the exact value: scale with `toBigDecimal`-free long arithmetic where `|value| * 10^decimals < 2^53`, else from `toString` digits). **Step 4:** Run — PASS.
- [ ] **Step 5:** Move sources to `commonMain` and apply the replacement table; replace `String.format` and `BigDecimal` call sites (`TripExport.describe`, `googleMapsUrl`, `Coordinate`). Model data classes now carry `kotlin.time.Instant` and `TimeZone`.
- [ ] **Step 6:** Update `app`: where UI code needs `java.time` (pickers, formatting with locale), convert at the boundary with `toJavaInstant()` / `toKotlinInstant()`, `toJavaZoneId()` / `toKotlinTimeZone()`, `toJavaLocalDate()` / `toKotlinLocalDate()`. No behaviour change.
- [ ] **Step 7:** Move tests per the Files rule; convert moved tests from JUnit (`org.junit.Test`, `org.junit.Assert.*`) to `kotlin.test` and from `java.time` to the common types.
- [ ] **Step 8:** Run `./gradlew :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Expected: all pass, golden unchanged, `grep -rE "^import java\.|jackson" shared/src/commonMain` prints nothing. Benchmark again; still ≤ 1.2×.
- [ ] **Step 9:** Smoke on device: open a file, change the date range and time of day, export each format once, open Trips and Places.
- [ ] **Step 10: Commit** `Move the shared core to commonMain` (list the replacements, the boundary conversions in app, test counts, benchmark).

---

### Task 7: CI, spec note, final check

**Files:**
- Create: `.github/workflows/shared.yml`
- Modify: `docs/superpowers/specs/2026-11-06-ios-port-design.md` (UI-helpers deferral), `README.md` (one paragraph on the module layout, if it has an architecture section)

- [ ] **Step 1: `shared.yml`**
```yaml
# Shared core checks. Free on a public repository; macOS only for the iOS compile and tests.
name: shared

on:
  push:
    branches: [master]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: shared-${{ github.ref }}
  cancel-in-progress: true

jobs:
  jvm:
    name: JVM tests + Android debug build
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug --stacktrace

  ios:
    name: iOS compile + simulator tests
    needs: jvm
    runs-on: macos-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :shared:iosSimulatorArm64Test :shared:linkReleaseFrameworkIosArm64 --stacktrace
```
Pin each action to the commit SHA of its current release tag (as NEARCOIL does: `uses: owner/action@<sha> # vX.Y.Z`); look the SHAs up with `git ls-remote https://github.com/<owner>/<action> refs/tags/<tag>`. `app` release signing reads `keystore.properties` only when present, so `assembleDebug` needs no secrets — verify `app/build.gradle.kts` does not fail without it.
- [ ] **Step 2:** Spec: in section 1 replace the sentence about moving `MapImageLayout`, `RouteMotion`, `TrackDays`, `WhatsNewNotes` with: "These UI helpers move when iOS needs them (stages 4–5): they use `R` and locale-aware date formatting, which must first be split from their pure geometry and grouping logic."
- [ ] **Step 3: Commit** `Run the shared core tests in CI, on the JVM and the iOS simulator` and push `master` (ask the user before the push).
- [ ] **Step 4:** Watch the first run of `shared.yml` to green. iOS-only failures (e.g. `plainDecimal` digits on Kotlin/Native, time zone database differences) are fixed in `commonMain` with a pinned test, then a new commit.
- [ ] **Step 5: Final review** (opus) of the whole stage against the spec's section 1 acceptance list.
