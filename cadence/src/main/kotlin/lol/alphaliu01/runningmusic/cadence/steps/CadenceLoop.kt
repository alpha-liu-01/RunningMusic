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

    /**
     * Measure and lock as above, then keep following, under every guard.
     *
     * The guards bound how fast the target may move, not how far it may
     * eventually get: a pace held long enough moves the anchor. Nothing may add
     * a constant offset to the target in this mode, because the runner hears the
     * target and synchronises to it, so a bias is integrated rather than
     * absorbed and the two walk each other off the end of the range.
     */
    CONTINUOUS,
}

/**
 * Where the steps came from, which decides how the window is summarised.
 *
 * Not a detail: the two sources fail in opposite directions, so an estimator
 * right for one is wrong for the other by several spm.
 */
enum class StepSource {
    /**
     * One event per step, summarised by the **median** gap.
     *
     * Detectors miss steps and occasionally emit two for one, and either makes a
     * gap wrong by a factor of two. A mean carries that into the target; a
     * median discards it. Recorded run 4 differs by 10 spm between the two, and
     * the median is the one that matches the legs.
     */
    TIMED,

    /**
     * A cumulative count, summarised by the **mean** rate.
     *
     * A counter loses no steps, so there are no outliers to reject, and the
     * median actively lies here. Counts arrive quantised to the read period — a
     * 112 spm walk read every second is a run of 2-step readings sprinkled with
     * 1-step ones — so most gaps are the period divided by the larger count and
     * the median picks that, reading 120. The mean divides the steps by the time
     * they took, which is the one thing a counter knows exactly.
     */
    COUNTED,
}

/**
 * What the runner appears to be doing.
 *
 * Everything except [MOVING] holds the target where it is. The alternative is
 * a runner who stops at a traffic light and finds the music collapsing to 0.6x
 * underneath them.
 */
enum class Motion {
    /** No steps yet. A run that has only just begun. */
    STARTING,

    /**
     * Travelling at a cadence worth matching, whether that is a run or a walk.
     *
     * Deliberately not split into running and walking. The loop's only real
     * question is whether the steps arriving are steady locomotion, and a walk
     * at 110 spm answers that exactly as well as a run at 180; treating the two
     * differently is what made the target freeze for anyone who was not fast.
     */
    MOVING,

