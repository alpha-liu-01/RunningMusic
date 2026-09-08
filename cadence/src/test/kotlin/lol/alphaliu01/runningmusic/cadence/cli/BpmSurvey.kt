package lol.alphaliu01.runningmusic.cadence.cli

import lol.alphaliu01.runningmusic.cadence.Candidate
import lol.alphaliu01.runningmusic.cadence.MAX_RESIDUAL
import lol.alphaliu01.runningmusic.cadence.ToleranceBand
import lol.alphaliu01.runningmusic.cadence.coverageAt
import lol.alphaliu01.runningmusic.cadence.selectForRun
import java.io.File
import kotlin.system.exitProcess

/**
 * Measures what a real BPM library supports, before any UI exists to ask.
 *
 * Lives in the test source set so it never ships in the app.
 *
 * ```
 * ./gradlew :cadence:bpmSurvey --args="--csv library.csv --cadence 170 --run-length 45m"
 * ```
 */

private const val DEFAULT_DURATION_MS = 210_000L // 3.5 minutes, when the CSV omits it
private val SURVEY_CADENCES = 150..190 step 2
private val SURVEY_TOLERANCES = listOf(1.05, 1.10, 1.15, 1.20, MAX_RESIDUAL)

private class Options(
    val csv: File,
    val cadence: Double,
    val runLengthMs: Long,
)

fun main(args: Array<String>) {
    val options = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n")
        System.err.println(USAGE)
        exitProcess(1)
    }

    val library = try {
        readLibrary(options.csv)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }

    if (library.isEmpty()) {
        System.err.println("No usable rows in ${options.csv}.")
        exitProcess(1)
    }

    println("Library: ${library.size} tracks from ${options.csv}")
    println("Target:  ${fmt(options.cadence)} spm, ${formatMs(options.runLengthMs)} run")
    printCoverageGrid(library)
    printRunPlan(library, options)
    printBandComparison(library, options)
}

private val USAGE = """
    Usage: --csv <path> [--cadence <spm>] [--run-length <duration>]

      --csv         Required. One track per line, "bpm" or "bpm,durationMs".
                    Blank lines and lines starting with # are ignored, as is a
                    header row. Tracks with no duration are assumed to be
                    ${formatMs(DEFAULT_DURATION_MS)}.
      --cadence     Steps per minute. Default 170.
      --run-length  Accepts 45m, 90s, 1h, or a bare number read as minutes.
                    Default 45m.
""".trimIndent()

private fun parseArgs(args: Array<String>): Options {
    var csv: String? = null
    var cadence = 170.0
    var runLength = 45 * 60_000L

    var i = 0
    while (i < args.size) {
        val flag = args[i]
        val value = args.getOrNull(i + 1)
            ?: throw IllegalArgumentException("$flag needs a value.")
        when (flag) {
            "--csv" -> csv = value
            "--cadence" -> cadence = value.toDoubleOrNull()
                ?: throw IllegalArgumentException("--cadence must be a number, got '$value'.")
            "--run-length" -> runLength = parseDuration(value)
            else -> throw IllegalArgumentException("Unknown option '$flag'.")
        }
        i += 2
    }

    val path = csv ?: throw IllegalArgumentException("--csv is required.")
    val file = File(path)
    require(file.isFile) { "No such file: $path" }
    require(cadence > 0) { "--cadence must be positive." }

    return Options(file, cadence, runLength)
}

private fun parseDuration(raw: String): Long {
    val match = Regex("^(\\d+(?:\\.\\d+)?)(h|m|min|s|ms)?$").find(raw.trim().lowercase())
        ?: throw IllegalArgumentException("Cannot read '$raw' as a duration.")
    val amount = match.groupValues[1].toDouble()
    val millis = when (match.groupValues[2]) {
        "h" -> amount * 3_600_000
        "s" -> amount * 1_000
        "ms" -> amount
        else -> amount * 60_000 // m, min, or nothing at all
    }
    require(millis > 0) { "Duration must be positive." }
    return millis.toLong()
}

