package lol.alphaliu01.runningmusic.cadence.steps

import kotlin.math.roundToInt

/** One reading of a cumulative step counter, on the sensor's own clock. */
data class CounterReading(val atNs: Long, val steps: Float)

/**
 * The most steps a single counter reading is allowed to contribute.
 *
 * A counter that resets, or a first reading mistaken for a delta, can otherwise
 * inject tens of thousands of steps into the window at once. The cap is far
 * above any honest gap: even a sprint suspended for two minutes is under 400.
 */
private const val MAX_STEPS_PER_READING = 1_000

/**
 * The step timestamps implied by a cumulative counter moving from [previous] to
 * [next], spread evenly across the interval.
 *
 * This exists because a step *detector* cannot be trusted to fire once per step.
 * A OnePlus PKX110 declares its detector `SPECIAL_TRIGGER`, which means one
 * event per step, and then emits on a 994.3 ms hardware tick regardless of how
 * fast its owner is moving: of 129 consecutive gaps in a recorded walk, 103 were
 * the same 994.3 ms to within half a millisecond. Cadence measured from those
 * gaps is the tick, so every walk and every run alike read 60.3 spm.
 *
 * A cumulative count cannot fail that way. Throttling delays it and suspend
 * defers it, but neither loses a step, so the number of steps taken over a
 * window stays right even when the timing of individual events is worthless.
 *
 * Spreading them evenly is a deliberate discard of information the counter never
 * had. The loop takes a median over tens of seconds, so within-tick placement
 * cannot change its answer; what matters is that the count and the elapsed time
 * are both right, and both are.
 *
 * @return one timestamp per step, ascending, ending at [next]. Empty when there
 * is no previous reading to subtract, when the counter did not move, or when it
 * went backwards, which is what a reboot looks like.
 */
fun stepTimestampsFrom(previous: CounterReading?, next: CounterReading): List<Long> {
    if (previous == null) return emptyList()

    val span = next.atNs - previous.atNs
    if (span <= 0) return emptyList()

    val delta = (next.steps - previous.steps).roundToInt()
    if (delta <= 0 || delta > MAX_STEPS_PER_READING) return emptyList()

    return (1..delta).map { previous.atNs + span * it / delta }
}

/**
 * A recording's counter readings as the steps the loop would have been fed.
 *
 * Replay and the phone go through [stepTimestampsFrom] alike, so a fixture
 * answers what the device did rather than what an idealised device would have.
 * Every step a reading produces carries that reading's arrival time, because on
 * the phone that is when all of them land in the queue together.
 */
fun countedSteps(readings: List<CounterSample>): List<StepSample> {
    val ordered = readings.sortedBy { it.sensorTimestampNs }
    var previous: CounterReading? = null

    return ordered.flatMap { sample ->
        val reading = CounterReading(sample.sensorTimestampNs, sample.steps)
        val steps = stepTimestampsFrom(previous, reading)
        previous = reading
        steps.map { StepSample(it, sample.receivedElapsedRealtimeNs) }
    }
}
