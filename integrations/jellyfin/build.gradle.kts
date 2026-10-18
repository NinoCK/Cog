import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The user's own Jellyfin server: its movies, shows and music, played on their players and controlled by voice.
plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.bestassistant.integrations.jellyfin"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":integration-api"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    // The real org.json: Android's copy is a stub off-device.
    testImplementation(libs.org.json)
}
