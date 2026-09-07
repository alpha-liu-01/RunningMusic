package lol.alphaliu01.runningmusic.cadence

import kotlin.math.roundToLong

/**
 * How much of a library a given cadence and tolerance can actually use.
 *
 * @property accepted tracks inside the band.
 * @property total tracks considered, ignoring unusable rows.
 * @property stretchedMs playing time the accepted tracks provide, at the speeds
 * they would be played at.
 */
data class Coverage(
    val accepted: Int,
    val total: Int,
    val stretchedMs: Long,
) {
    val fraction: Double get() = if (total == 0) 0.0 else accepted.toDouble() / total
}

/**
 * Measures what [library] offers at [targetCadence] within [band].
 *
 * Library coverage is a lumpy function of cadence rather than a smooth one: a
 * cadence whose half-value lands on a real tempo peak does well (180 to 90),
 * one that lands in a trough does badly (170 to 85, between the 80 and 90
 * clusters). Sweeping this function over candidate cadences is what makes it
 * possible to suggest a target a few spm away that the library actually
 * supports.
 */
fun <T> coverageAt(
    library: List<Candidate<T>>,
    targetCadence: Double,
    band: ToleranceBand,
): Coverage {
    require(targetCadence > 0.0 && targetCadence.isFinite()) {
        "targetCadence must be positive and finite, was $targetCadence"
    }

    var total = 0
    var accepted = 0
    var stretchedMs = 0L

    for (candidate in library) {
        if (candidate.bpm <= 0.0 || !candidate.bpm.isFinite() || candidate.durationMs <= 0) continue
        total++

        val fold = fold(candidate.bpm, targetCadence)
        if (!band.accepts(fold)) continue

        accepted++
        stretchedMs += (candidate.durationMs / fold.speed).roundToLong()
    }

    return Coverage(accepted = accepted, total = total, stretchedMs = stretchedMs)
}
