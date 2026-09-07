package lol.alphaliu01.runningmusic.analysis

/**
 * What an estimator made of a block of audio.
 *
 * @property bpm the tempo, or 0 when too few beats were found to say. Zero means
 *   unknown and must never be shown or stored as a tempo.
 * @property confidence the estimator's own opinion, on its own scale. Recorded
 *   rather than acted on: docs/private/BPM_ACCURACY.md found aubio's confidence
 *   anti-correlated with correctness, so trust comes from agreement between
 *   windows instead.
 * @property beats how many beats were detected, which is worth keeping even
 *   when [bpm] is 0 because it distinguishes silence from a rhythm too
 *   irregular to measure.
 */
data class TempoEstimate(
    val bpm: Float,
    val confidence: Float,
    val beats: Int
) {
    val isKnown: Boolean get() = bpm > 0f

    companion object {
        val UNKNOWN = TempoEstimate(bpm = 0f, confidence = 0f, beats = 0)
    }
}

/**
 * Estimates the tempo of mono PCM.
 *
 * An interface over a [FloatArray] rather than over a file, so that the engine
 * behind it can be swapped without anything above noticing, the pipeline can be
 * unit tested against a fake, and the same estimator can be measured on a
 * desktop over a corpus. That last point is not hypothetical: the accuracy
 * figures in docs/private/BPM_ACCURACY.md come from running exactly this C over
 * exactly this boundary.
 */
interface TempoAnalyser {

    /** The block size the analyser prefers, in samples. */
    val hopSize: Int

    fun analyse(samples: FloatArray, sampleRate: Int): TempoEstimate
}
