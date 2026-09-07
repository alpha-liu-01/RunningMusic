plugins {
    alias(libs.plugins.kotlinJvm)
}

// Deliberately a plain JVM module with no Android dependency. The cadence maths
// has to run in milliseconds under ./gradlew test, and keeping it out of :app
// means the compiler enforces that rather than review.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // So a test can reach a committed file, such as the corpus manifest, by the
    // same path a person would type.
    workingDir = rootDir
}

// Lives in the test source set so it never ships. Run with:
//   ./gradlew :cadence:bpmSurvey --args="--csv library.csv --cadence 170 --run-length 45m"
tasks.register<JavaExec>("bpmSurvey") {
    group = "verification"
    description = "Reports what cadences and tolerances a real BPM library supports."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "lol.alphaliu01.runningmusic.cadence.cli.BpmSurveyKt"
}

// Run with:
//   ./gradlew :cadence:recordingSummary --args="build/step-recordings --expected-steps 450"
tasks.register<JavaExec>("recordingSummary") {
    group = "verification"
    description = "Summarises step recordings pulled off a device."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "lol.alphaliu01.runningmusic.cadence.cli.RecordingSummaryKt"
    // Paths on the command line are the ones the user typed, so resolve them
    // against the repo root rather than against cadence/.
    workingDir = rootDir
}

// Needs bpm_probe built first, so scripts/bpm-eval.sh is the usual way in.
// Directly:
//   ./gradlew :cadence:bpmEval --args="--analyse-seconds 60"
tasks.register<JavaExec>("bpmEval") {
    group = "verification"
    description = "Measures BPM estimation accuracy and recommends a confidence threshold."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "lol.alphaliu01.runningmusic.cadence.cli.BpmEvalKt"
    workingDir = rootDir
}
