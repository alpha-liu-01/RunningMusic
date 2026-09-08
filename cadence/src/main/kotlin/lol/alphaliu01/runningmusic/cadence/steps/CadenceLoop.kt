package lol.alphaliu01.runningmusic.cadence.steps

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The control loop that turns a stream of steps into a target cadence.
 *
 * Deliberately a pure function of its inputs, and deliberately in this module,
 * where `SensorManager` cannot be reached. The natural instinct is to wire the
 * sensor callback straight into playback, and that produces a system whose only
 * test is to go jogging. Here a recorded run replays through the same
 * [advance] the phone calls, so runaway feedback, traffic lights, walking
 * breaks and a dead sensor are all answerable from a chair.
 *
 * It reads two clocks and never mixes them. Step timestamps are used only for
 * *differences*, so whatever base the sensor counts from cancels out; the
 * caller's clock is used only for elapsed time since we last heard anything. A
 * device whose `SensorEvent.timestamp` runs on a different base than
 * `elapsedRealtimeNanos` therefore changes nothing, which matters because that
 * disagreement is common and would otherwise read as a permanently dead sensor.
 */

private const val SECOND_NS = 1_000_000_000L
private const val MINUTE_NS = 60 * SECOND_NS

/** How the target is allowed to follow the runner. */
enum class TrackingMode {
    /** The slider is the target. No sensor is registered at all. */
    MANUAL,

    /** Sample the natural cadence once, set the target, then hold it. */
    MEASURE_THEN_LOCK,

    /** Measure and lock as above, then keep following, under every guard. */
    CONTINUOUS,
}

/**
 * What the runner appears to be doing.
 *
 * Everything except [RUNNING] holds the target where it is. The alternative is
 * a runner who stops at a traffic light and finds the music collapsing to 0.6x
 * underneath them.
 */
enum class Motion {
    /** No steps yet. A run that has only just begun. */
    STARTING,
    RUNNING,
    WALKING,

    /** Nothing for [LoopConfig.stallNs]. A kerb, a queue, a shoelace. */
    STOPPED,

    /** Nothing for [LoopConfig.sensorLostNs]. Treated as a hardware failure. */
    SENSOR_LOST,
}

/**
 * The tuning constants, all in one place so a fixture can sweep them.
 *
 * @property windowNs how much history the measurement median covers. The
 * Overview asks for 30 to 60 seconds; a single stride is noise at any useful
 * resolution, and anything shorter turns a stumble into a tempo change.
 * @property measureWindowNs how much *running* has to accumulate before the
 * first lock. Counted as accumulated running time rather than wall time, so a
 * traffic light in the first minute extends the measurement instead of
 * restarting or, worse, poisoning it.
 * @property minIntervals how many gaps the measurement median needs before it
 * will report at all. At 170 spm this is about eleven seconds.
 * @property classifyIntervals the much shorter median that decides running from
 * walking. Separate from the measurement median because classification has to
 * react in seconds while measurement has to be stable over a minute.
 * @property deadbandSpm a measurement this close to the target does nothing.
 * @property maxDriftPerMinuteSpm the fastest the target may move once locked.
 * @property maxDriftFromLockSpm how far the target may ever get from the value
 * measured at the lock. This, not the rate limit, is what actually stops
 * runaway feedback: a rate limit alone only makes a runaway take longer.
 * @property stallNs silence this long means stopped. Must comfortably exceed
 * the platform's delivery lag, which the sensor spike measured at up to two
 * seconds on a wakeup detector, or every batch boundary reads as a stop.
 * @property walkingFloorSpm below this the runner is walking, not running.
 * Defaults to the bottom of [cadenceRange] rather than to a separate figure for
 * walking, because the two questions are the same one: a cadence the app would
 * never be allowed to set as a target is not a cadence worth measuring. Pitched
 * lower it admits a brisk walk, which the recorded fixtures show reaching 137
 * spm and holding it long enough to look like a slow jog.
 */
data class LoopConfig(
    val windowNs: Long = 45 * SECOND_NS,
    val measureWindowNs: Long = 60 * SECOND_NS,
    val minIntervals: Int = 30,
    val classifyIntervals: Int = 8,
    val deadbandSpm: Double = 3.0,
    val maxDriftPerMinuteSpm: Double = 4.0,
    val maxDriftFromLockSpm: Double = 10.0,
    val stallNs: Long = 8 * SECOND_NS,
    val sensorLostNs: Long = 45 * SECOND_NS,
    val cadenceRange: IntRange = 140..200,
    val walkingFloorSpm: Double = cadenceRange.first.toDouble(),
) {
    init {
        require(windowNs > 0) { "windowNs must be positive, was $windowNs" }
        require(measureWindowNs > 0) { "measureWindowNs must be positive, was $measureWindowNs" }
        require(minIntervals >= 1) { "minIntervals must be at least 1, was $minIntervals" }
        require(classifyIntervals >= 1) {
            "classifyIntervals must be at least 1, was $classifyIntervals"
        }
        require(deadbandSpm >= 0.0) { "deadbandSpm must not be negative, was $deadbandSpm" }
        require(maxDriftPerMinuteSpm > 0.0) {
            "maxDriftPerMinuteSpm must be positive, was $maxDriftPerMinuteSpm"
        }
        require(maxDriftFromLockSpm >= 0.0) {
            "maxDriftFromLockSpm must not be negative, was $maxDriftFromLockSpm"
        }
        require(stallNs > 0) { "stallNs must be positive, was $stallNs" }
        require(sensorLostNs >= stallNs) {
            "sensorLostNs ($sensorLostNs) must not precede stallNs ($stallNs)"
        }
        require(walkingFloorSpm > 0.0) {
            "walkingFloorSpm must be positive, was $walkingFloorSpm"
        }
        require(!cadenceRange.isEmpty()) { "cadenceRange must not be empty" }
    }
}

