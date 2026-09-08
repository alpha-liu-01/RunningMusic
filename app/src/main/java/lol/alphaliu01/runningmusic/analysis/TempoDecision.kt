package lol.alphaliu01.runningmusic.analysis

import lol.alphaliu01.runningmusic.cadence.accuracy.ACCURACY_TOLERANCE_OCTAVES
import lol.alphaliu01.runningmusic.cadence.accuracy.octaveDistance
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_MANUAL
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_NONE
import lol.alphaliu01.runningmusic.library.TrackMetadata

/**
 * How far apart two windows may land and still count as the same tempo.
 *
 * Octave distance, so a window reporting 85 against another's 170 agrees
 * perfectly. That is not leniency: playback speed is chosen by folding tempo by
 * powers of two, so those two answers produce identical music, and a metric that
 * called them a disagreement would be measuring the wrong thing.
 *
 * The width is the 4% the tempo-estimation literature scores at, which is far
 * tighter than the app needs and so costs nothing to insist on.
 */
val AGREEMENT_TOLERANCE = ACCURACY_TOLERANCE_OCTAVES

/** What analysing a track concluded. */
sealed interface TempoVerdict {

    /** Two windows agreed, or a track was too short to disagree with itself. */
    data class Known(val bpm: Float, val confidence: Float, val beats: Int) : TempoVerdict

    /**
     * We looked and could not tell.
     *
     * Distinct from never having looked, and recorded as such, or the job would
     * decode its own failures again on every run.
     */
    data class Unknown(val reason: String) : TempoVerdict
}

/**
 * Decides whether two windows of the same track agree well enough to believe.
 *
 * Agreement, rather than the estimator's own confidence, because
 * docs/private/BPM_ACCURACY.md found aubio's confidence anti-correlated with
 * correctness on the synthetic corpus: the wrong answers were the more confident
 * ones. Two disjoint stretches of the same recording independently arriving at
 * the same tempo is a claim about the music rather than about the estimator's
 * self-regard, and it costs one extra pass over audio already decoded.
 *
 * Passing null for [second] is how a track too short to split says so. It is
 * accepted on one window, because refusing to analyse short tracks at all would
 * be a worse answer than analysing them with less evidence.
 */
fun decideTempo(first: TempoEstimate, second: TempoEstimate?): TempoVerdict {
    if (second == null) {
        return if (first.isKnown) first.toKnown() else TempoVerdict.Unknown(NO_BEATS)
    }

    if (!first.isKnown && !second.isKnown) return TempoVerdict.Unknown(NO_BEATS)

    // One window finding nothing is not agreement. A quiet or beatless half of a
    // track tells us nothing about the half that did report a tempo, and
    // accepting the single answer would be exactly the unchecked guess this
    // function exists to avoid.
    if (!first.isKnown || !second.isKnown) return TempoVerdict.Unknown(ONE_WINDOW_SILENT)

    val distance = octaveDistance(first.bpm.toDouble(), second.bpm.toDouble())

    if (distance > AGREEMENT_TOLERANCE) return TempoVerdict.Unknown(DISAGREED)

    // Either window's answer would do, since they are the same tempo up to
    // octaves and the fold absorbs the difference. The one with more beats saw
    // more evidence, so it is the better-supported of two equally usable
    // answers.
    return (if (first.beats >= second.beats) first else second).toKnown()
}

const val NO_BEATS = "no beats found"
const val ONE_WINDOW_SILENT = "only one window found a tempo"
const val DISAGREED = "the two windows disagreed"

private fun TempoEstimate.toKnown() = TempoVerdict.Known(bpm, confidence, beats)

/**
 * Whether a track still needs looking at.
 *
 * A hand-entered tempo is never touched: someone who took the trouble to measure
 * one outranks a detector, and silently overwriting it would be the rudest thing
 * this job could do.
 *
 * Everything else turns on [TrackMetadata.analysedAt] rather than on whether a
 * tempo is present, which is what keeps a track we tried and failed on from
 * being decoded again every single run.
 */
fun needsAnalysis(existing: TrackMetadata?, force: Boolean): Boolean {
    if (existing == null) return true
    if (existing.bpmSource == BPM_SOURCE_MANUAL) return false
    if (force) return true

    return existing.analysedAt == null && existing.bpmSource == BPM_SOURCE_NONE
}
