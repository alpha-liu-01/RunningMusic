plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    // Pinned here because AGP already puts the Kotlin plugin on the buildscript
    // classpath with no version, which makes :cadence's own request unresolvable.
    alias(libs.plugins.kotlinJvm) apply false
}
