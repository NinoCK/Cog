import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore/keystore.properties (not shared). Without it, release builds use the debug key.
val releaseKeys = rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.fastsearch"
    compileSdk = 37

    defaultConfig {
        // Where Spotify's sign-in returns to: SpotifyRemote.REDIRECT_URI, bestassistant://spotify-callback.
        manifestPlaceholders["redirectSchemeName"] = "bestassistant"
        manifestPlaceholders["redirectHostName"] = "spotify-callback"
        applicationId = "com.bestassistant.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            // The wake word's native speech library: phones, plus x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseKeys != null) {
            create("release") {
                storeFile = rootProject.file("keystore/" + releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The project's release key when present (shareable APKs, a stable fingerprint for
            // Spotify registrations); otherwise the debug key, so `assembleRelease` still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    // Spotify's App Remote SDK (github.com/spotify/android-sdk, v0.8.0): starts playback in the
    // Spotify app when the user has added their own Client ID. Gson is its JSON mapper.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)
    // Spotify's sign-in library (same release, v2.1.0): asks for the user's OK from our own screen,
    // inside the Spotify app; AndroidX Browser is its fallback when Spotify isn't installed.
    implementation(files("libs/spotify-auth-release-2.1.0.aar"))
    implementation(libs.androidx.browser)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
