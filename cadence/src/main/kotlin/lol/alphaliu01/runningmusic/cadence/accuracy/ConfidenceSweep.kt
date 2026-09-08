package lol.alphaliu01.runningmusic.cadence.accuracy

/**
 * The accuracy target a threshold has to reach before it is worth recommending.
 *
 * High, deliberately. Refusing to answer costs a track; answering wrongly plays
 * a track at a wildly wrong speed, which is the one failure the whole app is
 * built to avoid.
 */
const val TARGET_ACCURACY = 0.95

/** What accepting only tracks at or above [threshold] would get you. */
data class SweepPoint(
    val threshold: Double,
    /** Tracks the app would accept a tempo for. */
    val retained: Int,
    val total: Int,
    /** Of those, how many are octave-correct. */
    val correct: Int
) {
    /** Share of the library that keeps a usable tempo. */
    val coverage: Double get() = if (total == 0) 0.0 else retained.toDouble() / total

    /** Accuracy among the tracks that survive the threshold. */
    val accuracy: Double get() = if (retained == 0) 0.0 else correct.toDouble() / retained
}

data class Sweep(
    val points: List<SweepPoint>,
    val recommended: SweepPoint?,
    val targetAccuracy: Double,
    /** Mean confidence of the octave-correct answers. */
    val meanConfidenceWhenRight: Double,
    /** Mean confidence of the wrong ones. Should be lower, and is not always. */
    val meanConfidenceWhenWrong: Double
) {
    /**
     * True when no threshold reaches the target, which is a finding rather than
     * a reason to pick one anyway: it means confidence does not separate right
     * answers from wrong ones on this material.
     */
    val noUsableThreshold: Boolean get() = recommended == null

    /**
     * True when accepting everything already meets the target, so a threshold
     * buys nothing.
     *
     * Reported rather than dressed up as a recommendation of zero. It means one
     * of two things, and the report should not pretend to know which: either
     * the estimator is genuinely reliable on this material, or the material is
     * too easy to have produced the failures a threshold would catch.
     */
    val thresholdBuysNothing: Boolean get() = recommended != null && recommended.threshold <= 0.0

    /**
     * True when wrong answers are, on average, at least as confident as right
     * ones, so the number carries no usable signal whichever way it is cut.
     */
    val confidenceIsUninformative: Boolean
        get() = meanConfidenceWhenWrong.isFinite() &&
            meanConfidenceWhenRight.isFinite() &&
            meanConfidenceWhenWrong >= meanConfidenceWhenRight
}

/**
 * Reports what every candidate confidence threshold would buy.
 *
 * The candidates are the observed confidences themselves rather than an
 * arbitrary grid, since those are the only values where the outcome can change.
 * Zero is included so the "accept everything" baseline is always in the table.
 *
 * Tracks the estimator declined to answer for are excluded entirely: they have
 * no tempo to threshold, and counting them would make every threshold look
 * equally bad.
 */
fun Collection<Scored>.sweepConfidence(targetAccuracy: Double = TARGET_ACCURACY): Sweep {
    val answered = filterNot { it.unknown }
    val total = size

    val thresholds = (listOf(0.0) + answered.map { it.confidence })
        .distinct()
        .sorted()

    val points = thresholds.map { threshold ->
        val retained = answered.filter { it.confidence >= threshold }

        SweepPoint(
            threshold = threshold,
            retained = retained.size,
            total = total,
            correct = retained.count { it.octaveCorrect }
        )
    }

    // The lowest threshold that hits the target, because among thresholds that
    // are accurate enough the useful one is whichever keeps the most tracks.
    val recommended = points.firstOrNull {
        it.retained > 0 && it.accuracy >= targetAccuracy
    }

    val (right, wrong) = answered.partition { it.octaveCorrect }

    return Sweep(
        points = points,
        recommended = recommended,
        targetAccuracy = targetAccuracy,
        meanConfidenceWhenRight = right.meanConfidence(),
        meanConfidenceWhenWrong = wrong.meanConfidence()
    )
}

private fun List<Scored>.meanConfidence() =
    if (isEmpty()) Double.NaN else sumOf { it.confidence } / size

/**
 * Thins a sweep to at most [limit] rows for display.
 *
 * A sweep has one row per distinct confidence, which is a table nobody reads.
 * The recommended point is always kept, since it is the one the reader came for.
 */
fun Sweep.forDisplay(limit: Int = 20): List<SweepPoint> {
    require(limit > 0) { "limit must be positive, was $limit" }

    if (points.size <= limit) return points

    val step = points.size.toDouble() / limit
    val sampled = (0 until limit).map { points[(it * step).toInt()] }

    return (sampled + listOfNotNull(recommended) + points.last())
        .distinct()
        .sortedBy { it.threshold }
}
