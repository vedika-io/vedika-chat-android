// Example Android app for the Vedika chat SDK.
//
// Pulls in the library module from `:library` and ships a placeholder
// chat bundle in `src/main/assets/vedika-chat/`. A real host app copies
// the bundle provided by Vedika into its own assets/ tree.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.vedika.chat.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.vedika.chat.example"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation(project(":library"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
