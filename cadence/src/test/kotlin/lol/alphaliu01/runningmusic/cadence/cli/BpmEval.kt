package lol.alphaliu01.runningmusic.cadence.cli

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import lol.alphaliu01.runningmusic.cadence.accuracy.Accuracy
import lol.alphaliu01.runningmusic.cadence.accuracy.Estimate
import lol.alphaliu01.runningmusic.cadence.accuracy.ProbeSettings
import lol.alphaliu01.runningmusic.cadence.accuracy.Resolved
import lol.alphaliu01.runningmusic.cadence.accuracy.SYNTHETIC_DURATION_SECONDS
import lol.alphaliu01.runningmusic.cadence.accuracy.SYNTHETIC_SEED
import lol.alphaliu01.runningmusic.cadence.accuracy.Scored
import lol.alphaliu01.runningmusic.cadence.accuracy.Sweep
import lol.alphaliu01.runningmusic.cadence.accuracy.accuracy
import lol.alphaliu01.runningmusic.cadence.accuracy.decode
import lol.alphaliu01.runningmusic.cadence.accuracy.forDisplay
import lol.alphaliu01.runningmusic.cadence.accuracy.middle
import lol.alphaliu01.runningmusic.cadence.accuracy.parseManifest
import lol.alphaliu01.runningmusic.cadence.accuracy.resolveAgainst
import lol.alphaliu01.runningmusic.cadence.accuracy.runProbe
import lol.alphaliu01.runningmusic.cadence.accuracy.score
import lol.alphaliu01.runningmusic.cadence.accuracy.sha256Of
import lol.alphaliu01.runningmusic.cadence.accuracy.sweepConfidence
import lol.alphaliu01.runningmusic.cadence.accuracy.syntheticCorpus

/**
 * Measures how well the shipped tempo estimator does, and what confidence
 * threshold is worth trusting it above.
 *
 * Not a test. Nothing here passes or fails; there is a number, and the job of
 * the harness is to make that number comparable between runs so that it getting
 * worse is visible. The estimate itself comes from `bpm_probe`, which links the
 * same C the phone runs.
 */
private const val EVAL_USAGE = """
usage: bpmEval [options]

  --corpus <dir>          Real audio, described by the manifest. Optional; the
                          harness runs on its synthetic corpus without it.
  --manifest <file>       Default docs/private/bpm-corpus.tsv
  --synthetic             Include the generated corpus. On by default.
  --no-synthetic          Leave it out, e.g. when measuring real audio alone.
  --cadence <spm>         Target cadence for the playback-speed metric. Default 170.
  --samplerate <hz>       Default 44100.
  --buffer <n>            Analysis window. Default 1024.
  --hop <n>               Default 512.
  --analyse-seconds <n>   Analyse n seconds from the middle of each track, as
                          the app intends to. Default: the whole track.
  --probe <file>          Default aubio/build/host/bpm_probe
  --out <dir>             Default aubio/build/bpm-eval
"""

fun main(args: Array<String>) {
    val options = runCatching { EvalOptions.parse(args) }.getOrElse {
        System.err.println(it.message)
        System.err.println(EVAL_USAGE)
        return
    } ?: return println(EVAL_USAGE.trim())

    val probe = ProbeSettings(
        executable = options.probe,
        sampleRate = options.sampleRate,
        bufferSize = options.bufferSize,
        hopSize = options.hopSize
    )

    if (!options.probe.canExecute()) {
        System.err.println(
            "No probe at ${options.probe}. Build it with scripts/build-host-aubio.sh, " +
                "or use scripts/bpm-eval.sh which does that first."
        )
        return
    }

    val notes = mutableListOf<String>()
    val scored = mutableListOf<Scored>()

    if (options.synthetic) scored += measureSynthetic(options, probe)
    scored += measureReal(options, probe, notes)

    if (scored.isEmpty()) {
        System.err.println("Nothing to measure. Point --corpus at some audio, or drop --no-synthetic.")

        // The reason is almost always in here -- every track was missing, or
        // every hash had changed -- and swallowing it would leave the run
        // looking like it simply found nothing to do.
        notes.forEach { System.err.println("  $it") }
        return
    }

    options.out.mkdirs()

    val results = File(options.out, "results.tsv")
    results.writeText(resultsTsv(scored))

    val summary = summary(options, probe, scored, notes)
    File(options.out, "summary.txt").writeText(summary)

    print(summary)
    println("wrote ${results.path} and ${File(options.out, "summary.txt").path}")
}

