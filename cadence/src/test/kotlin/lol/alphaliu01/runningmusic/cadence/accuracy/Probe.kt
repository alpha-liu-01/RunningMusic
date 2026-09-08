package lol.alphaliu01.runningmusic.cadence.accuracy

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** What the estimator made of one track. */
data class Estimate(val bpm: Double, val confidence: Double, val beats: Int)

/** Settings the probe and the report both need to agree on. */
data class ProbeSettings(
    val executable: File,
    val sampleRate: Int = 44100,
    val bufferSize: Int = 1024,
    val hopSize: Int = 512
)

/**
 * Runs `bpm_probe` over a block of samples.
 *
 * The subprocess is what makes this an honest measurement: the numbers come out
 * of the same C the phone runs, compiled from the same sources, rather than out
 * of a second implementation that agrees with it only until it doesn't.
 */
fun runProbe(samples: FloatArray, settings: ProbeSettings): Estimate {
    require(settings.executable.canExecute()) {
        "${settings.executable} is not executable. Run scripts/build-host-aubio.sh first."
    }

    val process = ProcessBuilder(
        settings.executable.absolutePath,
        "--samplerate", settings.sampleRate.toString(),
        "--buffer", settings.bufferSize.toString(),
        "--hop", settings.hopSize.toString()
    ).redirectErrorStream(false).start()

    // The probe reads to end of input before printing anything, so nothing can
    // deadlock on a full pipe here. Draining stderr on a thread anyway, because
    // that stops being true the moment the probe learns to complain.
    val errors = ByteArrayOutputStream()
    val drain = Thread { process.errorStream.use { it.copyTo(errors) } }.apply {
        isDaemon = true
        start()
    }

    val writer = Thread {
        runCatching {
            process.outputStream.buffered(1 shl 16).use { out ->
                val block = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

                for (sample in samples) {
                    if (block.remaining() < Float.SIZE_BYTES) {
                        out.write(block.array(), 0, block.position())
                        block.clear()
                    }
                    block.putFloat(sample)
                }

                out.write(block.array(), 0, block.position())
            }
        }
    }.apply { start() }

    val output = process.inputStream.bufferedReader().use { it.readText() }

    writer.join()
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        throw IOException("bpm_probe did not finish within five minutes")
    }
    drain.join(1_000)

    if (process.exitValue() != 0) {
        throw IOException("bpm_probe exited ${process.exitValue()}: ${errors.toString().trim()}")
    }

    val fields = output.trim().split('\t')
    if (fields.size != 3) throw IOException("bpm_probe printed '${output.trim()}'")

    return Estimate(
        bpm = fields[0].toDouble(),
        confidence = fields[1].toDouble(),
        beats = fields[2].toInt()
    )
}

/**
 * Decodes any audio file ffmpeg understands to mono float samples.
 *
 * ffmpeg rather than a Kotlin decoder because the corpus is whatever music
 * someone happens to own, and writing a second decoder to evaluate a tempo
 * detector would be a strange use of an afternoon.
 */
fun decode(file: File, sampleRate: Int, ffmpeg: String = "ffmpeg"): FloatArray {
    val process = ProcessBuilder(
        ffmpeg,
        "-v", "error",
        "-i", file.absolutePath,
        "-f", "f32le",
        "-acodec", "pcm_f32le",
        "-ac", "1",
        "-ar", sampleRate.toString(),
        "-"
    ).start()

    val errors = ByteArrayOutputStream()
    val drain = Thread { process.errorStream.use { it.copyTo(errors) } }.apply {
        isDaemon = true
        start()
    }

    val bytes = process.inputStream.use { it.readBytes() }

    if (!process.waitFor(10, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        throw IOException("ffmpeg did not finish within ten minutes on ${file.name}")
    }
    drain.join(1_000)

    if (process.exitValue() != 0) {
        throw IOException("ffmpeg exited ${process.exitValue()} on ${file.name}: ${errors.toString().trim()}")
    }

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}

/**
 * Takes [seconds] from the middle of a track, or all of it if it is shorter.
 *
 * The app intends to analyse a window rather than whole files, since a five
 * minute track costs five minutes of decoding for an answer a minute would have
 * given. Taking it from the middle avoids intros, fades and applause. This is
 * the cheapest place to find out what that shortcut costs in accuracy.
 */
fun FloatArray.middle(seconds: Int, sampleRate: Int): FloatArray {
    require(seconds > 0) { "seconds must be positive, was $seconds" }

    val wanted = seconds.toLong() * sampleRate
    if (wanted >= size) return this

    val start = ((size - wanted) / 2).toInt()

    return copyOfRange(start, start + wanted.toInt())
}