private fun readLibrary(csv: File): List<Candidate<String>> =
    csv.readLines().mapIndexedNotNull { index, raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapIndexedNotNull null

        val fields = line.split(',').map { it.trim() }
        val bpm = fields[0].toDoubleOrNull()
            // A header row, or junk. Skipping the first line silently is
            // reasonable; skipping the twentieth is worth mentioning.
            ?: run {
                if (index > 0) System.err.println("Skipping line ${index + 1}: '$raw'")
                return@mapIndexedNotNull null
            }
        val durationMs = fields.getOrNull(1)?.toLongOrNull() ?: DEFAULT_DURATION_MS

        Candidate("line ${index + 1}", bpm, durationMs)
    }

private fun printCoverageGrid(library: List<Candidate<String>>) {
    println()
    println("Share of the library reachable, by cadence and tolerance")
    println("(coverage is lumpy, not smooth: it depends on where half the cadence lands)")
    println()
    print("  spm ")
    SURVEY_TOLERANCES.forEach { print("  ±%-5s".format(percentOf(it))) }
    println()

    for (cadence in SURVEY_CADENCES) {
        print("  %3d ".format(cadence))
        for (r in SURVEY_TOLERANCES) {
            val coverage = coverageAt(library, cadence.toDouble(), ToleranceBand.symmetric(r))
            print("  %5.1f%%".format(coverage.fraction * 100))
        }
        println()
    }
}

private fun printRunPlan(library: List<Candidate<String>>, options: Options) {
    println()
    println("Your run at ${fmt(options.cadence)} spm")
    println()

    val queue = selectForRun(library, options.cadence, options.runLengthMs)
    val worst = queue.worstBand
    if (worst == null) {
        println("  Nothing in the library is reachable within the ±%s ceiling."
            .format(percentOf(ToleranceBand.DEFAULT.widest)))
    } else {
        println("  %d tracks, %s covered, worst stretch +%s / -%s".format(
            queue.tracks.size,
            formatMs(queue.filledMs),
            percentOf(worst.maxSpeedUp),
            percentOf(worst.maxSlowDown),
        ))
        if (queue.shortfallMs > 0) {
            println("  Short by ${formatMs(queue.shortfallMs)}. The library is too thin at this")
            println("  cadence to fill the run without going past the ceiling.")
        }
    }

    // The nudge the Overview argues for: a cadence a few spm away that the
    // library actually supports is a better answer than a wider tolerance.
    val nearby = (options.cadence.toInt() - 10..options.cadence.toInt() + 10)
        .map { it to coverageAt(library, it.toDouble(), ToleranceBand.DEFAULT) }
    val best = nearby.maxBy { it.second.accepted }
    val here = coverageAt(library, options.cadence, ToleranceBand.DEFAULT)

    if (best.second.accepted > here.accepted) {
        println()
        println("  Try %d spm instead: %d tracks reachable rather than %d, %s of music."
            .format(best.first, best.second.accepted, here.accepted, formatMs(best.second.stretchedMs)))
    }
}

private fun printBandComparison(library: List<Candidate<String>>, options: Options) {
    println()
    println("Symmetric versus asymmetric, at ${fmt(options.cadence)} spm")
    println("(speeding up adds energy that suits running, slowing down tends to drag,")
    println(" so the band may be better off lopsided. This is the measurement for it.)")
    println()

    val shapes = listOf(
        "symmetric ±12%" to ToleranceBand.symmetric(1.12),
        "symmetric ±8%" to ToleranceBand.symmetric(1.08),
        "+12% / -8%" to ToleranceBand.ASYMMETRIC_HYPOTHESIS,
    )

    println("  %-16s %8s %10s %14s".format("band", "tracks", "share", "playing time"))
    for ((label, band) in shapes) {
        val coverage = coverageAt(library, options.cadence, band)
        println("  %-16s %8d %9.1f%% %14s".format(
            label, coverage.accepted, coverage.fraction * 100, formatMs(coverage.stretchedMs),
        ))
    }

    println()
    println("  A run needs ${formatMs(options.runLengthMs)}; any band above that clears it can")
    println("  fill the queue, so prefer the one that sounds best on an actual run.")
}

private fun percentOf(ratio: Double) = "%.0f%%".format((ratio - 1.0) * 100)

private fun fmt(value: Double) =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

private fun formatMs(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h${"%02d".format(minutes)}m" else "${minutes}m"
}
