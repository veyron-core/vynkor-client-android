// Top-level build config. The app module owns the Android plugin; the Rust
// core is built by cargo-ndk through a Gradle task (see app/build.gradle.kts).

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
