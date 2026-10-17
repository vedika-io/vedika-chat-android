// Library module for the Vedika chat Android SDK.
//
// Pulls in `androidx.webkit` for `WebViewCompat.addWebMessageListener`
// and `WebViewAssetLoader`. The chat bundle lives in `src/main/assets`
// so the loader can serve it at `https://appassets.androidplatform.net`.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization")
    id("maven-publish")
}

group = "io.vedika"
version = "0.1.0"

android {
    namespace = "io.vedika.chat"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Publish a single `release` AAR with a sources jar attached.
    // Kotlin docs are skipped (no Java/Kotlin doc generation in the
    // pipeline yet); the AAR is the consumable artefact.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // `runBlocking` is used inside the WebView message handler.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.mockito:mockito-core:5.13.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

// AGP registers the publishable components late in evaluation, so wire
// the publication in `afterEvaluate`. This gives `publishToMavenLocal`
// a `release` component to attach the AAR + sources jar to.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "io.vedika"
                artifactId = "vedika-chat-android"
                version = "0.1.0"

                from(components["release"])

                pom {
                    name.set("Vedika Chat Android")
                    description.set(
                        "Host library for the Vedika chat bundle on Android. " +
                            "Wraps a WebView with a token bridge over " +
                            "WebViewCompat.addWebMessageListener."
                    )
                    url.set("https://github.com/vedika-io/vedika-chat-android")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("vedika-io")
                            name.set("Vedika")
                            // Maven Central's POM validation requires a developer
                            // email; this is the team's public package-maintainer
                            // alias, not an individual's address.
                            email.set("dev@vedika.io") // gitleaks:allow
                        }
                    }
                    scm {
                        url.set("https://github.com/vedika-io/vedika-chat-android")
                        connection.set("scm:git:git://github.com/vedika-io/vedika-chat-android.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/vedika-io/vedika-chat-android.git"
                        )
                    }
                }
            }
        }

        repositories {
            mavenLocal()
        }
    }
}
