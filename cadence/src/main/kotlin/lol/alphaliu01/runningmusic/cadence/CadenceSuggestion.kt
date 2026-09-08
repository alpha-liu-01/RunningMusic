package lol.alphaliu01.runningmusic.cadence

import kotlin.math.abs
import kotlin.math.floor

/** What one candidate cadence would give the runner. */
data class CadenceOption(
    val cadence: Double,
    val coverage: Coverage,
)

/**
 * How far the sweep looks either side of the requested cadence, in spm.
 *
 * Human cadence has a comfortable range of several spm, and nudging it slightly
 * upward is generally considered good running form, so a suggestion inside this
 * radius is a defensible nudge rather than a demand to run differently.
 */
const val SUGGESTION_RADIUS_SPM = 8.0

/**
 * How much more playing time a different cadence has to unlock before it is
 * worth mentioning.
 *
 * Set well above 1.0 deliberately. A suggestion that saves the runner two
 * minutes of music is noise, and a prompt to change cadence that fires every
 * time is one the runner learns to ignore.
 */
const val SUGGESTION_GAIN = 1.25

/**
 * The sweep of a library's coverage across nearby cadences.
 *
 * @property requested what the runner actually asked for.
 * @property best the strongest cadence found in the sweep, possibly [requested].
 * @property options every cadence tried, in ascending order, so a caller can
 * plot the sweep rather than only show its peak.
 */
data class CadenceSuggestion(
    val requested: CadenceOption,
    val best: CadenceOption,
    val options: List<CadenceOption>,
) {
    /** True when [best] is a different cadence and unlocks materially more music. */
    val worthMoving: Boolean
        get() = best.cadence != requested.cadence &&
            best.coverage.rawMs > requested.coverage.rawMs * SUGGESTION_GAIN
}

/**
 * Finds a cadence near [around] that [library] supports better than [around]
 * itself.
 *
 * Coverage is a lumpy function of cadence, not a smooth one, because real tempo
 * distributions are lumpy. The largest cluster in pop and dance music sits at
 * 120-130 bpm, and no power-of-two multiple of that lands in a running cadence:
 * a 170 spm runner is almost exactly `120 * sqrt(2)` away from it, the worst
 * case of the fold, so the biggest part of the library is unreachable. Moving
 * the target a handful of spm can bring the whole cluster back.
 *
 * Ranked by [Coverage.rawMs], the reachable music at its own tempo, rather than
 * by track count or by stretched time. Track count would let a cluster of very
 * short tracks outrank the material that actually fills a run, and stretched
 * time is worse still: it grows as tracks are slowed down, so ranking on it
 * recommends the slowest cadence in the sweep almost regardless of the library.
 *
 * Ties go to the cadence closest to [around], since the runner asked for that
 * one, and then to the faster of the two, since nudging a runner up is better
 * form than nudging them down.
 *
 * @throws IllegalArgumentException if the cadence, radius or step is unusable.
 */
fun <T> suggestCadence(
    library: List<Candidate<T>>,
    around: Double,
    radius: Double = SUGGESTION_RADIUS_SPM,
    step: Double = 1.0,
    band: ToleranceBand = ToleranceBand.DEFAULT,
): CadenceSuggestion {
    require(around > 0.0 && around.isFinite()) {
        "around must be positive and finite, was $around"
    }
    require(radius >= 0.0 && radius.isFinite()) {
        "radius must not be negative and must be finite, was $radius"
    }
    require(step > 0.0 && step.isFinite()) { "step must be positive and finite, was $step" }

    // Offsets are counted rather than accumulated, so the sweep stays symmetric
    // about the requested cadence and one of the options is exactly it.
    val steps = floor(radius / step).toInt()
    val options = (-steps..steps)
        .map { around + it * step }
        .filter { it > 0.0 }
        .map { CadenceOption(it, coverageAt(library, it, band)) }

    val requested = options.firstOrNull { it.cadence == around }
        ?: CadenceOption(around, coverageAt(library, around, band))

    val best = options.maxWithOrNull(
        compareBy<CadenceOption> { it.coverage.rawMs }
            .thenByDescending { abs(it.cadence - around) }
            .thenBy { it.cadence }
    ) ?: requested

    return CadenceSuggestion(requested = requested, best = best, options = options)
}
