import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "lol.alphaliu01.runningmusic.aubio"
    compileSdk = 37

    // Pinned rather than left to AGP, which would otherwise pick whatever NDK
    // it defaults to and quietly change how the shipped libraries are built.
    // r28 and newer default to 16 KB page alignment, which Android 15 requires.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            // The SDK's CMake, not the host's 4.2.3 on PATH. Pinning it keeps
            // this build and CI's build the same build, and CMake 4 is strict
            // about policies in a way 3.31 is not.
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    packaging {
        jniLibs {
            // Compressed libraries are loaded by copying them out of the APK,
            // which throws away the page alignment the NDK gave them.
            useLegacyPackaging = false
        }
    }
}

// Android 15 refuses to load a library whose segments are aligned to the old
// 4 KB page size. The NDK gets this right by default, but this is the project's
// first native code and a wrong answer surfaces as a crash on hardware nobody
// here owns, so it is checked rather than assumed.
val verifyNativeAlignment = tasks.register<Exec>("verifyNativeAlignment") {
    group = "verification"
    description = "Fails if any shipped native library is not 16 KB page aligned."

    dependsOn("assembleRelease")

    val script = rootProject.file("scripts/check-so-alignment.sh")
    val aar = layout.buildDirectory.file("outputs/aar/aubio-release.aar")

    inputs.file(script)
    inputs.file(aar)

    commandLine(script.absolutePath, aar.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(verifyNativeAlignment)
}

dependencies {
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
