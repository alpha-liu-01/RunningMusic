package lol.alphaliu01.runningmusic.cadence.steps

/**
 * The on-disk format for a recorded run.
 *
 * Deliberately pure Kotlin and deliberately shared: the phone writes it with
 * [StepRecordingWriter] and tests read it back with [parseStepRecording], so a
 * single real run becomes a fixture the control loop can be developed against
 * without going jogging again. Splitting the writer and the reader across the
 * Android and JVM sides would let them drift, which is exactly how a fixture
 * format stops round-tripping.
 *
 * It is line-oriented rather than JSON so that a recording whose process was
 * killed mid-write still parses up to the last complete line.
 */

const val STEP_RECORDING_FORMAT = "runningmusic-step-recording"

/**
 * 2 added the uptime clock to `S` lines. 3 added the detector's reported step
 * count to them and `C` lines for the step counter. Older files still parse,
 * and older readers skip what they do not know.
 */
const val STEP_RECORDING_VERSION = 3

class StepRecordingFormatException(message: String) : IllegalArgumentException(message)

data class RecordingHeader(
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val sensorName: String,
    val sensorVendor: String,
    val isWakeUp: Boolean,
    val fifoMaxEvents: Int,
    val fgsType: String,
    val batchLatencyUs: Int,
    val startWallClockMs: Long,
    val startElapsedRealtimeNs: Long,
)

/**
 * One step, timed by three different clocks.
 *
 * @property sensorTimestampNs the sensor's own boot-based clock, i.e. when the
 * step happened.
 * @property receivedElapsedRealtimeNs `SystemClock.elapsedRealtimeNanos()` at
 * the moment the app was handed the event, i.e. when we heard about it.
 * @property receivedUptimeNs `SystemClock.uptimeNanos()` at the same moment.
 * Null in version 1 recordings, which predate it.
 *
 * The first two answer "did we get it, and how late". The third answers the
 * question those two cannot: **was the CPU actually asleep?** `elapsedRealtime`
 * runs during suspend and `uptime` freezes, so the amount by which they diverge
 * between two steps is, exactly and by construction, time spent suspended.
 *
 * Without it the evidence is only circumstantial. A steady one-second delivery
 * cadence is equally consistent with a CPU that never slept and with a wakeup
 * sensor rousing a sleeping CPU once a second — opposite conclusions from
 * identical data. This turns that inference into a measurement.
 */
data class StepSample(
    val sensorTimestampNs: Long,
    val receivedElapsedRealtimeNs: Long,
    val receivedUptimeNs: Long? = null,
    val reportedSteps: Float? = null,
) {
    /** How long the event sat in a buffer before we saw it. */
    val deliveryLagNs: Long get() = receivedElapsedRealtimeNs - sensorTimestampNs
}

/**
 * One reading of `TYPE_STEP_COUNTER`, whose value is cumulative since boot.
 *
 * Recorded alongside the detector because the two disagree on hardware that
 * matters. A OnePlus PKX110 was found emitting detector events on a 994.3 ms
 * hardware tick no matter how fast its owner was moving, so the gap between two
 * events measured the tick and every cadence came out as 60 spm. A cumulative
 * count cannot fail that way: throttling delays it but never loses steps, so
 * the number taken over a window is right even when the timing of the
 * individual events is not.
 *
 * @property steps the counter's own value, a float by the platform's choice
 * rather than ours.
 */
data class CounterSample(
    val sensorTimestampNs: Long,
    val receivedElapsedRealtimeNs: Long,
    val steps: Float,
)

data class AccelSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

