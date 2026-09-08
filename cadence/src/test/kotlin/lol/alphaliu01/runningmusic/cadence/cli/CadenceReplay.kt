package lol.alphaliu01.runningmusic.cadence.cli

import lol.alphaliu01.runningmusic.cadence.steps.CadenceLoop
import lol.alphaliu01.runningmusic.cadence.steps.LoopConfig
import lol.alphaliu01.runningmusic.cadence.steps.Motion
import lol.alphaliu01.runningmusic.cadence.steps.TrackingMode
import lol.alphaliu01.runningmusic.cadence.steps.parseStepRecording
import lol.alphaliu01.runningmusic.cadence.steps.replay
import java.io.File

private const val SECOND_NS = 1_000_000_000L

/**
 * Replays a recorded run through the control loop and prints what it would have
 * done.
 *
 * The point of building the loop as a pure function, made usable. A run
 * recorded on the phone comes back through the same [CadenceLoop.advance] the
 * phone calls, so "why did the music speed up there" is answerable at a desk
 * instead of by going out again with a notebook.
 *
 * Unit tests assert on the verdict; this shows the working.
 */
fun main(args: Array<String>) {
    val mode = when (val name = args.option("--mode") ?: "continuous") {
        "continuous" -> TrackingMode.CONTINUOUS
        "lock" -> TrackingMode.MEASURE_THEN_LOCK
        "manual" -> TrackingMode.MANUAL
        else -> {
            println("Unknown mode '$name'. Use continuous, lock or manual.")
            return
        }
    }

    val target = args.option("--target")?.toIntOrNull() ?: 170
    val tickNs = (args.option("--tick")?.toDoubleOrNull() ?: 5.0).toNanos()
    val verbose = args.contains("--verbose")

    val files = args.filterNot { it.startsWith("--") }
        .filterNot { it.toDoubleOrNull() != null }
        .flatMap { path ->
            val file = File(path)
            if (file.isDirectory) file.listFiles()?.sorted().orEmpty().toList() else listOf(file)
        }
        .filter { it.isFile && it.extension != "md" }

    if (files.isEmpty()) {
        println(
            "usage: cadenceReplay <file-or-dir>... " +
                "[--mode continuous|lock|manual] [--target 170] [--tick 5] [--verbose]"
        )
        return
    }

    files.forEach { file ->
        println("=".repeat(78))
        println("${file.name}   mode=${mode.name.lowercase()}  slider=$target spm")
        println("=".repeat(78))
        runCatching { parseStepRecording(file.readText()) }
            .onSuccess { recording ->
                if (recording.steps.isEmpty()) {
                    println("  no steps recorded")
                    return@onSuccess
                }
                replayOne(recording.steps, mode, target, tickNs, verbose)
            }
            .onFailure { println("  unreadable: ${it.message}") }
        println()
    }
}

private fun replayOne(
    samples: List<lol.alphaliu01.runningmusic.cadence.steps.StepSample>,
    mode: TrackingMode,
    target: Int,
    tickNs: Long,
    verbose: Boolean,
) {
    val start = samples.minOf { it.receivedElapsedRealtimeNs }

    println("     t  motion       measured   target  note")
    println("  " + "-".repeat(52))

    var previous: CadenceLoop? = null
    var lastPrintedNs = Long.MIN_VALUE

    val settled = CadenceLoop(target = target, mode = mode, config = LoopConfig())
        .replay(samples, tickNs = tickNs) { nowNs, loop ->
            val changed = previous == null ||
                loop.motion != previous!!.motion ||
                loop.target != previous!!.target ||
                loop.locked != previous!!.locked

            // Every change, plus a heartbeat, so a long steady stretch is visible
            // as a stretch rather than as silence.
            val heartbeat = nowNs - lastPrintedNs >= 30 * SECOND_NS
            if (verbose || changed || heartbeat) {
                val note = when {
                    previous == null -> "start"
                    loop.locked && !previous!!.locked -> "LOCKED at ${loop.target} spm"
                    loop.target != previous!!.target ->
                        "target ${previous!!.target} -> ${loop.target}"

                    loop.motion != previous!!.motion -> loop.motion.explain()
                    else -> ""
                }
                println(
                    "  %4ds  %-11s %8s   %6d  %s".format(
                        (nowNs - start) / SECOND_NS,
                        loop.motion.name.lowercase(),
                        loop.measuredSpm?.let { "%.1f".format(it) } ?: "-",
                        loop.target,
                        note,
                    )
                )
                lastPrintedNs = nowNs
            }
            previous = loop
        }

    println("  " + "-".repeat(52))
    val baseline = settled.baseline
    println(
        "  verdict      " + when {
            !settled.locked -> "never locked — no unbroken minute of running"
            mode == TrackingMode.MEASURE_THEN_LOCK -> "locked at $baseline spm and held"
            else -> "locked at $baseline spm, finished at ${settled.target} spm " +
                "(drift ${settled.target - (baseline ?: 0)})"
        }
    )
}

private fun Motion.explain() = when (this) {
    Motion.STARTING -> "waiting for the first step"
    Motion.RUNNING -> "running"
    Motion.WALKING -> "walking — target held"
    Motion.STOPPED -> "stopped — target held"
    Motion.SENSOR_LOST -> "sensor lost — target held"
}

private fun Array<String>.option(name: String): String? =
    indexOf(name).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

private fun Double.toNanos() = (this * SECOND_NS).toLong()
