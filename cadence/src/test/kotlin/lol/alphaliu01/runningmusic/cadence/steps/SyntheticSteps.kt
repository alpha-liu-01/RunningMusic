package lol.alphaliu01.runningmusic.cadence.steps

private const val SECOND_NS = 1_000_000_000L

/**
 * Synthetic step streams, for the failure modes no recording contains.
 *
 * The real fixtures cover a run, a walk, and the platform's delivery behaviour.
 * They do not cover a runner chasing their own music upward, because that only
 * happens once the feature exists, and they do not cover a sensor that dies
 * mid-run, because the hardware declined to. Those have to be constructed.
 *
 * Every stream is expressed as gait first and delivery second, because the two
 * are independent: the same run can arrive one step at a time or in one-second
 * bursts, and the loop is supposed to reach the same answer either way.
 */

/** How the platform hands a step over, given when it happened. */
fun interface Delivery {
    fun arrivalOf(sensorNs: Long): Long
}

/** The idealised case the hardware never actually provides. */
val Immediate = Delivery { it }

/**
 * Steps held back and released on a fixed grid, which is what the spike
 * measured: roughly a second of events at a time, even having asked for zero
 * batching latency, because a wakeup sensor rousing a suspended CPU cannot do
 * better.
 */
fun bursty(periodNs: Long = SECOND_NS, lagNs: Long = SECOND_NS) = Delivery { sensorNs ->
    (sensorNs / periodNs + 1) * periodNs + lagNs
}

/**
 * Builds a gait out of segments.
 *
 * Timestamps start well away from zero so that nothing can accidentally pass by
 * treating a boot-based clock as if it began at the run.
 */
class StepStream(private val startNs: Long = 4_000 * SECOND_NS) {

    private val steps = mutableListOf<Long>()
    private var cursor = startNs

    /** The clock after the last segment, for a replay that outlives the steps. */
    val endNs: Long get() = cursor

    fun steady(spm: Double, seconds: Int) = apply {
        val interval = (60.0 * SECOND_NS / spm).toLong()
        val until = cursor + seconds * SECOND_NS
        while (cursor + interval <= until) {
            cursor += interval
            steps += cursor
        }
        cursor = until
    }

    /** A cadence sliding from one value to another, i.e. a runner speeding up. */
    fun ramp(fromSpm: Double, toSpm: Double, seconds: Int) = apply {
        val began = cursor
        val span = (seconds * SECOND_NS).toDouble()
        val until = cursor + seconds * SECOND_NS
        while (true) {
            val progress = ((cursor - began) / span).coerceIn(0.0, 1.0)
            val spm = fromSpm + (toSpm - fromSpm) * progress
            val interval = (60.0 * SECOND_NS / spm).toLong()
            if (cursor + interval > until) break
            cursor += interval
            steps += cursor
        }
        cursor = until
    }

    /** Time passing with no steps at all: a traffic light, or a dead sensor. */
    fun still(seconds: Int) = apply { cursor += seconds * SECOND_NS }

    fun samples(delivery: Delivery = Immediate): List<StepSample> =
        steps.map { StepSample(sensorTimestampNs = it, receivedElapsedRealtimeNs = delivery.arrivalOf(it)) }

    /**
     * The same gait seen through a cumulative counter read every [periodNs].
     *
     * The counter's resolution is the read period, not the stride, which is the
     * whole reason it survives hardware the detector does not: the reading says
     * how many steps happened, and the timing of the individual steps inside the
     * period never has to be recovered.
     *
     * @param startCount where the counter happens to be. It counts from boot, so
     * a run joins it partway through and the absolute value means nothing.
     */
    fun counterSamples(
        periodNs: Long = SECOND_NS,
        startCount: Float = 12_345f,
        delivery: Delivery = Immediate,
    ): List<CounterSample> {
        val readings = mutableListOf<CounterSample>()
        var at = startNs
        var taken = 0

        while (at <= cursor) {
            while (taken < steps.size && steps[taken] <= at) taken++
            readings += CounterSample(at, delivery.arrivalOf(at), startCount + taken)
            at += periodNs
        }
        return readings
    }
}

/** Loads one of the recordings in `src/test/resources/fixtures`. */
object Fixtures {

    const val SHORT = "run0-nowake-short.txt"
    const val TETHERED_WALK = "run1-nowake-tethered-walk.txt"
    const val WALK = "run2-wake-untethered-walk.txt"
    const val WALK_REPEAT = "run3-wake-untethered-walk.txt"

    /** Five minutes at 177 spm with the CPU suspended for most of it. */
    const val RUN = "run4-wake-untethered-run.txt"

    /** A walk whose detector fired on a 994.3 ms tick instead of on steps. */
    const val TICKING_DETECTOR = "run5-oplus-detector-ticks.txt"

    fun load(name: String): StepRecording {
        val text = Fixtures::class.java.getResourceAsStream("/fixtures/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Fixture '$name' is missing from src/test/resources/fixtures.")
        return parseStepRecording(text)
    }
}