    /**
     * Stepping, but below [LoopConfig.movementFloorSpm]: pacing a kitchen,
     * shuffling at a crossing. Real steps, not a cadence anyone wants music set
     * to, so the target holds.
     */
    IDLING,

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
 * @property measureWindowNs how much *movement* has to accumulate before the
 * first lock. Counted as accumulated moving time rather than wall time, so a
 * traffic light in the first minute extends the measurement instead of
 * restarting or, worse, poisoning it.
 * @property minIntervals how many gaps the measurement median needs before it
 * will report at all. At 170 spm this is about eleven seconds.
 * @property classifyIntervals the much shorter median that decides movement
 * from idling. Separate from the measurement median because classification has
 * to react in seconds while measurement has to be stable over a minute.
 * @property deadbandSpm a measurement this close to the target does nothing.
 * @property maxDriftPerMinuteSpm the fastest the target may move once locked.
 * @property maxDriftFromLockSpm how far the target may ever get from the value
 * measured at the lock. This, not the rate limit, is what actually stops
 * runaway feedback: a rate limit alone only makes a runaway take longer.
 * @property stallNs silence this long means stopped. Must comfortably exceed
 * the platform's delivery lag, which the sensor spike measured at up to two
 * seconds on a wakeup detector, or every batch boundary reads as a stop.
 * @property movementFloorSpm below this the steps are not locomotion worth
 * matching. Defaults to the bottom of [cadenceRange], because the two questions
 * are the same one: a cadence the app would never be allowed to set as a target
 * is not a cadence worth measuring.
 *
 * This floor used to sit at 140, on the theory that a brisk walk should not be
 * mistaken for a slow run. It was the wrong theory twice over. Anyone moving
 * below it had the target frozen for the whole run, and worse, a cadence
 * hovering near it flipped in and out of [Motion.MOVING] every few seconds,
 * which reset the measurement window each time and meant [measuredSpm] was
 * never computed even once. All four recorded walk fixtures fail to produce a
 * single measurement at 140 and lock cleanly at 60.
 */
data class LoopConfig(
    val windowNs: Long = 45 * SECOND_NS,
    val measureWindowNs: Long = 60 * SECOND_NS,
    val minIntervals: Int = 30,
    val classifyIntervals: Int = 8,
    val deadbandSpm: Double = 3.0,
    val maxDriftPerMinuteSpm: Double = 4.0,
    val maxDriftFromLockSpm: Double = 10.0,
    val reanchorNs: Long = 60 * SECOND_NS,
    val stallNs: Long = 8 * SECOND_NS,
    val sensorLostNs: Long = 45 * SECOND_NS,
    val cadenceRange: IntRange = 60..200,
    val movementFloorSpm: Double = cadenceRange.first.toDouble(),
    val source: StepSource = StepSource.TIMED,
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
        require(reanchorNs > 0) { "reanchorNs must be positive, was $reanchorNs" }
        require(stallNs > 0) { "stallNs must be positive, was $stallNs" }
        require(sensorLostNs >= stallNs) {
            "sensorLostNs ($sensorLostNs) must not precede stallNs ($stallNs)"
        }
        require(movementFloorSpm > 0.0) {
            "movementFloorSpm must be positive, was $movementFloorSpm"
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
 * @property baseline the centre of the drift cap. Set at the lock, and moved
 * again whenever the runner holds a pace beyond the cap for long enough that
 * refusing to follow it stops being caution and starts being wrong.
 * @property awayFromBaselineNs how long the runner has been outside the cap
 * without coming back, which is the only thing allowed to move [baseline].
 * @property lastStepAwakeNs the awake clock at the last delivery, against which
 * silence is judged. Its wall-clock twin [lastStepArrivalNs] is kept because it
 * answers a different question: whether anything has ever arrived at all.
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
    val awayFromBaselineNs: Long = 0L,
    val movingNs: Long = 0L,
    val movingSinceStepNs: Long? = null,
    val startedNs: Long? = null,
    val startedAwakeNs: Long? = null,
    val lastStepArrivalNs: Long? = null,
    val lastStepAwakeNs: Long? = null,
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
     * @param awakeNs a clock that stops while the CPU is suspended, against
     * which silence is judged. Defaults to [nowNs], which says the CPU never
     * slept and is what a caller with only one clock is asserting anyway.
     *
     * Must be called on a timer as well as on delivery. A loop told only about
     * steps cannot notice their absence, and noticing their absence is most of
     * what this does.
     */
    fun advance(
        nowNs: Long,
        steps: List<Long> = emptyList(),
        awakeNs: Long = nowNs,
    ): CadenceLoop {
        val elapsed = lastAdvanceNs?.let { (nowNs - it).coerceAtLeast(0L) } ?: 0L

        val newest = window.lastOrNull()
        val fresh = steps.sorted().filter { newest == null || it > newest }
        val merged = if (fresh.isEmpty()) window else window + fresh

        // Credit for the interval just ended. One that delivered steps is
        // vouched for by them: a cumulative counter keeps counting through
        // suspend, so steps covering a gap are evidence the runner ran through
        // it, and refusing to credit that would make a phone sleeping in thirty
        // second stretches take minutes to satisfy a one minute measurement. An
        // interval that delivered nothing is capped, because a long silence is
        // exactly as consistent with a runner who stopped.
        //
        // Bounded either way, because an interval cannot honestly be worth more
        // than the loop's own patience for one; past [LoopConfig.sensorLostNs] we
        // would have called the sensor dead had we been awake to look.
        val credited = when {
            motion != Motion.MOVING -> 0L
            fresh.isNotEmpty() -> minOf(elapsed, config.sensorLostNs)
            else -> minOf(elapsed, config.stallNs)
        }

        // Trimmed against the newest step rather than against nowNs, so the two
        // clocks never meet.
        val horizon = (merged.lastOrNull() ?: 0L) - config.windowNs
        val trimmed = if (merged.isEmpty()) merged else merged.filter { it >= horizon }

        val arrival = if (fresh.isNotEmpty()) nowNs else lastStepArrivalNs
        val started = startedNs ?: nowNs

        // Silence is judged on the awake clock, and that is the whole reason
        // there is one. Step sensors with no wakeup variant stop delivering the
        // moment the CPU suspends, and on a clock that runs through suspend that
        // is indistinguishable from a runner standing at a crossing: both are a
        // gap of the same length. The two clocks diverge by exactly the time
        // spent suspended, so "how long were we awake and heard nothing" is the
        // question [LoopConfig.stallNs] was always trying to ask.
        val heardAt = if (fresh.isNotEmpty()) awakeNs else lastStepAwakeNs
        val startedAwake = startedAwakeNs ?: awakeNs
        val silence = (awakeNs - (heardAt ?: startedAwake)).coerceAtLeast(0L)

        val classified = trimmed.windowSpm(
            atLeast = config.classifyIntervals,
            source = config.source,
            limit = config.classifyIntervals,
        )

        val motion = when {
            silence >= config.sensorLostNs -> Motion.SENSOR_LOST
            arrival == null -> Motion.STARTING
            silence >= config.stallNs -> Motion.STOPPED
            classified != null && classified < config.movementFloorSpm -> Motion.IDLING
            else -> Motion.MOVING
        }

        // The measurement only ever sees one unbroken stretch of movement. Left
        // to span a break it would average a stop into a pace, which is the same
        // mistake as collapsing the target at a traffic light, just slower.
        val movingSince = when {
            motion != Motion.MOVING -> null
            this.motion == Motion.MOVING -> movingSinceStepNs ?: trimmed.firstOrNull()
            else -> trimmed.lastOrNull()
        }

        val measured = if (movingSince == null) {
            null
        } else {
            trimmed.filter { it >= movingSince }
                .windowSpm(atLeast = config.minIntervals, source = config.source)
        }

        // A cap anchored to the opening measurement cannot follow a runner who
        // drops into a walk, or who spends ten minutes climbing: the target
        // holds near a pace they have plainly left, and the further they get
        // from it the more the music insists on the pace they started at.
        // Sustained time spent beyond the cap moves the anchor, so the cap keeps
        // bounding how *fast* the target may move without also deciding, from
        // the opening minute, how fast the rest of the run is allowed to be.
        // A brief excursion cannot do it: the clock resets the moment the runner
        // comes back inside, so only a pace held for [LoopConfig.reanchorNs]
        // counts, and the rate limit still governs the way over.
        // Re-anchored onto the target rather than onto the measurement. Moving it
        // to the measurement would recentre the cap somewhere the target is not,
        // and the cap is applied by clamping, so the target would be yanked a
        // full cap's width in one tick — the rate limit bypassed by the very
        // mechanism meant to bound it. Recentring on the target moves nothing by
        // itself; it only lets the target carry on creeping, and strays again a
        // minute later if the runner is still out there. The result ratchets
        // toward a sustained new pace at the rate limit and no faster.
        val strayed = locked &&
            baseline != null &&
            measured != null &&
            abs(measured - baseline) > config.maxDriftFromLockSpm
        val strayedFor = if (strayed) awayFromBaselineNs + credited else 0L
        val anchor = if (strayedFor >= config.reanchorNs) target else baseline

        val accumulated = movingNs + credited
        val decided = decide(nowNs, motion, measured, accumulated, anchor)

        return copy(
            motion = motion,
            measuredSpm = measured,
            locked = decided.locked,
            baseline = decided.baseline,
            target = decided.target,
            window = trimmed,
            awayFromBaselineNs = if (anchor != baseline) 0L else strayedFor,
            movingNs = accumulated,
            movingSinceStepNs = movingSince,
            startedNs = started,
            startedAwakeNs = startedAwake,
            lastStepArrivalNs = arrival,
            lastStepAwakeNs = heardAt,
            lastAdvanceNs = nowNs,
            lastMoveNs = decided.lastMoveNs,
        )
    }

    private fun decide(
        nowNs: Long,
        motion: Motion,
        measured: Double?,
        accumulated: Long,
        anchor: Int?,
    ): Decision {
        // Carries [anchor] rather than [baseline], so a re-anchor sticks even on
        // a tick where the target itself is held.
        val held = Decision(target, anchor, locked, lastMoveNs)
        if (mode == TrackingMode.MANUAL) return held
        if (motion != Motion.MOVING || measured == null) return held

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
        val centre = (anchor ?: target).toDouble()

        val moved = (target + (candidate - target).coerceIn(-allowance, allowance))
            .coerceIn(centre - config.maxDriftFromLockSpm, centre + config.maxDriftFromLockSpm)
            .coerceIn(low, high)
            .roundToInt()

        // An allowance too small to survive rounding leaves lastMoveNs alone, so
        // the next call has a longer interval to spend and the target creeps
        // rather than freezing.
        return if (moved == target) held else Decision(moved, anchor, true, nowNs)
    }

    private data class Decision(
        val target: Int,
        val baseline: Int?,
        val locked: Boolean,
        val lastMoveNs: Long?,
    )
}

/**
 * The cadence of the most recent [limit] gaps, or null if there are fewer than
 * [atLeast] of them.
 *
 * [StepSource] decides whether that is the median gap or the mean one, and the
 * choice is worth several spm in both directions.
 */
private fun List<Long>.windowSpm(
    atLeast: Int,
    source: StepSource,
    limit: Int = Int.MAX_VALUE,
): Double? {
    if (size < 2) return null

    val intervals = zipWithNext { a, b -> b - a }
    val considered = if (intervals.size > limit) intervals.takeLast(limit) else intervals
    if (considered.size < atLeast) return null

    val typical = when (source) {
        StepSource.TIMED -> considered.median()
        StepSource.COUNTED -> considered.sum().toDouble() / considered.size
    }

    return if (typical <= 0.0) null else 60_000_000_000.0 / typical
}

private fun List<Long>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle].toDouble()
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

/**
 * What the awake clock read at a given moment of wall-clock time.
 *
 * A replay needs this because the difference between the two clocks is the only
 * evidence of suspend, and a suspend is what [CadenceLoop.advance] has to tell
 * apart from a runner standing still.
 */
fun interface AwakeClock {
    fun at(nowNs: Long): Long

    companion object {
        /** A CPU that never sleeps, which is what a caller with one clock means. */
        val Always = AwakeClock { it }
    }
}

/**
 * The awake clock these samples were delivered against.
 *
 * Between two deliveries the clock is interpolated, because a recording says
 * how much of a gap was spent suspended but not whereabouts in the gap. Only
 * the total matters to the loop, whose thresholds are all durations.
 *
 * Returns [AwakeClock.Always] for a recording made before uptime was captured,
 * which is the same thing the loop assumed at the time.
 */
fun List<StepSample>.awakeClock(): AwakeClock {
    val wall = mutableListOf<Long>()
    val awake = mutableListOf<Long>()

    for (sample in sortedBy { it.receivedElapsedRealtimeNs }) {
        val uptime = sample.receivedUptimeNs ?: continue
        if (wall.isNotEmpty() && sample.receivedElapsedRealtimeNs == wall.last()) continue
        wall += sample.receivedElapsedRealtimeNs
        awake += uptime
    }

    if (wall.isEmpty()) return AwakeClock.Always

    return AwakeClock { nowNs ->
        val hit = wall.binarySearch(nowNs)
        if (hit >= 0) {
            awake[hit]
        } else {
            val next = -hit - 1
            when {
                // Outside the recording there is nothing to interpolate against,
                // so assume the CPU was up: it is the conservative guess, being
                // the one that lets a silence read as a stall.
                next == 0 -> awake.first() - (wall.first() - nowNs)
                next == wall.size -> awake.last() + (nowNs - wall.last())
                else -> {
                    val previous = next - 1
                    val through = (nowNs - wall[previous]).toDouble() /
                        (wall[next] - wall[previous])
                    awake[previous] + ((awake[next] - awake[previous]) * through).toLong()
                }
            }
        }
    }
}

/**
 * Runs a recording, or a synthesised one, through [CadenceLoop.advance] exactly
 * as the phone would.
 *
 * Steps are handed over when their arrival time says they arrived, not when
 * they happened, so a fixture reproduces the platform's batched delivery rather
 * than an idealised stream the loop will never actually see.
 *
 * @param tickNs the timer interval the caller would use on device, spent in
 * awake time: the caller's timer is an ordinary delay, which does not fire
 * through suspend but on the far side of it.
 * @param awake the CPU's sleep behaviour over the same span. Defaulting to
 * [AwakeClock.Always] makes a replay that does not care about suspend read as it
 * did before there was a second clock.
 * @param onTick called after every advance, for a replay that wants a timeline
 * rather than only a verdict.
 */
fun CadenceLoop.replay(
    samples: List<StepSample>,
    tickNs: Long = 5 * SECOND_NS,
    startNs: Long? = null,
    endNs: Long? = null,
    awake: AwakeClock = AwakeClock.Always,
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

        loop = loop.advance(now, due, awake.at(now))
        onTick(now, loop)

        if (now >= end && pending >= ordered.size) return loop
        now = nextTick(now, tickNs, end, awake, ordered.getOrNull(pending))
    }
}

/**
 * When the caller would next be running, having last run at [nowNs].
 *
 * A tick costs [tickNs] of awake time, so a suspended CPU skips the ticks it
 * slept through rather than delivering them late in a burst. A delivery ends the
 * wait early, since receiving one is itself being awake. Under
 * [AwakeClock.Always] this is [nowNs] plus [tickNs] and nothing else.
 */
private fun nextTick(
    nowNs: Long,
    tickNs: Long,
    endNs: Long,
    awake: AwakeClock,
    next: StepSample?,
): Long {
    val due = awake.at(nowNs) + tickNs

    // Bounded by whichever comes first of the next delivery and the end of the
    // replay, so that a clock which stays frozen — a CPU that never wakes again
    // — ends the replay rather than searching forever for a tick it will never
    // owe. Under [AwakeClock.Always] the search never runs at all and this is
    // the plain grid it has always been.
    val limit = next?.receivedElapsedRealtimeNs ?: endNs
    var candidate = nowNs + tickNs
    while (awake.at(candidate) < due && candidate < limit) candidate += tickNs

    return candidate
}
