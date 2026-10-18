import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The user's Tasker tasks, run by name.
plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.bestassistant.integrations.tasker"
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
}