/**
 * One immutable snapshot of the loop.
 *
 * @property target the cadence playback should currently be matched to. Always
 * meaningful: it starts at whatever the runner set on the slider, so the music
 * has something to play at during the opening measurement.
 * @property measuredSpm what the runner is doing right now, or null when that
 * cannot honestly be answered. Not a fallback to the last known value; a stale
 * reading presented as a live one is how a stopped runner looks like a slow one.
 * @property locked whether the opening measurement has been taken.
 * @property baseline the target at the moment of the lock, and the centre of
 * the drift cap.
 *
 * The remaining properties are bookkeeping, public only because the whole point
 * of this type is that a test can inspect and reconstruct any moment of a run.
 */
data class CadenceLoop(
    val target: Int,
    val mode: TrackingMode = TrackingMode.MEASURE_THEN_LOCK,
    val config: LoopConfig = LoopConfig(),
    val motion: Motion = Motion.STARTING,
    val measuredSpm: Double? = null,
    val locked: Boolean = false,
    val baseline: Int? = null,
    val window: List<Long> = emptyList(),
    val runningNs: Long = 0L,
    val runningSinceStepNs: Long? = null,
    val startedNs: Long? = null,
    val lastStepArrivalNs: Long? = null,
    val lastAdvanceNs: Long? = null,
    val lastMoveNs: Long? = null,
) {

    /**
     * Folds one moment of a run into the next.
     *
     * @param nowNs the caller's monotonic clock, which must advance between
     * calls but need share no base with the step timestamps.
     * @param steps sensor timestamps delivered since the last call, if any. A
     * batch of several arriving together is the normal case rather than an
     * exception: the platform was measured handing over roughly a second of
     * steps at a time even when asked for zero latency.
     *
     * Must be called on a timer as well as on delivery. A loop told only about
     * steps cannot notice their absence, and noticing their absence is most of
     * what this does.
     */
    fun advance(nowNs: Long, steps: List<Long> = emptyList()): CadenceLoop {
        val elapsed = lastAdvanceNs?.let { (nowNs - it).coerceAtLeast(0L) } ?: 0L

        // Credit for the interval just ended, capped at the stall threshold. An
        // unusually long gap between calls means the CPU was suspended, and a
        // suspended CPU cannot vouch for what the runner was doing; crediting it
        // in full would let one long sleep satisfy the whole measurement.
        val credited = if (motion == Motion.RUNNING) minOf(elapsed, config.stallNs) else 0L

        val newest = window.lastOrNull()
        val fresh = steps.sorted().filter { newest == null || it > newest }
        val merged = if (fresh.isEmpty()) window else window + fresh

        // Trimmed against the newest step rather than against nowNs, so the two
        // clocks never meet.
        val horizon = (merged.lastOrNull() ?: 0L) - config.windowNs
        val trimmed = if (merged.isEmpty()) merged else merged.filter { it >= horizon }

        val arrival = if (fresh.isNotEmpty()) nowNs else lastStepArrivalNs
        val started = startedNs ?: nowNs
        val silence = (nowNs - (arrival ?: started)).coerceAtLeast(0L)

        val classified = trimmed.medianSpm(
            atLeast = config.classifyIntervals,
            limit = config.classifyIntervals,
        )

        val motion = when {
            silence >= config.sensorLostNs -> Motion.SENSOR_LOST
            arrival == null -> Motion.STARTING
            silence >= config.stallNs -> Motion.STOPPED
            classified != null && classified < config.walkingFloorSpm -> Motion.WALKING
            else -> Motion.RUNNING
        }

        // The measurement only ever sees one unbroken stretch of running. Left
        // to span a break it would average a walk into a run, which is the same
        // mistake as collapsing the target at a traffic light, just slower.
        val runningSince = when {
            motion != Motion.RUNNING -> null
            this.motion == Motion.RUNNING -> runningSinceStepNs ?: trimmed.firstOrNull()
            else -> trimmed.lastOrNull()
        }

        val measured = if (runningSince == null) {
            null
        } else {
            trimmed.filter { it >= runningSince }.medianSpm(atLeast = config.minIntervals)
        }

        val accumulated = runningNs + credited
        val decided = decide(nowNs, motion, measured, accumulated)

        return copy(
            motion = motion,
            measuredSpm = measured,
            locked = decided.locked,
            baseline = decided.baseline,
            target = decided.target,
            window = trimmed,
            runningNs = accumulated,
            runningSinceStepNs = runningSince,
            startedNs = started,
            lastStepArrivalNs = arrival,
            lastAdvanceNs = nowNs,
            lastMoveNs = decided.lastMoveNs,
        )
    }

    private fun decide(
        nowNs: Long,
        motion: Motion,
        measured: Double?,
        accumulated: Long,
    ): Decision {
        val held = Decision(target, baseline, locked, lastMoveNs)
        if (mode == TrackingMode.MANUAL) return held
        if (motion != Motion.RUNNING || measured == null) return held

        val low = config.cadenceRange.first.toDouble()
        val high = config.cadenceRange.last.toDouble()

        // Rejected rather than clamped. Clamping treats a reading the app could
        // never act on as if it were the nearest one it could, so a stride the
        // sensor doubled into 300 spm would set the target to the top of the
        // range and hold it there as though it had been measured.
        if (measured !in low..high) return held
        val candidate = measured

        if (!locked) {
            // The opening measurement is the one move that is neither rate
            // limited nor deadbanded: it is not following anything, it is
            // establishing what to follow.
            if (accumulated < config.measureWindowNs) return held
            val settled = candidate.roundToInt()
            return Decision(settled, settled, true, nowNs)
        }

        if (mode != TrackingMode.CONTINUOUS) return held
        if (abs(candidate - target) < config.deadbandSpm) return held

        val since = lastMoveNs?.let { (nowNs - it).coerceAtLeast(0L) } ?: 0L
        val allowance = config.maxDriftPerMinuteSpm * since / MINUTE_NS
        val centre = (baseline ?: target).toDouble()

        val moved = (target + (candidate - target).coerceIn(-allowance, allowance))
            .coerceIn(centre - config.maxDriftFromLockSpm, centre + config.maxDriftFromLockSpm)
            .coerceIn(low, high)
            .roundToInt()

        // An allowance too small to survive rounding leaves lastMoveNs alone, so
        // the next call has a longer interval to spend and the target creeps
        // rather than freezing.
        return if (moved == target) held else Decision(moved, baseline, true, nowNs)
    }

    private data class Decision(
        val target: Int,
        val baseline: Int?,
        val locked: Boolean,
        val lastMoveNs: Long?,
    )
}

