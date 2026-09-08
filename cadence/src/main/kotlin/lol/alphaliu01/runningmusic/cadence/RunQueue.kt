package lol.alphaliu01.runningmusic.cadence

import kotlin.math.roundToLong

/**
 * A track the caller is offering, paired with whatever it uses to identify one.
 *
 * The module never looks at [ref]; it exists so callers get their own type back
 * out of [selectForRun] instead of an index.
 */
data class Candidate<out T>(
    val ref: T,
    val bpm: Double,
    val durationMs: Long,
)

/**
 * A track that made it into the run, with the speed it will be played at.
 *
 * @property stretchedDurationMs how long this track actually occupies at that
 * speed, which is what fills the run.
 */
data class Selected<out T>(
    val ref: T,
    val fold: Fold,
    val stretchedDurationMs: Long,
)

/**
 * @property filledMs total stretched duration of [tracks].
 * @property shortfallMs how much of the requested run is still uncovered, 0 when filled.
 * @property worstBand the tolerance that fell out of the selection, or null when
 * nothing was selected. This is the honest figure to show the user: "45 minutes,
 * 68 tracks, at most 7% stretch".
 */
data class RunQueue<out T>(
    val tracks: List<Selected<T>>,
    val filledMs: Long,
    val shortfallMs: Long,
    val worstBand: ToleranceBand?,
)

/**
 * Picks tracks whose folded tempo suits [targetCadence] until [runLengthMs] is
 * covered.
 *
 * Rather than having the user choose a tolerance and discover how many tracks
 * survive, they state how long they are running and the tolerance falls out:
 * candidates are ranked by how far they must be stretched and taken in order
 * until the run is full, so the result minimises average distortion instead of
 * merely bounding it.
 *
 * [band] is a hard limit that ranking never exceeds. A library too thin to
 * fill the run comes back short rather than stretched past what the listener
 * said they would accept.
 *
 * Candidates with a non-positive bpm or duration are dropped rather than
 * rejected loudly, because a tempo detector that fails on one file should not
 * take the whole run with it.
 *
 * Selection is deterministic: ties in deviation are broken by input order. The
 * queue should be shuffled within the accepted set before playback so runs do
 * not all open with the same songs, but that is ordering rather than selection
 * and belongs with the player.
 */
fun <T> selectForRun(
    library: List<Candidate<T>>,
    targetCadence: Double,
    runLengthMs: Long,
    band: ToleranceBand = ToleranceBand.DEFAULT,
): RunQueue<T> {
    require(targetCadence > 0.0 && targetCadence.isFinite()) {
        "targetCadence must be positive and finite, was $targetCadence"
    }
    require(runLengthMs >= 0) { "runLengthMs must not be negative, was $runLengthMs" }

    val ranked = library
        .asSequence()
        .filter { it.bpm > 0.0 && it.bpm.isFinite() && it.durationMs > 0 }
        .map { it to fold(it.bpm, targetCadence) }
        .filter { (_, fold) -> band.accepts(fold) }
        .sortedBy { (_, fold) -> fold.absLogDeviation }
        .toList()

    val chosen = mutableListOf<Selected<T>>()
    var filledMs = 0L

    for ((candidate, fold) in ranked) {
        if (filledMs >= runLengthMs) break
        val stretched = (candidate.durationMs / fold.speed).roundToLong()
        chosen += Selected(candidate.ref, fold, stretched)
        filledMs += stretched
    }

    return RunQueue(
        tracks = chosen,
        filledMs = filledMs,
        shortfallMs = (runLengthMs - filledMs).coerceAtLeast(0L),
        worstBand = chosen.worstBand(),
    )
}

/**
 * The tightest band that would still admit every track here, reported as the
 * asymmetric shape actually used rather than a single ratio.
 */
private fun <T> List<Selected<T>>.worstBand(): ToleranceBand? {
    if (isEmpty()) return null
    return ToleranceBand(
        maxSpeedUp = maxOf(1.0, maxOf { it.fold.speed }),
        maxSlowDown = maxOf(1.0, maxOf { 1.0 / it.fold.speed }),
    )
}
