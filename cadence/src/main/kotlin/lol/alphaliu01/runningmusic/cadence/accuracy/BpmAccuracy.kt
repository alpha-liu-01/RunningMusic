package lol.alphaliu01.runningmusic.cadence.accuracy

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.round
import lol.alphaliu01.runningmusic.cadence.fold

/**
 * The tolerance the tempo-estimation literature scores against: an estimate
 * counts as correct if it is within 4% of the truth.
 *
 * Kept because it makes our figures comparable with published ones, not because
 * the app needs anything like that precision.
 */
const val ACCURACY_TOLERANCE = 0.04

/** 4% expressed in octaves, which is the space the comparisons happen in. */
val ACCURACY_TOLERANCE_OCTAVES = log2(1.0 + ACCURACY_TOLERANCE)

/**
 * How far an estimate is from the nearest octave of the truth, in octaves, so
 * always in `[0, 0.5]`.
 *
 * This is the metric that matters, and it is far more forgiving than the strict
 * one. Reporting 85 for a 170 bpm track is the classic tempo-detector failure,
 * tracked separately in the literature as Accuracy2, but here it is not a
 * failure at all: playback speed is chosen by folding tempo by powers of two,
 * so 85 and 170 lead to exactly the same music.
 *
 * Returns [Double.MAX_VALUE] for an estimate of zero, which is how the
 * estimator reports "unknown" and must never be scored as a near miss.
 */
fun octaveDistance(estimate: Double, truth: Double): Double {
    require(truth > 0.0 && truth.isFinite()) { "truth must be positive and finite, was $truth" }

    if (estimate <= 0.0 || !estimate.isFinite()) return Double.MAX_VALUE

    val ratio = log2(estimate / truth)

    return abs(ratio - round(ratio))
}

/** Distance ignoring octaves entirely, for the strict figure. */
fun strictDistance(estimate: Double, truth: Double): Double {
    require(truth > 0.0 && truth.isFinite()) { "truth must be positive and finite, was $truth" }

    if (estimate <= 0.0 || !estimate.isFinite()) return Double.MAX_VALUE

    return abs(log2(estimate / truth))
}

/**
 * How differently the track would actually be played, in octaves of speed.
 *
 * The harshest of the three metrics, and the only one that answers the question
 * the app cares about. It runs the shipped [fold], so it inherits the clamp to
 * reachable steps-per-beat exponents: an estimate at four times the truth is
 * octave-correct by the metric above, but folds to a *different* playback speed
 * because four steps per beat is out of reach. The runner hears that; the
 * octave metric does not see it.
 *
 * Returns [Double.MAX_VALUE] when there is no estimate to compare.
 */
fun playbackSpeedError(estimate: Double, truth: Double, targetCadence: Double): Double {
    require(truth > 0.0 && truth.isFinite()) { "truth must be positive and finite, was $truth" }

    if (estimate <= 0.0 || !estimate.isFinite()) return Double.MAX_VALUE

    val estimated = fold(estimate, targetCadence)
    val actual = fold(truth, targetCadence)

    return abs(log2(estimated.speed / actual.speed))
}

/** One track's estimate, scored every way. */
data class Scored(
    val corpus: String,
    val track: String,
    val variant: String,
    val truth: Double,
    val estimate: Double,
    val confidence: Double,
    val beats: Int,
    val octaveDistance: Double,
    val strictDistance: Double,
    val playbackSpeedError: Double
) {
    /** Whether the estimate leads to the right music, allowing octave errors. */
    val octaveCorrect: Boolean get() = octaveDistance <= ACCURACY_TOLERANCE_OCTAVES

    /** Whether the estimate is the literal tempo, as the literature scores it. */
    val strictCorrect: Boolean get() = strictDistance <= ACCURACY_TOLERANCE_OCTAVES

    /** Whether the track would actually be played at the right speed. */
    val playedCorrectly: Boolean get() = playbackSpeedError <= ACCURACY_TOLERANCE_OCTAVES

    /** The estimator declined to answer, which is not the same as being wrong. */
    val unknown: Boolean get() = estimate <= 0.0
}

fun score(
    corpus: String,
    track: String,
    variant: String,
    truth: Double,
    estimate: Double,
    confidence: Double,
    beats: Int,
    targetCadence: Double
) = Scored(
    corpus = corpus,
    track = track,
    variant = variant,
    truth = truth,
    estimate = estimate,
    confidence = confidence,
    beats = beats,
    octaveDistance = octaveDistance(estimate, truth),
    strictDistance = strictDistance(estimate, truth),
    playbackSpeedError = playbackSpeedError(estimate, truth, targetCadence)
)

/** Headline figures over a set of scored tracks. */
data class Accuracy(
    val total: Int,
    val unknown: Int,
    val octaveCorrect: Int,
    val strictCorrect: Int,
    val playedCorrectly: Int,
    val medianOctaveDistance: Double
) {
    val octaveAccuracy: Double get() = ratio(octaveCorrect, total)
    val strictAccuracy: Double get() = ratio(strictCorrect, total)
    val playedAccuracy: Double get() = ratio(playedCorrectly, total)

    private fun ratio(part: Int, whole: Int) = if (whole == 0) 0.0 else part.toDouble() / whole
}

fun Collection<Scored>.accuracy(): Accuracy {
    // Unknowns are counted in the total on purpose. A detector that answers
    // rarely but perfectly is not accurate, it is quiet, and the confidence
    // sweep is where that trade gets made explicitly.
    val distances = filterNot { it.unknown }.map { it.octaveDistance }.sorted()

    return Accuracy(
        total = size,
        unknown = count { it.unknown },
        octaveCorrect = count { it.octaveCorrect },
        strictCorrect = count { it.strictCorrect },
        playedCorrectly = count { it.playedCorrectly },
        medianOctaveDistance = distances.median()
    )
}

internal fun List<Double>.median(): Double = when {
    isEmpty() -> Double.NaN
    size % 2 == 1 -> this[size / 2]
    else -> (this[size / 2 - 1] + this[size / 2]) / 2.0
}