/**
 * The median cadence of the most recent [limit] gaps, or null if there are
 * fewer than [atLeast] of them.
 *
 * A median rather than a mean throughout. Step detectors miss steps and
 * occasionally emit two for one, and either produces a gap wrong by a factor of
 * two; a mean carries that into the target, a median discards it.
 */
private fun List<Long>.medianSpm(atLeast: Int, limit: Int = Int.MAX_VALUE): Double? {
    if (size < 2) return null

    val intervals = zipWithNext { a, b -> b - a }
    val considered = if (intervals.size > limit) intervals.takeLast(limit) else intervals
    if (considered.size < atLeast) return null

    val sorted = considered.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 1) {
        sorted[middle].toDouble()
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    return if (median <= 0.0) null else 60_000_000_000.0 / median
}

/**
 * Runs a recording, or a synthesised one, through [CadenceLoop.advance] exactly
 * as the phone would.
 *
 * Steps are handed over when their arrival time says they arrived, not when
 * they happened, so a fixture reproduces the platform's batched delivery rather
 * than an idealised stream the loop will never actually see.
 *
 * @param tickNs the timer interval the caller would use on device. Stall
 * detection is the only thing that depends on it.
 * @param onTick called after every advance, for a replay that wants a timeline
 * rather than only a verdict.
 */
fun CadenceLoop.replay(
    samples: List<StepSample>,
    tickNs: Long = 5 * SECOND_NS,
    startNs: Long? = null,
    endNs: Long? = null,
    onTick: (nowNs: Long, loop: CadenceLoop) -> Unit = { _, _ -> },
): CadenceLoop {
    require(tickNs > 0) { "tickNs must be positive, was $tickNs" }

    val ordered = samples.sortedBy { it.receivedElapsedRealtimeNs }
    val start = startNs
        ?: ordered.firstOrNull()?.receivedElapsedRealtimeNs
        ?: return this
    val end = endNs ?: ordered.lastOrNull()?.receivedElapsedRealtimeNs ?: start

    var loop = this
    var pending = 0
    var now = start

    while (true) {
        val due = mutableListOf<Long>()
        while (pending < ordered.size && ordered[pending].receivedElapsedRealtimeNs <= now) {
            due += ordered[pending].sensorTimestampNs
            pending++
        }

        loop = loop.advance(now, due)
        onTick(now, loop)

        if (now >= end && pending >= ordered.size) return loop
        now += tickNs
    }
}
