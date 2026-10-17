// Top-level build script for the Vedika chat Android SDK.
//
// Versions live here; module-specific config lives in `:library` and
// `:example`. AGP 8.5+ requires JDK 17, which matches the toolchain.

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    kotlin("plugin.serialization") version "2.0.20" apply false
}
