package lol.alphaliu01.runningmusic.aubio

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same click tracks the host smoke test uses, run through the JNI layer on
 * real hardware.
 *
 * This is the only thing that proves the arm64 library actually loads, that the
 * array marshalling is right, and that the phone agrees with the desktop. The
 * estimator itself is tested far more cheaply by `scripts/build-host-aubio.sh`,
 * so this stays deliberately thin.
 */
@RunWith(AndroidJUnit4::class)
class AubioTempoTest {

    @Test
    fun recoversTheTempoOfAClickTrack() {
        for (truth in listOf(100f, 120f, 160f)) {
            val track = clickTrack(truth)

            AubioTempo(SAMPLE_RATE).use { tempo ->
                for (offset in track.indices step tempo.hopSize) {
                    tempo.feed(track.copyOfRange(offset, offset + tempo.hopSize))
                }

                assertTrue(
                    "$truth BPM: only ${tempo.beats} beats",
                    tempo.beats >= 4
                )
                assertTrue(
                    "$truth BPM: estimated ${tempo.bpm}",
                    octaveDistance(tempo.bpm, truth) < 0.05
                )
                assertTrue(
                    "$truth BPM: no confidence reported",
                    tempo.confidence > 0f
                )
            }
        }
    }

    @Test
    fun silenceHasNoTempoRatherThanATempoOfZero() {
        AubioTempo(SAMPLE_RATE).use { tempo ->
            val silence = FloatArray(tempo.hopSize)

            repeat(200) { tempo.feed(silence) }

            assertEquals(0, tempo.beats)
            assertEquals(0f, tempo.bpm, 0f)
        }
    }

    @Test
    fun aClosedEstimatorRefusesToBeUsed() {
        val tempo = AubioTempo(SAMPLE_RATE)
        tempo.close()

        assertThrows(IllegalStateException::class.java) { tempo.bpm }
        assertThrows(IllegalStateException::class.java) {
            tempo.feed(FloatArray(tempo.hopSize))
        }

        // Closing twice must not free the handle twice.
        tempo.close()
    }

    @Test
    fun aHopOfTheWrongLengthIsRejected() {
        AubioTempo(SAMPLE_RATE).use { tempo ->
            assertThrows(IllegalArgumentException::class.java) {
                tempo.feed(FloatArray(tempo.hopSize - 1))
            }
        }
    }

    @Test
    fun theEstimatorRejectsSettingsItCannotWorkWith() {
        assertThrows(IllegalArgumentException::class.java) {
            AubioTempo(sampleRate = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AubioTempo(SAMPLE_RATE, hopSize = 1024, bufferSize = 1024)
        }
    }

    @Test
    fun twoEstimatorsDoNotShareState() {
        // A handle mix-up would show up as the second one inheriting the first
        // one's beats, which is the sort of thing a static would cause.
        val track = clickTrack(120f)

        AubioTempo(SAMPLE_RATE).use { fed ->
            AubioTempo(SAMPLE_RATE).use { untouched ->
                for (offset in track.indices step fed.hopSize) {
                    fed.feed(track.copyOfRange(offset, offset + fed.hopSize))
                }

                assertNotEquals(0, fed.beats)
                assertEquals(0, untouched.beats)
            }
        }
    }

    /** Distance to the nearest octave of the true tempo; see the host test. */
    private fun octaveDistance(estimate: Float, truth: Float): Double {
        if (estimate <= 0f) return Double.MAX_VALUE

        val ratio = ln(estimate.toDouble() / truth) / ln(2.0)

        return abs(ratio - ratio.roundToInt())
    }

    /**
     * A click track with a quiet noise floor under it.
     *
     * The floor is not decoration: aubio discards beats predicted inside a hop
     * below -90 dBFS, and without it the digital silence between clicks would
     * throw away most of the beats and the tempo would come out several times
     * too slow.
     */
    private fun clickTrack(bpm: Float): FloatArray {
        val hops = SAMPLE_RATE * DURATION_SECONDS / AubioTempo.DEFAULT_HOP_SIZE
        val track = FloatArray(hops * AubioTempo.DEFAULT_HOP_SIZE)

        var noise = 0x5eed1234
        for (i in track.indices) {
            noise = noise * 1664525 + 1013904223
            track[i] = ((noise ushr 8).toDouble() / (1 shl 24) - 0.5).toFloat() * 0.002f
        }

        val interval = 60.0 / bpm * SAMPLE_RATE
        val clickLength = SAMPLE_RATE / 50 // 20 ms
        var beat = 0

        while ((beat * interval).toInt() < track.size) {
            val start = (beat * interval).toInt()

            for (i in 0 until clickLength) {
                if (start + i >= track.size) break

                val t = i.toDouble() / SAMPLE_RATE
                track[start + i] += (0.8 * exp(-t * 120.0) * sin(2.0 * PI * 1000.0 * t)).toFloat()
            }

            beat++
        }

        return track
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val DURATION_SECONDS = 30
    }
}