// ---------------------------------------------------------------- measurement

private fun measureSynthetic(options: EvalOptions, probe: ProbeSettings): List<Scored> {
    val corpus = syntheticCorpus(options.sampleRate)

    println("synthetic: ${corpus.size} tracks")

    return corpus.mapIndexed { index, track ->
        progress(index + 1, corpus.size, track.name)

        val samples = track.render().let {
            if (options.analyseSeconds == null) it else it.middle(options.analyseSeconds, options.sampleRate)
        }

        val estimate = runProbe(samples, probe)

        score(
            corpus = "synthetic",
            track = track.name,
            variant = track.variant.id,
            truth = track.truthBpm,
            estimate = estimate.bpm,
            confidence = estimate.confidence,
            beats = estimate.beats,
            targetCadence = options.cadence
        )
    }.also { if (INTERACTIVE) println() }
}

private fun measureReal(
    options: EvalOptions,
    probe: ProbeSettings,
    notes: MutableList<String>
): List<Scored> {
    val manifest = parseManifest(options.manifest.takeIf { it.isFile } ?: return emptyList())

    manifest.problems.forEach {
        notes += "${options.manifest.name} line ${it.lineNumber}: ${it.reason}"
    }

    if (manifest.entries.isEmpty()) return emptyList()

    val root = options.corpus ?: run {
        notes += "${manifest.entries.size} manifest entries were skipped: no --corpus directory given"
        return emptyList()
    }

    println("corpus: ${manifest.entries.size} tracks under $root")

    return manifest.entries.sortedBy { it.path }.mapIndexedNotNull { index, entry ->
        progress(index + 1, manifest.entries.size, entry.path)

        val file = when (val resolved = entry.resolveAgainst(root)) {
            is Resolved.Ready -> resolved.file

            is Resolved.Missing -> {
                notes += "missing, skipped: ${entry.path}"
                return@mapIndexedNotNull null
            }

            // Loudly, because quietly measuring different audio under the same
            // name is how two runs come to disagree for no discoverable reason.
            is Resolved.Changed -> {
                notes += "CHANGED SINCE THE MANIFEST WAS WRITTEN, skipped: ${entry.path} " +
                    "(expected ${entry.sha256.take(12)}, found ${resolved.actualSha256.take(12)})"
                return@mapIndexedNotNull null
            }
        }

        val estimate = runCatching { estimate(file, options, probe) }.getOrElse {
            notes += "could not measure ${entry.path}: ${it.message}"
            return@mapIndexedNotNull null
        }

        score(
            corpus = "corpus",
            track = entry.path,
            variant = entry.truthSource.name.lowercase(),
            truth = entry.truthBpm,
            estimate = estimate.bpm,
            confidence = estimate.confidence,
            beats = estimate.beats,
            targetCadence = options.cadence
        )
    }.also { if (INTERACTIVE) println() }
}

private fun estimate(file: File, options: EvalOptions, probe: ProbeSettings): Estimate {
    val decoded = decode(file, options.sampleRate)
    val samples =
        if (options.analyseSeconds == null) decoded
        else decoded.middle(options.analyseSeconds, options.sampleRate)

    return runProbe(samples, probe)
}

/**
 * A line that rewrites itself, and only when someone is watching.
 *
 * Under Gradle there is no terminal to interpret the carriage returns, and
 * eighty-five of them arrive as one unreadable line in the log.
 */
