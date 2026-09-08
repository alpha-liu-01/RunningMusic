package lol.alphaliu01.runningmusic.cadence.cli

import lol.alphaliu01.runningmusic.cadence.steps.StepRecording
import lol.alphaliu01.runningmusic.cadence.steps.cadenceSpm
import lol.alphaliu01.runningmusic.cadence.steps.parseStepRecording
import java.io.File
import kotlin.math.roundToInt

/**
 * Reads recordings pulled off the phone and reports what they say about
 * delivery.
 *
 * This is the other half of the spike: the recorder answers "did we get the
 * events", and this answers "how, and did we miss any". It runs on the JVM
 * against the same parser the control loop will use, so a recording that
 * summarises here is by construction a usable fixture.
 */
fun main(args: Array<String>) {
    val expected = args.indexOf("--expected-steps")
        .takeIf { it >= 0 && it + 1 < args.size }
        ?.let { args[it + 1].toIntOrNull() }

    val paths = args.filterNot { it.startsWith("--") }
        .filterNot { it.toIntOrNull() != null }

    val files = paths.flatMap { path ->
        val file = File(path)
        if (file.isDirectory) file.listFiles()?.sorted().orEmpty().toList() else listOf(file)
    }.filter { it.isFile }

    if (files.isEmpty()) {
        println("usage: recordingSummary <file-or-dir>... [--expected-steps N]")
        return
    }

    files.forEach { file ->
        println("=".repeat(72))
        println(file.name)
        println("=".repeat(72))
        runCatching { parseStepRecording(file.readText()) }
            .onSuccess { it.report(expected) }
            .onFailure { println("  unreadable: ${it.message}") }
        println()
    }
}

private fun StepRecording.report(expectedSteps: Int?) {
    val h = header
    println("  device       ${h.device}  (Android ${h.androidRelease}, API ${h.sdkInt})")
    println("  sensor       ${h.sensorName.trim()} · ${h.sensorVendor} · wakeUp=${h.isWakeUp}")
    println("  config       fgs=${h.fgsType}  batchLatency=${h.batchLatencyUs / 1000}ms  fifo=${h.fifoMaxEvents}")

    if (steps.isEmpty()) {
        println("  steps        NONE RECORDED")
        if (accel.isNotEmpty()) println("  accel        ${accel.size} samples")
        return
    }

    val spanNs = steps.last().sensorTimestampNs - steps.first().sensorTimestampNs
    val spanMin = spanNs / 60_000_000_000.0
    println("  steps        ${steps.size} over ${"%.1f".format(spanMin)} min")
    if (spanMin > 0) println("  mean cadence ${"%.1f".format(steps.size / spanMin)} spm")

    expectedSteps?.let {
        val error = 100.0 * (steps.size - it) / it
        println("  vs counted   $it expected, ${"%+.1f".format(error)}%")
    }

    val lags = steps.map { it.deliveryLagNs / 1_000_000.0 }.sorted()
    println(
        "  lag ms       median ${"%.0f".format(lags.percentile(50))}" +
            "  p95 ${"%.0f".format(lags.percentile(95))}" +
            "  max ${"%.0f".format(lags.last())}"
    )

    // The headline number. Delivery lag alone cannot tell a CPU that never slept
    // from a wakeup sensor rousing one that did; the two clocks can.
    val suspended = suspendedNs()
    val span = observedSpanNs()
    when {
        suspended == null ->
            println("  suspend      not measurable (version 1 recording, no uptime clock)")

        span <= 0 -> println("  suspend      too few steps to measure")

        else -> {
            val percent = 100.0 * suspended / span
            val verdict = when {
                percent >= 50 -> "CPU was mostly asleep — this is the real test passing"
                percent >= 5 -> "CPU slept intermittently"
                else -> "CPU stayed awake — something held it up, run does not count"
            }
            println(
                "  suspend      ${"%.1f".format(suspended / 1e9)}s of ${"%.1f".format(span / 1e9)}s" +
                    " (${"%.0f".format(percent)}%) — $verdict"
            )
        }
    }

    // A gap far wider than the prevailing stride is either a genuine loss or a
    // pause. Only the person who did the walking can tell which, so report it
    // rather than judging it.
    val intervals = stepIntervalsNs()
    val medianInterval = intervals.map { it.toDouble() }.sorted().percentile(50)
    val gaps = intervals.filter { it > medianInterval * 4 }
    println("  stride       median ${"%.0f".format(medianInterval / 1e6)}ms (${"%.0f".format(cadenceSpm(medianInterval.roundToInt().toLong()))} spm)")
    if (gaps.isEmpty()) {
        println("  gaps         none over 4x median stride")
    } else {
        println("  gaps         ${gaps.size}: " + gaps.joinToString { "${"%.1f".format(it / 1e9)}s" })
    }

    reportCounter()

    // What the detector claimed each event was worth. The platform defines this
    // as always 1.0, so anything else is a vendor counting steps in a field
    // nobody reads, and would explain a detector firing slower than the legs.
    val reported = steps.mapNotNull { it.reportedSteps }.distinct().sorted()
    if (reported.isNotEmpty() && reported != listOf(1.0f)) {
        println("  values[0]    ${reported.joinToString()} — detector is not reporting one step per event")
    }

    if (accel.isNotEmpty()) println("  accel        ${accel.size} samples")
}

/**
 * The counter's verdict beside the detector's, because where they disagree the
 * counter is right.
 */
private fun StepRecording.reportCounter() {
    if (counter.size < 2) {
        println("  counter      ${if (counter.isEmpty()) "not recorded" else "one reading only"}")
        return
    }

    val counted = (counter.last().steps - counter.first().steps).roundToInt()
    val spanNs = counter.last().sensorTimestampNs - counter.first().sensorTimestampNs
    val spanMin = spanNs / 60_000_000_000.0
    if (spanMin <= 0) {
        println("  counter      ${counter.size} readings, no time between them")
        return
    }

    val cadence = counted / spanMin
    println(
        "  counter      $counted steps over ${"%.1f".format(spanMin)} min" +
            " = ${"%.1f".format(cadence)} spm, from ${counter.size} readings"
    )

    val detectorCadence = steps.size / spanMin
    val disagreement = 100.0 * (steps.size - counted) / counted
    println(
        "  detector     ${"%.1f".format(detectorCadence)} spm over the same span," +
            " ${"%+.0f".format(disagreement)}% of the counted steps"
    )
}

private fun List<Double>.percentile(p: Int): Double {
    if (isEmpty()) return 0.0
    val index = ((p / 100.0) * (size - 1)).roundToInt().coerceIn(0, size - 1)
    return this[index]
}