data class StepRecording(
    val header: RecordingHeader,
    val steps: List<StepSample>,
    val accel: List<AccelSample>,
    val counter: List<CounterSample> = emptyList(),
) {
    /** Gaps between consecutive steps, by the sensor's clock. */
    fun stepIntervalsNs(): List<Long> =
        steps.zipWithNext { a, b -> b.sensorTimestampNs - a.sensorTimestampNs }

    /** False for version 1 recordings, where suspend cannot be measured. */
    val hasUptimeClock: Boolean get() = steps.any { it.receivedUptimeNs != null }

    private val timedSteps get() = steps.filter { it.receivedUptimeNs != null }

    /**
     * How long the CPU spent suspended between the first and last step.
     *
     * `elapsedRealtime` advances during suspend and `uptime` does not, so their
     * divergence over an interval *is* the suspended time. Null when the
     * recording predates the uptime clock and the question cannot be answered.
     */
    fun suspendedNs(): Long? {
        val timed = timedSteps
        if (timed.isEmpty()) return null
        if (timed.size < 2) return 0

        val elapsed = timed.last().receivedElapsedRealtimeNs - timed.first().receivedElapsedRealtimeNs
        val awake = timed.last().receivedUptimeNs!! - timed.first().receivedUptimeNs!!
        return (elapsed - awake).coerceAtLeast(0)
    }

    /** Wall time covered by the same interval [suspendedNs] measures. */
    fun observedSpanNs(): Long {
        val timed = timedSteps.ifEmpty { steps }
        if (timed.size < 2) return 0
        return timed.last().receivedElapsedRealtimeNs - timed.first().receivedElapsedRealtimeNs
    }
}

/**
 * Steps per minute implied by the gap between two consecutive steps.
 *
 * Instantaneous and unsmoothed, which is all a display or a fixture summary
 * needs. Smoothing belongs to the control loop, which this spike does not build.
 */
fun cadenceSpm(intervalNs: Long): Double =
    if (intervalNs <= 0) 0.0 else 60_000_000_000.0 / intervalNs

class StepRecordingWriter(private val out: Appendable) {

    fun writeHeader(header: RecordingHeader) {
        comment("format", STEP_RECORDING_FORMAT)
        comment("version", STEP_RECORDING_VERSION)
        comment("device", header.device)
        comment("androidRelease", header.androidRelease)
        comment("sdkInt", header.sdkInt)
        comment("sensorName", header.sensorName)
        comment("sensorVendor", header.sensorVendor)
        comment("isWakeUp", header.isWakeUp)
        comment("fifoMaxEvents", header.fifoMaxEvents)
        comment("fgsType", header.fgsType)
        comment("batchLatencyUs", header.batchLatencyUs)
        comment("startWallClockMs", header.startWallClockMs)
        comment("startElapsedRealtimeNs", header.startElapsedRealtimeNs)
    }

    fun writeStep(sample: StepSample) {
        out.append("S ")
            .append(sample.sensorTimestampNs.toString())
            .append(' ')
            .append(sample.receivedElapsedRealtimeNs.toString())
        // Omitted rather than zero-filled when absent, so the field's presence
        // is itself the signal and no value has to be reserved as a sentinel.
        sample.receivedUptimeNs?.let { out.append(' ').append(it.toString()) }
        // Only meaningful once the uptime clock is there to hold its place, and
        // it always is: nothing writes version 3 without writing version 2.
        sample.reportedSteps?.let { out.append(' ').append(it.toString()) }
        out.append('\n')
    }

    fun writeCounter(sample: CounterSample) {
        out.append("C ")
            .append(sample.sensorTimestampNs.toString())
            .append(' ')
            .append(sample.receivedElapsedRealtimeNs.toString())
            .append(' ')
            .append(sample.steps.toString())
            .append('\n')
    }

    fun writeAccel(sample: AccelSample) {
        out.append("A ")
            .append(sample.timestampNs.toString())
            .append(' ')
            .append(sample.x.toString())
            .append(' ')
            .append(sample.y.toString())
            .append(' ')
            .append(sample.z.toString())
            .append('\n')
    }

    private fun comment(key: String, value: Any) {
        // Values are single-line by construction; a sensor name containing a
        // newline would otherwise forge a header line.
        val flattened = value.toString().replace('\n', ' ').replace('\r', ' ')
        out.append("# ").append(key).append('=').append(flattened).append('\n')
    }
}

