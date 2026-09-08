package lol.alphaliu01.runningmusic.aubio

/**
 * Estimates the tempo of a track from its mono PCM.
 *
 * Feed the whole track through [feed] one hop at a time, then read [bpm]. The
 * answer is the median interval between detected beats rather than a running
 * estimate, so it only means anything once the whole track has been fed.
 *
 * The boundary is deliberately a [FloatArray] and not a decoder: what to decode
 * with, and how, is not this class's problem, and drawing the line here is what
 * lets the same estimator be measured on a desktop over a corpus.
 *
 * Not thread safe, and holds native memory, so it must be closed. Using it after
 * closing throws rather than reading freed memory.
 */
class AubioTempo(
    val sampleRate: Int,
    val hopSize: Int = DEFAULT_HOP_SIZE,
    bufferSize: Int = DEFAULT_BUFFER_SIZE
) : AutoCloseable {

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(hopSize in 1 until bufferSize) {
            "hopSize must be positive and shorter than bufferSize, was $hopSize of $bufferSize"
        }
    }

    // Declared after the checks so they run first; a property initialiser and an
    // init block execute in the order they appear.
    private var handle: Long = nativeNew(sampleRate, bufferSize, hopSize)

    init {
        check(handle != 0L) { "could not create a tempo estimator" }
    }

    /**
     * Feeds exactly [hopSize] samples, nominally in -1 to 1.
     *
     * A track's final partial hop should be zero padded to length rather than
     * passed short, which throws.
     *
     * @return whether a beat fell inside this hop, which is only useful for
     *   diagnostics; the tempo comes from [bpm].
     */
    fun feed(hop: FloatArray): Boolean {
        require(hop.size == hopSize) { "expected $hopSize samples, got ${hop.size}" }

        return nativeFeed(alive(), hop, hop.size)
    }

    /**
     * The estimated tempo, or 0 when too few beats were found to say.
     *
     * Zero means unknown and must not be shown as a tempo. A track whose tempo
     * is unknown should be left out of a run rather than guessed at, because a
     * wrong answer here does not mildly misorder a playlist, it plays the track
     * at a wildly wrong speed.
     */
    val bpm: Float
        get() = nativeBpm(alive())

    /**
     * How much aubio trusts the beats it found, as a median over them.
     *
     * Higher is better, but the scale is aubio's own and has no fixed maximum,
     * so the threshold below which a tempo is refused has to be calibrated
     * against a real corpus rather than picked.
     */
    val confidence: Float
        get() = nativeConfidence(alive())

    /** How many beats were detected, worth reporting even when [bpm] is 0. */
    val beats: Int
        get() = nativeBeats(alive())

    override fun close() {
        if (handle == 0L) return

        nativeFree(handle)
        handle = 0L
    }

    private fun alive(): Long {
        check(handle != 0L) { "this AubioTempo has been closed" }

        return handle
    }

    private external fun nativeNew(sampleRate: Int, bufferSize: Int, hopSize: Int): Long

    private external fun nativeFree(handle: Long)

    private external fun nativeFeed(handle: Long, hop: FloatArray, length: Int): Boolean

    private external fun nativeBpm(handle: Long): Float

    private external fun nativeConfidence(handle: Long): Float

    private external fun nativeBeats(handle: Long): Int

    companion object {
        /** aubio's own default, and what its beat tracker is tuned around. */
        const val DEFAULT_BUFFER_SIZE = 1024

        const val DEFAULT_HOP_SIZE = 512

        init {
            System.loadLibrary("rmtempo")
        }
    }
}