private val INTERACTIVE = System.console() != null

private fun progress(done: Int, total: Int, name: String) {
    if (!INTERACTIVE) return

    print("\r  %3d/%d  %-40.40s".format(Locale.ROOT, done, total, name))
    System.out.flush()
}

// -------------------------------------------------------------------- reports

private val RESULT_COLUMNS = listOf(
    "corpus", "track", "variant", "truth_bpm", "estimate_bpm", "confidence", "beats",
    "octave_distance", "strict_distance", "playback_speed_error",
    "octave_correct", "strict_correct", "played_correctly"
)

/**
 * One row per track, in a fixed order and with no timestamp anywhere, so two
 * runs of the same corpus diff cleanly and a difference means the estimator
 * changed.
 */
private fun resultsTsv(scored: List<Scored>): String = buildString {
    appendLine(RESULT_COLUMNS.joinToString("\t"))

    scored.sortedWith(compareBy({ it.corpus }, { it.variant }, { it.truth }, { it.track }))
        .forEach { row ->
            appendLine(
                listOf(
                    row.corpus,
                    row.track,
                    row.variant,
                    "%.2f".format(Locale.ROOT, row.truth),
                    "%.2f".format(Locale.ROOT, row.estimate),
                    "%.4f".format(Locale.ROOT, row.confidence),
                    row.beats.toString(),
                    distance(row.octaveDistance),
                    distance(row.strictDistance),
                    distance(row.playbackSpeedError),
                    row.octaveCorrect.toString(),
                    row.strictCorrect.toString(),
                    row.playedCorrectly.toString()
                ).joinToString("\t")
            )
        }
}

/** Unknowns carry a sentinel distance, which would print as an absurd number. */
private fun distance(value: Double) =
    if (value == Double.MAX_VALUE) "" else "%.4f".format(Locale.ROOT, value)

private fun summary(
    options: EvalOptions,
    probe: ProbeSettings,
    scored: List<Scored>,
    notes: List<String>
): String = buildString {
    appendLine("=".repeat(78))
    appendLine("BPM accuracy")
    appendLine("=".repeat(78))

    // Without this header a pair of reports is two numbers with no way to tell
    // why they differ.
    appendLine("  measured        ${Instant.now()}")
    appendLine("  commit          ${gitDescription()}")
    appendLine("  probe           ${options.probe}")
    appendLine("  analysis        ${probe.sampleRate} Hz, buffer ${probe.bufferSize}, hop ${probe.hopSize}")
    appendLine("  window          " + (options.analyseSeconds?.let { "$it s from the middle" } ?: "whole track"))
    appendLine("  cadence         ${"%.0f".format(Locale.ROOT, options.cadence)} spm, for the playback-speed metric")
    appendLine(
        "  synthetic       " + if (options.synthetic) {
            "seed 0x${SYNTHETIC_SEED.toString(16)}, ${SYNTHETIC_DURATION_SECONDS} s per track"
        } else {
            "not included"
        }
    )
    appendLine("  manifest        ${options.manifest} (sha256 ${manifestHash(options.manifest).take(12)})")
    appendLine("  corpus root     ${options.corpus ?: "none given"}")
    appendLine()

    appendLine("Accuracy, at the conventional 4% tolerance")
    appendLine("-".repeat(78))
    appendLine("  %-22s %6s %8s %8s %8s %8s".format(Locale.ROOT, "", "n", "octave", "strict", "played", "unknown"))
    row(this, "everything", scored.accuracy())

    scored.groupBy { it.corpus }.takeIf { it.size > 1 }?.forEach { (corpus, rows) ->
        row(this, "  $corpus", rows.accuracy())
    }

    appendLine()
    scored.groupBy { it.variant }.toSortedMap().forEach { (variant, rows) ->
        row(this, "  by $variant", rows.accuracy())
    }

    appendLine()
    appendLine("  octave  = right, allowing half and double, which play identically")
    appendLine("  strict  = the literal tempo, as the literature scores it")
    appendLine("  played  = folds to the right playback speed, which is what a runner hears")

    // The one cell worth calling out by name. A track can be octave-correct and
    // still play wrongly, and when it does the fault is usually not the
    // estimator's: the truth sits near a fold midpoint, where a percent of
    // tempo error moves the track to the next playback speed.
    val onTheBoundary = scored.filter { it.octaveCorrect && !it.playedCorrectly }
    if (onTheBoundary.isNotEmpty()) {
        appendLine()
        appendLine(
            "  ${onTheBoundary.size} track(s) were octave-correct but would play at the wrong speed:"
        )
        onTheBoundary.sortedBy { it.track }.forEach {
            appendLine(
                "    %-28s truth %6.1f  estimate %6.1f".format(
                    Locale.ROOT, it.track, it.truth, it.estimate
                )
            )
        }
        appendLine("  Check whether their tempo sits near a fold midpoint at this cadence")
        appendLine("  before reading them as estimation failures.")
    }

    appendLine()

    append(sweepReport(scored.sweepConfidence()))

    if (options.synthetic) {
        appendLine()
        appendLine("Caveat")
        appendLine("-".repeat(78))
        appendLine("  Synthetic material is easier than real music: the beats are exactly where")
        appendLine("  they claim to be, the tempo never drifts, and nothing competes with the")
        appendLine("  beat in the spectrum. Any figure above that includes it is optimistic.")
        appendLine("  The real number waits for a real corpus in docs/private/bpm-corpus.tsv.")
    }

    if (notes.isNotEmpty()) {
        appendLine()
        appendLine("Notes")
        appendLine("-".repeat(78))
        notes.forEach { appendLine("  $it") }
    }

    appendLine()
}

