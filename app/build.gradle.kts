import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Load release signing credentials from a gitignored file at the project
// root. If the file doesn't exist (fresh checkout, CI without secrets,
// contributor with no key), the properties will be null and release builds
// will fail with a clear "Keystore file ... not found" message. Debug builds
// are unaffected (AGP provides a default debug signing config).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "io.github.nikkittap.timelineexporter"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.nikkittap.timelineexporter"
        minSdk = 29
        targetSdk = 36

        // Bump versionCode by 1 for every AAB uploaded to Play Console
        // (including internal-testing uploads; Play won't accept a repeat).
        // Bump versionName per semver: patch for bugfixes, minor for
        // backwards-compatible features, major for breaking changes.
        //
        // History:
        //   versionCode=1, versionName="1.0.0" -> uploaded to Internal track
        //   versionCode=2, versionName="1.0.0" -> Closed (Alpha) track
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            keystoreProperties.getProperty("RELEASE_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = keystoreProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = keystoreProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = keystoreProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 strips unused code and obfuscates remaining classes/methods.
            // Reflection-using libraries (kotlinx.serialization, MapLibre)
            // are protected by rules in proguard-rules.pro.
            isMinifyEnabled = true
            // Strips unused resources (drawables, strings, layouts). Requires
            // isMinifyEnabled = true. Saves a few hundred KB typically.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.android.billing.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}