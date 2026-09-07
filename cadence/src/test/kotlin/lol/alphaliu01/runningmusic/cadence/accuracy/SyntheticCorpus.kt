package lol.alphaliu01.runningmusic.cadence.accuracy

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A corpus that needs no files.
 *
 * Generated from a fixed seed with a fixed algorithm, so it is byte for byte
 * the same on every machine and in every run. That is the whole point: the
 * harness is runnable today, on a laptop with no music on it, and a regression
 * in the estimator shows up as a changed number rather than as nothing at all.
 *
 * It is also, unavoidably, easier than real music. Synthetic beats are exactly
 * where they say they are, the tempo never drifts, and nothing else in the
 * spectrum competes with the beat. Any accuracy figure from here is optimistic
 * and the report says so.
 */

/** Tempos to generate, in beats per minute. */
val SYNTHETIC_TEMPOS = listOf(
    60.0, 70.0, 80.0, 85.0, 90.0, 100.0, 110.0,
    // 120 to 130 densely, because that cluster is where a small tempo error
    // moves a track between playback speeds most easily.
    120.0, 125.0, 128.0, 130.0,
    140.0, 150.0, 160.0, 170.0, 180.0, 200.0
)

/** The seed for every generator, so two runs cannot disagree. */
const val SYNTHETIC_SEED = 0x5eed1234

const val SYNTHETIC_DURATION_SECONDS = 30

/**
 * How each variant is hard.
 *
 * Chosen so failures are informative rather than merely counted: if the corpus
 * is only clicks, an accuracy figure says nothing about which kinds of music
 * the estimator struggles with.
 */
enum class Variant(val description: String) {
    /** Plain clicks on every beat. The plan 6 stimulus, and the easy case. */
    CLICK("clicks on every beat"),

    /** A low thud on 1 and 3 against a brighter burst on 2 and 4. */
    BACKBEAT("kick on 1 and 3, snare on 2 and 4"),

    /**
     * Events only on 1 and 3.
     *
     * Invites exactly the octave error: half the true tempo is a perfectly
     * reasonable reading of this signal, and the octave-agnostic metric is what
     * decides whether that matters.
     */
    SPARSE("events on 1 and 3 only"),

    /** Offbeats pushed late, so the inter-onset intervals are uneven. */
    SWUNG("swung eighths, offbeats late"),

    /** Clicks over a sustained tone, so the spectrum holds something that is not the beat. */
    PADDED("clicks over a sustained pad");

    val id: String get() = name.lowercase()
}

/** One generated track, ready to feed to the probe. */
data class SyntheticTrack(
    val name: String,
    val variant: Variant,
    val truthBpm: Double,
    val sampleRate: Int
) {
    fun render(durationSeconds: Int = SYNTHETIC_DURATION_SECONDS): FloatArray =
        renderSynthetic(variant, truthBpm, sampleRate, durationSeconds)
}

/** The full corpus, in a fixed order so results.tsv sorts the same way every run. */
fun syntheticCorpus(sampleRate: Int = 44100): List<SyntheticTrack> =
    Variant.entries.flatMap { variant ->
        SYNTHETIC_TEMPOS.map { bpm ->
            SyntheticTrack(
                name = "%s-%03d".format(variant.id, bpm.toInt()),
                variant = variant,
                truthBpm = bpm,
                sampleRate = sampleRate
            )
        }
    }

/**
 * The same linear congruential generator the C smoke test uses.
 *
 * A named generator rather than [kotlin.random.Random] because the value here
 * is that the noise is identical everywhere, including to the C fixture, and a
 * standard library's algorithm is free to change between versions.
 */
private class Lcg(seed: Int) {
    private var state: Int = seed

    /** Uniform in `[-0.5, 0.5)`. */
    fun nextCentred(): Double {
        state = state * 1664525 + 1013904223

        return ((state ushr 8).toDouble() / (1 shl 24).toDouble()) - 0.5
    }
}

/**
 * The noise floor, at roughly -60 dBFS.
 *
 * Not decoration, and plan 6 paid for this lesson: aubio discards any beat it
 * predicts inside a hop quieter than -90 dBFS. With digital silence between
 * clicks most correctly predicted beats get thrown away and the tempo comes out
 * several times too slow. Real recordings always have a floor.
 */
private const val NOISE_AMPLITUDE = 0.002

private fun renderSynthetic(
    variant: Variant,
    bpm: Double,
    sampleRate: Int,
    durationSeconds: Int
): FloatArray {
    require(bpm > 0.0) { "bpm must be positive, was $bpm" }
    require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    require(durationSeconds > 0) { "durationSeconds must be positive, was $durationSeconds" }

    val total = sampleRate * durationSeconds
    val buffer = FloatArray(total)
    val noise = Lcg(SYNTHETIC_SEED)

    for (i in 0 until total) {
        buffer[i] = (noise.nextCentred() * NOISE_AMPLITUDE).toFloat()
    }

    if (variant == Variant.PADDED) writePad(buffer, sampleRate)

    val samplesPerBeat = 60.0 / bpm * sampleRate

    var beat = 0
    while (true) {
        val at = (beat * samplesPerBeat).toInt()
        if (at >= total) break

        when (variant) {
            Variant.CLICK, Variant.PADDED ->
                writeBurst(buffer, at, sampleRate, frequency = 1000.0, decay = 120.0, gain = 0.8)

            Variant.BACKBEAT -> if (beat % 2 == 0) {
                writeBurst(buffer, at, sampleRate, frequency = 80.0, decay = 40.0, gain = 0.9)
            } else {
                writeBurst(buffer, at, sampleRate, frequency = 2500.0, decay = 200.0, gain = 0.7)
            }

            Variant.SPARSE -> if (beat % 4 == 0 || beat % 4 == 2) {
                writeBurst(buffer, at, sampleRate, frequency = 1000.0, decay = 120.0, gain = 0.8)
            }

            Variant.SWUNG -> {
                writeBurst(buffer, at, sampleRate, frequency = 1000.0, decay = 120.0, gain = 0.8)

                // The offbeat two thirds of the way through the beat rather than
                // halfway, which is the usual swing and makes the intervals
                // alternate long-short.
                val offbeat = at + (samplesPerBeat * 2.0 / 3.0).toInt()
                writeBurst(buffer, offbeat, sampleRate, frequency = 1600.0, decay = 200.0, gain = 0.4)
            }
        }

        beat++
    }

    return buffer
}

/** A decaying tone burst, the stimulus aubio's spectral flux onset detector can see. */
private fun writeBurst(
    buffer: FloatArray,
    offset: Int,
    sampleRate: Int,
    frequency: Double,
    decay: Double,
    gain: Double
) {
    if (offset >= buffer.size) return

    val length = sampleRate / 50 // 20 ms

    for (i in 0 until length) {
        val at = offset + i
        if (at >= buffer.size) return

        val t = i.toDouble() / sampleRate

        buffer[at] += (gain * exp(-t * decay) * sin(2.0 * PI * frequency * t)).toFloat()
    }
}

/**
 * A quiet two-note drone under the clicks.
 *
 * Gives the padded variant a spectrum that holds steady energy unrelated to the
 * beat, which is the ordinary situation in recorded music and something a bare
 * click track never presents.
 */
private fun writePad(buffer: FloatArray, sampleRate: Int) {
    for (i in buffer.indices) {
        val t = i.toDouble() / sampleRate

        buffer[i] += (0.06 * sin(2.0 * PI * 220.0 * t) + 0.04 * sin(2.0 * PI * 330.0 * t)).toFloat()
    }
}
