import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The user's own Home Assistant: its devices, switched, set and asked about by voice.
plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.bestassistant.integrations.homeassistant"
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