private fun row(out: StringBuilder, label: String, accuracy: Accuracy) {
    out.appendLine(
        "  %-22s %6d %7.1f%% %7.1f%% %7.1f%% %8d".format(
            Locale.ROOT,
            label,
            accuracy.total,
            accuracy.octaveAccuracy * 100,
            accuracy.strictAccuracy * 100,
            accuracy.playedAccuracy * 100,
            accuracy.unknown
        )
    )
}

private fun sweepReport(sweep: Sweep) = buildString {
    appendLine("Confidence threshold")
    appendLine("-".repeat(78))
    appendLine("  %10s %10s %10s %10s".format(Locale.ROOT, "threshold", "kept", "coverage", "accuracy"))

    sweep.forDisplay().forEach { point ->
        val mark = if (point == sweep.recommended) " <-- recommended" else ""

        appendLine(
            "  %10.4f %10d %9.1f%% %9.1f%%%s".format(
                Locale.ROOT,
                point.threshold,
                point.retained,
                point.coverage * 100,
                point.accuracy * 100,
                mark
            )
        )
    }

    appendLine()
    appendLine(
        "  mean confidence when right ${mean(sweep.meanConfidenceWhenRight)}, " +
            "when wrong ${mean(sweep.meanConfidenceWhenWrong)}"
    )
    appendLine()

    val recommended = sweep.recommended

    when {
        recommended == null -> {
            appendLine("  No threshold reaches ${percent(sweep.targetAccuracy)} accuracy.")
            appendLine("  That is a finding about aubio on this material, not a reason to pick one")
            appendLine("  anyway: confidence is not separating right answers from wrong ones here.")
        }

        sweep.thresholdBuysNothing -> {
            appendLine(
                "  Accepting every answer already reaches %.1f%%, so no threshold is needed to".format(
                    Locale.ROOT, recommended.accuracy * 100
                )
            )
            appendLine(
                "  hit ${percent(sweep.targetAccuracy)} here. Do not read that as confidence being trustworthy:"
            )
            appendLine("  it may only mean this material was too easy to produce the failures a")
            appendLine("  threshold exists to catch.")
        }

        else -> {
            appendLine(
                "  Record a tempo only when confidence >= %.4f.".format(Locale.ROOT, recommended.threshold)
            )
            appendLine(
                "  That keeps %.1f%% of tracks at %.1f%% accuracy.".format(
                    Locale.ROOT,
                    recommended.coverage * 100,
                    recommended.accuracy * 100
                )
            )
            appendLine("  Below it, store the tempo as unknown. Refusing to guess costs a track;")
            appendLine("  guessing wrongly plays one at a wildly wrong speed.")
        }
    }

    if (sweep.confidenceIsUninformative) {
        appendLine()
        appendLine("  Wrong answers here are on average MORE confident than right ones, so the")
        appendLine("  number carries no usable signal on this material. Anything plan 8 does with")
        appendLine("  a threshold needs confirming against a real corpus first.")
    }
}

