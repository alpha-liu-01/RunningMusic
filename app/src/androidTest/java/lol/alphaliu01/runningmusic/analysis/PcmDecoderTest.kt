package lol.alphaliu01.runningmusic.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decoder against real platform codecs.
 *
 * The audio is generated at test time rather than committed, so the repository
 * carries no binaries and the test cannot quietly start passing against a stale
 * fixture. Two paths are covered because they exercise different halves of the
 * class: a hand-written WAV goes through the raw PCM path, and an AAC file made
 * with [MediaMuxer] goes through a real compressed decoder, which is where
 * [MediaFormat.KEY_PCM_ENCODING] and channel layouts actually vary.
 */
@RunWith(AndroidJUnit4::class)
class PcmDecoderTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "decoder-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun decodesAWavFileToMonoAtTheRequestedRate() {
        val file = writeWav("tone.wav", seconds = 4, sampleRate = 44_100, channels = 1)

        val samples = PcmDecoder(context).decodeMiddle(
            uri = Uri.fromFile(file),
            durationSeconds = 2,
            sampleRate = 22_050
        )

        // Rate conversion happens, and the result is one channel: two seconds at
        // 22050 is 44100 samples however the file was stored.
        assertNear(expected = 44_100, actual = samples.size, tolerance = 4_410)
        assertTrue("decoded a silent buffer", samples.any { abs(it) > 0.1f })
    }

    @Test
    fun downmixesStereoToOneChannel() {
        val file = writeWav("stereo.wav", seconds = 4, sampleRate = 44_100, channels = 2)

        val samples = PcmDecoder(context).decodeMiddle(
            uri = Uri.fromFile(file),
            durationSeconds = 2,
            sampleRate = 44_100
        )

        // Two seconds of stereo is four seconds' worth of interleaved samples.
        // Getting this wrong is the classic bug: the analysis then runs at half
        // speed and reports half the tempo, plausibly enough to go unnoticed.
        assertNear(expected = 88_200, actual = samples.size, tolerance = 8_820)
    }

    @Test
    fun takesFromTheMiddleOfALongerFile() {
        // Ten seconds in, only the second half carries a tone. Asking for four
        // seconds from the middle must land in it.
        val file = writeWav("late.wav", seconds = 10, sampleRate = 44_100, channels = 1) { i ->
            if (i < 44_100 * 5) 0f else sin(2.0 * PI * 440.0 * i / 44_100).toFloat() * 0.5f
        }

        val samples = PcmDecoder(context).decodeMiddle(
            uri = Uri.fromFile(file),
            durationSeconds = 4,
            sampleRate = 44_100
        )

        assertTrue("took the silent half", samples.any { abs(it) > 0.1f })
    }

    @Test
    fun aFileShorterThanTheWindowIsTakenWhole() {
        val file = writeWav("short.wav", seconds = 2, sampleRate = 44_100, channels = 1)

        val samples = PcmDecoder(context).decodeMiddle(
            uri = Uri.fromFile(file),
            durationSeconds = 30,
            sampleRate = 44_100
        )

        assertNear(expected = 88_200, actual = samples.size, tolerance = 8_820)
    }

    @Test
    fun decodesCompressedAudio() {
        val file = writeAac("tone.m4a", seconds = 6, sampleRate = 44_100)

        val samples = PcmDecoder(context).decodeMiddle(
            uri = Uri.fromFile(file),
            durationSeconds = 4,
            sampleRate = 22_050
        )

        // Generous on length: an encoder is allowed priming and padding, and a
        // seek lands on a sync frame rather than a sample. What is being checked
        // is that a real codec's output arrives as sane mono audio at all.
        assertTrue("decoded ${samples.size} samples, expected seconds of audio", samples.size > 22_050)
        assertTrue("decoded a silent buffer", samples.any { abs(it) > 0.05f })
        assertTrue("samples outside the normalised range", samples.all { abs(it) <= 1.01f })
    }

    @Test
    fun aClickTracksTempoSurvivesTheRoundTrip() {
        // The end-to-end claim: what comes out of a real decoder is still
        // something the estimator can read a tempo from. Everything upstream of
        // this can be right and the feature still be useless if it is not.
        val sampleRate = 22_050
        val file = writeWav("clicks.wav", seconds = 40, sampleRate = sampleRate, channels = 1) { i ->
            clickTrack(i, bpm = 120.0, sampleRate = sampleRate)
        }

        val samples = PcmDecoder(context).decodeMiddle(
            uri = Uri.fromFile(file),
            durationSeconds = 30,
            sampleRate = sampleRate
        )

        val estimate = AubioTempoAnalyser().analyse(samples, sampleRate)

        assertTrue("no tempo found in a 120 BPM click track", estimate.isKnown)

        // Octave-agnostic, because 60 and 240 are the same music and the app
        // folds them to the same playback speed anyway.
        val folded = generateSequence(estimate.bpm.toDouble()) { it * 2 }.take(4)
            .plus(generateSequence(estimate.bpm.toDouble()) { it / 2 }.take(4))
            .any { abs(it - 120.0) < 120.0 * 0.05 }

        assertTrue("estimated ${estimate.bpm}, which is not 120 at any octave", folded)
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(
            "expected about $expected samples, got $actual",
            abs(actual - expected) <= tolerance
        )

    /**
     * A click at every beat over a quiet noise floor.
     *
     * The noise floor is not decoration: aubio discards blocks below about
     * -90 dBFS as silence, and digital silence between clicks makes it throw
     * away the very beats the test is looking for.
     */
    private fun clickTrack(i: Int, bpm: Double, sampleRate: Int): Float {
        val period = (sampleRate * 60.0 / bpm).roundToInt()
        val into = i % period
        val noise = ((i * 1_103_515_245L + 12_345L) % 2_001 - 1_000) / 1_000f * 0.001f

        if (into >= sampleRate / 100) return noise

        val decay = 1f - into.toFloat() / (sampleRate / 100)

        return (sin(2.0 * PI * 1_000.0 * into / sampleRate).toFloat() * 0.8f * decay) + noise
    }

    private fun writeWav(
        name: String,
        seconds: Int,
        sampleRate: Int,
        channels: Int,
        sample: (Int) -> Float = { sin(2.0 * PI * 440.0 * it / sampleRate).toFloat() * 0.5f }
    ): File {
        val frames = seconds * sampleRate
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)

        repeat(frames) { frame ->
            val value = (sample(frame) * 32_767).toInt().coerceIn(-32_768, 32_767).toShort()

            repeat(channels) { data.putShort(value) }
        }

        val file = File(directory, name)

        RandomAccessFile(file, "rw").use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            val byteRate = sampleRate * channels * 2

            header.put("RIFF".toByteArray())
            header.putInt(36 + data.capacity())
            header.put("WAVEfmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)                          // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * 2).toShort())   // block align
            header.putShort(16)                         // bits per sample
            header.put("data".toByteArray())
            header.putInt(data.capacity())

            out.write(header.array())
            out.write(data.array())
        }

        return file
    }

    /** Encodes a tone to AAC in an MP4 container, so a real codec is in the path. */
    private fun writeAac(name: String, seconds: Int, sampleRate: Int): File {
        val file = File(directory, name)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val totalFrames = seconds * sampleRate
            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(10_000)

                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!.also { it.clear() }
                        val frames = minOf(buffer.capacity() / 2, totalFrames - written)

                        if (frames <= 0) {
                            encoder.queueInputBuffer(
                                index, 0, 0,
                                written * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()

                            repeat(frames) {
                                val at = written + it
                                val value = sin(2.0 * PI * 440.0 * at / sampleRate) * 0.5

                                shorts.put((value * 32_767).toInt().toShort())
                            }

                            encoder.queueInputBuffer(
                                index, 0, frames * 2,
                                written * 1_000_000L / sampleRate, 0
                            )
                            written += frames
                        }
                    }
                }

                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }

                    else -> if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index)!!

                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(track, buffer, info)
                        }

                        encoder.releaseOutputBuffer(index, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            muxer.stop()
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            muxer.release()
        }

        return file
    }

    @Test
    fun resamplingHalvesTheLengthWhenTheRateHalves() {
        val samples = FloatArray(1_000) { it / 1_000f }

        assertEquals(500, resample(samples, from = 44_100, to = 22_050).size)
        assertEquals(2_000, resample(samples, from = 22_050, to = 44_100).size)
        assertEquals(1_000, resample(samples, from = 44_100, to = 44_100).size)
    }
}
