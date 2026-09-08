package lol.alphaliu01.runningmusic.analysis

import android.net.Uri
import com.sosauce.chocola.data.models.CuteTrack

/**
 * How a track is analysed.
 *
 * Defaults measured rather than guessed; see docs/private/BPM_ACCURACY.md.
 *
 * @property sampleRate 22050 with a 256-sample hop scores identically to 44100
 *   with a 512-sample hop on every metric, for half the decoding. What matters
 *   is the hop's length in milliseconds, not in samples: at 22050 a 512-sample
 *   hop lasts 23 ms instead of 12, and strict accuracy collapses from 76% to
 *   33% because the beat tracker can no longer place a beat finely enough.
 * @property windowSeconds the length of each of the two windows. Two of these
 *   are decoded in one pass, so the audio actually read is twice this.
 * @property minimumWindowSeconds below which a track is analysed as one window
 *   rather than split. Splitting a two-minute track into two one-minute windows
 *   is fine; splitting a forty-second one leaves two windows too short for
 *   either to mean much.
 */
data class AnalysisConfig(
    val sampleRate: Int = 22_050,
    val windowSeconds: Int = 45,
    val minimumWindowSeconds: Int = 20
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(windowSeconds > 0) { "windowSeconds must be positive, was $windowSeconds" }
        require(minimumWindowSeconds in 1..windowSeconds) {
            "minimumWindowSeconds must be positive and no larger than windowSeconds"
        }
    }
}

/**
 * Establishes one track's tempo: tag first, then decode and analyse.
 *
 * Deliberately knows nothing about jobs, progress or storage. That belongs to
 * [BpmAnalysisWorker], and keeping it out of here is what lets this be reasoned
 * about as "given a file, what is its tempo".
 */
class BpmAnalyser(
    private val decoder: PcmDecoder,
    private val analyser: TempoAnalyser,
    private val tags: BpmTagReader,
    private val config: AnalysisConfig = AnalysisConfig()
) {

    sealed interface Result {
        /** The file said so itself, and no decoding was needed. */
        data class FromTag(val bpm: Float) : Result

        data class FromAnalysis(val verdict: TempoVerdict) : Result
    }

    fun analyse(track: CuteTrack): Result {
        tags.read(track.uri)?.let { return Result.FromTag(it) }

        return Result.FromAnalysis(analyse(track.uri))
    }

    private fun analyse(uri: Uri): TempoVerdict {
        val samples = try {
            decoder.decodeMiddle(uri, config.windowSeconds * 2, config.sampleRate)
        } catch (e: DecodeException) {
            return TempoVerdict.Unknown(e.message ?: "could not decode")
        }

        val minimum = config.minimumWindowSeconds * config.sampleRate

        if (samples.size < minimum) return TempoVerdict.Unknown(TOO_SHORT)

        // One decode, split in two. Decoding dominates the cost by a wide
        // margin, so the second opinion is very nearly free.
        val half = samples.size / 2

        if (half < minimum) {
            return decideTempo(analyser.analyse(samples, config.sampleRate), second = null)
        }

        return decideTempo(
            analyser.analyse(samples.copyOfRange(0, half), config.sampleRate),
            analyser.analyse(samples.copyOfRange(half, samples.size), config.sampleRate)
        )
    }

    companion object {
        const val TOO_SHORT = "too little audio to analyse"
    }
}
