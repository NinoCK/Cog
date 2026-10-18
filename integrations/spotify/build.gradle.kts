import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Spotify: the page of what was asked for, opened and started; saved playlists by name.
plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.bestassistant.integrations.spotify"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // Its SDK's keep rules travel with it.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":integration-api"))
    implementation(libs.androidx.core.ktx)
    // Spotify's App Remote SDK (github.com/spotify/android-sdk, v0.8.0): starts playback in the
    // Spotify app when the user has added their own Client ID. Gson is its JSON mapper.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)
    // Spotify's sign-in library (same release, v2.1.0): asks for the user's OK from PocketSearch's own screen,
    // inside the Spotify app; AndroidX Browser is its fallback when Spotify isn't installed. It needs
    // the app to say where sign-in returns to: see the manifest placeholders in the app's build.
    implementation(files("libs/spotify-auth-release-2.1.0.aar"))
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
    // The real org.json: Android's copy is a stub off-device.
    testImplementation(libs.org.json)
}
