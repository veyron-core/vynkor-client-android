import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vynkor.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vynkor.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // pull the Rust cdylib into the APK's jniLibs
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // generated Kotlin bindings + jniLibs land here
            java.srcDir("$projectDir/build/generated/uniffi/kotlin")
            jniLibs.srcDir("$projectDir/build/rustLibs")
        }
    }
}

dependencies {
    // UniFFI-generated Kotlin bindings use JNA to reach the Rust cdylib.
    // Use the AAR, not the JAR: the JAR's native libjnidispatch.so never
    // lands in the APK (libjnidispatch is packaged per-ABI in the AAR).
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Build the Rust core with cargo-ndk and copy the .so files next to the
// generated bindings. Runs on every :app:mergeDebugNativeLibs.
tasks.register<Exec>("cargoNdkBuild") {
    workingDir = File(rootProject.projectDir, "rust")
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/.android-sdk")
    val outDir = File(projectDir, "build/rustLibs")
    doFirst {
        outDir.mkdirs()
    }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", outDir.absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") {
    dependsOn("cargoNdkBuild")
}

// Generate the Kotlin bindings from the compiled cdylib. Runs from the rust/
// crate dir so uniffi finds Cargo.toml + uniffi.toml.
tasks.register<Exec>("uniffiBindgen") {
    val soDir = File(projectDir, "build/rustLibs/arm64-v8a")
    val outDir = File(projectDir, "build/generated/uniffi/kotlin")
    workingDir = File(rootProject.projectDir, "rust")
    dependsOn("cargoNdkBuild")
    doFirst {
        outDir.mkdirs()
    }
    commandLine(
        "uniffi-bindgen", "generate",
        "--library", File(soDir, "libvynkor_agent_core.so").absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )
}

tasks.named("preBuild") {
    dependsOn("uniffiBindgen")
}
