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