fun parseStepRecording(text: String): StepRecording =
    parseStepRecording(text.lineSequence())

/**
 * Reads a recording back.
 *
 * Tolerant of everything a truncated or forward-versioned file can throw at it:
 * blank lines, unknown record types, and a final line cut off mid-write are all
 * skipped. A missing or malformed header is not tolerated, because that means
 * the file is not a recording at all.
 */
fun parseStepRecording(lines: Sequence<String>): StepRecording {
    val header = mutableMapOf<String, String>()
    val steps = mutableListOf<StepSample>()
    val accel = mutableListOf<AccelSample>()
    val counter = mutableListOf<CounterSample>()

    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue

        if (line.startsWith("#")) {
            val body = line.removePrefix("#").trim()
            val key = body.substringBefore('=', missingDelimiterValue = "")
            if (key.isNotEmpty()) header[key] = body.substringAfter('=')
            continue
        }

        val fields = line.split(' ')
        when (fields[0]) {
            "S" -> {
                if (fields.size < 3) continue
                val sensorNs = fields[1].toLongOrNull() ?: continue
                val receivedNs = fields[2].toLongOrNull() ?: continue
                // Absent in version 1 recordings, which stay readable.
                steps += StepSample(
                    sensorTimestampNs = sensorNs,
                    receivedElapsedRealtimeNs = receivedNs,
                    receivedUptimeNs = fields.getOrNull(3)?.toLongOrNull(),
                    reportedSteps = fields.getOrNull(4)?.toFloatOrNull(),
                )
            }

            "C" -> {
                if (fields.size < 4) continue
                val sensorNs = fields[1].toLongOrNull() ?: continue
                val receivedNs = fields[2].toLongOrNull() ?: continue
                val value = fields[3].toFloatOrNull() ?: continue
                counter += CounterSample(sensorNs, receivedNs, value)
            }

            "A" -> {
                if (fields.size < 5) continue
                val ts = fields[1].toLongOrNull() ?: continue
                val x = fields[2].toFloatOrNull() ?: continue
                val y = fields[3].toFloatOrNull() ?: continue
                val z = fields[4].toFloatOrNull() ?: continue
                accel += AccelSample(ts, x, y, z)
            }
            // Anything else is from a future version. Ignore it.
        }
    }

    return StepRecording(header.toRecordingHeader(), steps, accel, counter)
}

private fun Map<String, String>.toRecordingHeader(): RecordingHeader {
    val format = this["format"]
    if (format != STEP_RECORDING_FORMAT) {
        throw StepRecordingFormatException(
            "Not a $STEP_RECORDING_FORMAT file (format=${format ?: "absent"})."
        )
    }

    return RecordingHeader(
        device = required("device"),
        androidRelease = required("androidRelease"),
        sdkInt = requiredInt("sdkInt"),
        sensorName = required("sensorName"),
        sensorVendor = required("sensorVendor"),
        isWakeUp = required("isWakeUp").toBooleanStrictOrNull()
            ?: throw StepRecordingFormatException("Header 'isWakeUp' is not a boolean."),
        fifoMaxEvents = requiredInt("fifoMaxEvents"),
        fgsType = required("fgsType"),
        batchLatencyUs = requiredInt("batchLatencyUs"),
        startWallClockMs = requiredLong("startWallClockMs"),
        startElapsedRealtimeNs = requiredLong("startElapsedRealtimeNs"),
    )
}

private fun Map<String, String>.required(key: String): String =
    this[key] ?: throw StepRecordingFormatException("Header '$key' is missing.")

private fun Map<String, String>.requiredInt(key: String): Int =
    required(key).toIntOrNull()
        ?: throw StepRecordingFormatException("Header '$key' is not an integer.")

private fun Map<String, String>.requiredLong(key: String): Long =
    required(key).toLongOrNull()
        ?: throw StepRecordingFormatException("Header '$key' is not an integer.")
