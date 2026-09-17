import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Absent for anyone who clones this repo without the release key — including
// F-Droid's build server, which signs the APK with its own key regardless.
// Release builds are simply left unsigned rather than failing the build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasSigningConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasSigningConfig) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.dugcanlift.macrocalc"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dugcanlift.macrocalc"
        // 26 is required by androidx.health.connect:connect-client. API 24/25
        // share is negligible in 2026, so this isn't a meaningful trade-off.
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // A debug build installs as its own application id, so it sits alongside
        // a release install rather than replacing it — which matters here because
        // the release build is what people download from the site. It also means
        // the debug signing key can be named in the site's assetlinks.json against
        // `com.dugcanlift.macrocalc.debug` without ever being able to claim
        // dugcanlift.com links on behalf of the real app.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "LIFT (debug)")
        }
        release {
            resValue("string", "app_name", "LIFT")
            optimization {
                enable = false
            }
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Each build type sets its own app_name, so the launcher shows which is which.
        resValues = true
    }
}

// LocationPermissionsManifestTest reads the source manifest directly, so a
// manifest-only change has to make the unit tests out of date.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Declared explicitly rather than relying on it resolving transitively —
    // androidx.lifecycle.compose.LocalLifecycleOwner (used by
    // OutdoorRecordingScreen's permission-refresh lifecycle observer) comes
    // from this artifact, not lifecycle-runtime-ktx.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.health.connect.client)
    constraints {
        // Health Connect 1.1.0 brings Guava 31.1, which has two temp-directory
        // advisories (CVE-2020-8908, CVE-2023-2976) fixed in 32.0.0. LIFT never
        // calls Guava itself; this only raises the version Health Connect gets.
        implementation(libs.guava) {
            because("Guava before 32.0.0: CVE-2020-8908, CVE-2023-2976")
        }
    }
    implementation("com.github.dougray:dugcanlift-kit-android:1.4.0")
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}