private fun percent(value: Double) = "%.0f%%".format(Locale.ROOT, value * 100)

/** There is no mean of an empty group, and "NaN" in a report reads as a bug. */
private fun mean(value: Double) =
    if (value.isNaN()) "n/a" else "%.3f".format(Locale.ROOT, value)

private fun manifestHash(manifest: File) =
    if (manifest.isFile) sha256Of(manifest) else "absent"

/** Which commit produced this number, and whether the tree was clean when it did. */
private fun gitDescription(): String = runCatching {
    val commit = git("rev-parse", "--short", "HEAD")
    val dirty = git("status", "--porcelain").isNotEmpty()

    if (dirty) "$commit (with uncommitted changes)" else commit
}.getOrElse { "unknown" }

private fun git(vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

    check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "git failed" }

    return output
}

// -------------------------------------------------------------------- options

private data class EvalOptions(
    val corpus: File?,
    val manifest: File,
    val synthetic: Boolean,
    val cadence: Double,
    val sampleRate: Int,
    val bufferSize: Int,
    val hopSize: Int,
    val analyseSeconds: Int?,
    val probe: File,
    val out: File
) {
    companion object {
        /** Returns null when the caller asked for help. */
        fun parse(args: Array<String>): EvalOptions? {
            var corpus: File? = null
            var manifest = File("docs/private/bpm-corpus.tsv")
            var synthetic = true
            var cadence = 170.0
            var sampleRate = 44100
            var bufferSize = 1024
            var hopSize = 512
            var analyseSeconds: Int? = null
            var probe = File("aubio/build/host/bpm_probe")
            var out = File("aubio/build/bpm-eval")

            var i = 0
            while (i < args.size) {
                val name = args[i]

                fun value(): String {
                    require(++i < args.size) { "$name needs a value" }
                    return args[i]
                }

                fun positiveInt(): Int = value().toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("$name wants a positive whole number")

                when (name) {
                    "--help", "-h" -> return null
                    "--corpus" -> corpus = File(value())
                    "--manifest" -> manifest = File(value())
                    "--synthetic" -> synthetic = true
                    "--no-synthetic" -> synthetic = false
                    "--cadence" -> cadence = value().toDoubleOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--cadence wants a positive number")
                    "--samplerate" -> sampleRate = positiveInt()
                    "--buffer" -> bufferSize = positiveInt()
                    "--hop" -> hopSize = positiveInt()
                    "--analyse-seconds" -> analyseSeconds = positiveInt()
                    "--probe" -> probe = File(value())
                    "--out" -> out = File(value())
                    else -> throw IllegalArgumentException("Unknown argument: $name")
                }

                i++
            }

            require(hopSize <= bufferSize) { "--hop cannot be larger than --buffer" }

            return EvalOptions(
                corpus, manifest, synthetic, cadence,
                sampleRate, bufferSize, hopSize, analyseSeconds, probe, out
            )
        }
    }
}
