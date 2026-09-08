package lol.alphaliu01.runningmusic.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a slice of an audio file into mono float samples.
 *
 * Offline and much faster than realtime, which is the whole reason this is not
 * an [androidx.media3.common.audio.AudioProcessor] hanging off playback: a
 * track's tempo has to be known before it is played, not after.
 *
 * The output is a bare [FloatArray] so that everything above this class is
 * unaware of Android. That boundary is what lets the estimator be measured on a
 * desktop over a corpus and lets this class be tested on its own.
 */
class PcmDecoder(private val context: Context) {

    /**
     * Decodes [durationSeconds] from the middle of a track.
     *
     * From the middle because intros, fades and applause are the least
     * representative parts of a recording, and because a whole five-minute file
     * costs five minutes of decoding for an answer a shorter slice gives just
     * as well.
     *
     * @return mono samples at [sampleRate], or an empty array when there is no
     *   audio to be had.
     */
    fun decodeMiddle(uri: Uri, durationSeconds: Int, sampleRate: Int): FloatArray {
        require(durationSeconds > 0) { "durationSeconds must be positive, was $durationSeconds" }
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }

        val extractor = MediaExtractor()

        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = audioTrackOf(extractor) ?: return FloatArray(0)
            val format = extractor.getTrackFormat(trackIndex)

            val wanted = durationSeconds * 1_000_000L
            val total = format.longOrNull(MediaFormat.KEY_DURATION) ?: 0L

            // A track shorter than the window is simply taken whole. Refusing to
            // analyse it would be worse than analysing less of it.
            val startUs = if (total <= wanted) 0L else (total - wanted) / 2

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decoded = decode(extractor, format, startUs, startUs + wanted, sampleRate)

            // A codec emits whole buffers, so the last one runs past the point
            // asked for -- by 5% on a two-second request. Trimming makes the
            // result exactly as long as requested, which matters because the
            // caller splits it in half and two windows of drifting length are
            // two windows that are not quite comparable.
            val cap = durationSeconds.toLong() * sampleRate

            return if (decoded.size > cap) decoded.copyOf(cap.toInt()) else decoded
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            // Everything the platform can throw here -- unreadable file, codec
            // the device does not have, a permission that went away -- means the
            // same thing to the caller, so it arrives as one type it can record
            // the track as analysed-and-unknown on.
            throw DecodeException("could not decode $uri", e)
        } finally {
            extractor.release()
        }
    }

    private fun decode(
        extractor: MediaExtractor,
        format: MediaFormat,
        startUs: Long,
        endUs: Long,
        targetRate: Int
    ): FloatArray {
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw DecodeException("track has no mime type")

        val codec = MediaCodec.createDecoderByType(mime)
        val out = FloatArrayBuilder()

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            var sourceRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: targetRate
            var channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var encoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                ?: AudioFormat.ENCODING_PCM_16BIT

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)

                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val read = extractor.readSampleData(buffer, 0)

                        if (read < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    // The real format only arrives once decoding starts, and it
                    // is allowed to disagree with the extractor's. Trusting the
                    // extractor alone is how a stereo file ends up read as mono
                    // at half speed.
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let {
                        sourceRate = it.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sourceRate
                        channels = it.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        encoding = it.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: encoding
                    }

                    else -> if (index >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= startUs) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            out.appendMono(buffer, channels, encoding)
                        }

                        codec.releaseOutputBuffer(index, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            return resample(out.toFloatArray(), sourceRate, targetRate)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun audioTrackOf(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Grows without knowing the final length, since a decoder only reveals how many
 * samples a slice holds by producing them.
 */
private class FloatArrayBuilder {
    private var values = FloatArray(INITIAL)
    private var size = 0

    fun toFloatArray(): FloatArray = values.copyOf(size)

    /**
     * Appends a codec buffer, averaging its channels down to one.
     *
     * Averaging rather than taking the left channel: a beat can be panned, and
     * dropping a channel would quietly throw away half of a wide mix.
     */
    fun appendMono(buffer: ByteBuffer, channels: Int, encoding: Int) {
        val source = buffer.order(ByteOrder.nativeOrder())
        val safeChannels = max(1, channels)

        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = source.asFloatBuffer()

                while (floats.remaining() >= safeChannels) {
                    var sum = 0f
                    repeat(safeChannels) { sum += floats.get() }
                    append(sum / safeChannels)
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                while (source.remaining() >= safeChannels) {
                    var sum = 0f
                    // Unsigned, centred on 128, which is the one PCM format that
                    // is not signed and so cannot share the path below.
                    repeat(safeChannels) { sum += ((source.get().toInt() and 0xff) - 128) / 128f }
                    append(sum / safeChannels)
                }
            }

            // 16-bit is what almost every decoder produces, and the fallback for
            // anything unrecognised, since a wrong guess here is audible as
            // noise rather than silent.
            else -> {
                val shorts = source.asShortBuffer()

                while (shorts.remaining() >= safeChannels) {
                    var sum = 0f
                    repeat(safeChannels) { sum += shorts.get() / 32768f }
                    append(sum / safeChannels)
                }
            }
        }
    }

    private fun append(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)

        values[size++] = value
    }

    private companion object {
        const val INITIAL = 1 shl 16
    }
}

/**
 * Linear resampling, which is cruder than a windowed sinc and entirely adequate
 * here.
 *
 * The estimator works on spectral flux across 1024-sample windows and cares
 * about when energy arrives, not about the fidelity of what arrives. The
 * aliasing linear interpolation introduces sits far above anything a beat
 * lives at.
 */
internal fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
    require(from > 0 && to > 0) { "sample rates must be positive, were $from and $to" }

    if (from == to || samples.isEmpty()) return samples

    val ratio = from.toDouble() / to
    val length = (samples.size / ratio).roundToInt()

    if (length <= 0) return FloatArray(0)

    return FloatArray(length) { i ->
        val at = i * ratio
        val low = at.toInt()
        val high = min(low + 1, samples.lastIndex)
        val fraction = (at - low).toFloat()

        samples[low] * (1f - fraction) + samples[high] * fraction
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
