import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    // Shared.xcframework for the iOS app (ios/project.yml links
    // shared/build/XCFrameworks/release/Shared.xcframework). Static: linked into
    // the app binary, never embedded.
    val xcf = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "io.github.nikkittap.timelineexporter.shared")
            xcf.add(this)
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
            // Test-only: the reference tokenizer JsonReaderVsJacksonTest compares against.
            implementation(libs.jackson.core)
            // Test-only: an independent JSON parser to validate GeoJsonExporter output.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Pass the golden-hash and benchmark switches from -D on the Gradle command
// line through to the forked test JVM (Gradle does not forward them itself).
tasks.withType<Test>().configureEach {
    systemProperty("golden.record", System.getProperty("golden.record") ?: "false")
    systemProperty("bench", System.getProperty("bench") ?: "false")
}
