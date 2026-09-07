import com.android.build.api.variant.VariantOutputConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
}

androidComponents {
    val releaseVariant = selector().withBuildType("release")

    onVariants(releaseVariant) { variant ->
        val mainOutput = variant.outputs.single { it.outputType == VariantOutputConfiguration.OutputType.SINGLE }

        @Suppress("UnstableApiUsage")
        mainOutput.outputFileName = "RunningMusic_${mainOutput.versionName.get()}.apk"
    }
}

// On CI, release_stable.yml decodes secrets.SIGNING_KEY into this path before
// building. Locally it has to be put there by hand; see docs/private/RELEASE.md.
val releaseKeystore = file("release_key.jks")
val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
val releaseKeystoreReady = releaseKeystore.exists() && releaseStorePassword.isPresent
val allowUnsignedRelease = providers
    .gradleProperty("runningmusic.allowUnsignedRelease")
    .map(String::toBoolean)
    .getOrElse(false)

android {
    namespace = "com.sosauce.chocola"
    compileSdk = 37

    defaultConfig {
        applicationId = "lol.alphaliu01.runningmusic"
        minSdk = 28
        targetSdk = 37
        versionCode = 50009
        versionName = "4.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += arrayOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Only populated when the keystore is actually usable. A half-filled
        // config makes AGP fail with "Keystore file not set for signing config
        // release", which says nothing about what to do next; checkReleaseSigning
        // below explains it instead.
        create("release") {
            if (releaseKeystoreReady) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.orNull
                keyPassword = releaseKeyPassword.orNull
            }
        }

        // A single dev keystore shared between this machine and CI, so debug
        // builds from either source install over each other instead of failing
        // with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Falls back to the per-machine
        // ~/.android/debug.keystore when it isn't configured.
        getByName("debug") {
            val devKeystore = file(
                providers.gradleProperty("runningmusic.devKeystore")
                    .orElse(
                        providers.systemProperty("user.home")
                            .map { "$it/.android/runningmusic-dev.jks" }
                    )
                    .get()
            )
            val devStorePassword = providers.gradleProperty("runningmusic.devKeystorePassword").orNull

            if (devKeystore.exists() && devStorePassword != null) {
                storeFile = devKeystore
                storePassword = devStorePassword
                keyAlias = providers.gradleProperty("runningmusic.devKeyAlias")
                    .getOrElse("runningmusic-dev")
                keyPassword = providers.gradleProperty("runningmusic.devKeyPassword")
                    .getOrElse(devStorePassword)
            } else {
                println("No dev keystore configured, debug APK will use the default debug key")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystoreReady) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "debug"
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
        buildFeatures {
            compose = true
            // Debug-only code lives in src/main but is gated on BuildConfig.DEBUG,
            // so the constant has to be generated.
            buildConfig = true
        }
        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
        dependenciesInfo {
            includeInApk = false
            includeInBundle = false
        }
    }

    // JUnit 5 for the host-side tests, matching :cadence. Instrumented tests
    // stay on JUnit 4, which is what MigrationTestHelper's @Rule needs.
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    // MigrationTestHelper reads the exported schemas off the device, so they
    // have to be packaged into the test APK as assets.
    sourceSets {
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }
}

// Room exports a JSON schema per database version here. MigrationTestHelper
// needs the old version's schema to build a database to migrate from, so these
// files are committed rather than generated on demand.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    description = "Fails a release build that has no usable signing configuration."

    val keystorePath = releaseKeystore.absolutePath
    val ready = releaseKeystoreReady
    val allowUnsigned = allowUnsignedRelease

    doLast {
        if (ready || allowUnsigned) return@doLast

        throw GradleException(
            """
            No release signing configuration, so the APK would be unsigned.

            assembleRelease expects a keystore at
              $keystorePath
            and these environment variables:
              SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD

            On CI, .github/workflows/release_stable.yml decodes secrets.SIGNING_KEY
            into that path and supplies the three variables from the other secrets.
            To set this up locally, see docs/private/RELEASE.md.

            To build an unsigned release APK anyway, for R8 or size checks:
              ./gradlew assembleRelease -Prunningmusic.allowUnsignedRelease=true
            """.trimIndent()
        )
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseSigning)
}

dependencies {
    implementation(project(":cadence"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.compose.animation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.material.kolor)
    implementation(libs.koin.androidx.startup)
    implementation(libs.taglib)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.emoji2.emojipicker)
    implementation(libs.kmpalette.core)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.reorderable)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.compose.animation.graphics.android)
    implementation(libs.lyrics.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.colorpicker.compose)
    implementation(libs.sweetselect.compose)
    implementation(libs.squircle.shape)
    implementation(libs.cloudy)
    implementation(libs.nekobites)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // MigrationTestHelper is a JUnit 4 @Rule, so instrumented tests stay on
    // JUnit 4 while the host tests above use JUnit 5.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

