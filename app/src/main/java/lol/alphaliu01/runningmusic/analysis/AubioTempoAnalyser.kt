package lol.alphaliu01.runningmusic.analysis

import lol.alphaliu01.runningmusic.aubio.AubioTempo

/**
 * The shipped estimator: the vendored aubio beat tracker, behind the interface.
 *
 * Feeds the block one hop at a time and zero pads the last partial hop, which is
 * what `bpm_probe` does on the desktop. Keeping the two identical is what makes
 * the accuracy figures in docs/private/BPM_ACCURACY.md apply to the phone.
 */
class AubioTempoAnalyser(
    /**
     * 256 rather than aubio's own 512, because [AnalysisConfig] decodes at
     * 22050 Hz. A hop is a unit of time, not of samples: 256 at 22050 is the
     * same 12 ms that 512 is at 44100, and keeping that constant is what makes
     * halving the sample rate free.
     */
    override val hopSize: Int = 256,
    private val bufferSize: Int = AubioTempo.DEFAULT_BUFFER_SIZE
) : TempoAnalyser {

    override fun analyse(samples: FloatArray, sampleRate: Int): TempoEstimate {
        if (samples.size < bufferSize) return TempoEstimate.UNKNOWN

        return AubioTempo(sampleRate = sampleRate, hopSize = hopSize, bufferSize = bufferSize)
            .use { tempo ->
                val hop = FloatArray(hopSize)
                var at = 0

                while (at < samples.size) {
                    val take = minOf(hopSize, samples.size - at)

                    samples.copyInto(hop, 0, at, at + take)

                    // At most one hop of silence, at the very end. Passing a
                    // short hop would throw, and dropping it would discard up to
                    // 12 ms that might hold the final beat.
                    if (take < hopSize) hop.fill(0f, take)

                    tempo.feed(hop)
                    at += hopSize
                }

                TempoEstimate(
                    bpm = tempo.bpm,
                    confidence = tempo.confidence,
                    beats = tempo.beats
                )
            }
    }
}
