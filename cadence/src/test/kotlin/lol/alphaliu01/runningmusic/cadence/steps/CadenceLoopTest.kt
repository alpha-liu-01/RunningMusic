package lol.alphaliu01.runningmusic.cadence.steps

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SECOND_NS = 1_000_000_000L

/** Where a runner left the slider before pressing start. */
private const val SLIDER = 170

private fun loop(
    mode: TrackingMode = TrackingMode.CONTINUOUS,
    target: Int = SLIDER,
    config: LoopConfig = LoopConfig(),
) = CadenceLoop(target = target, mode = mode, config = config)

/** Replays and keeps every step of the way, so a test can assert on the middle. */
private fun CadenceLoop.timeline(
    samples: List<StepSample>,
    endNs: Long? = null,
    awake: AwakeClock = AwakeClock.Always,
): List<Frame> {
    val frames = mutableListOf<Frame>()
    replay(samples, endNs = endNs, awake = awake) { nowNs, state ->
        frames += Frame(nowNs, state)
    }
    return frames
}

private data class Frame(val atNs: Long, val loop: CadenceLoop)

class CadenceLoopTest {

    // The recorded runs.

    /**
     * The fixture the whole feature is pinned to: five real minutes of running,
     * recorded untethered with the screen off and the CPU suspended for most of
     * it, so the loop is being fed the platform's actual delivery behaviour and
     * a real gait rather than an idealised one.
     *
     * The target lands near 187, not near the 177 the sensor spike reported.
     * Both are correct and the gap between them is the reason this loop takes a
     * median. 177 is the mean, which is 888 recovered steps divided by five
     * minutes, so every step the detector missed is counted as time spent not
     * stepping. The median stride in the same recording is 321 ms, which is 187
     * spm, and that is what the runner's legs were actually doing. A control
     * loop that matched music to the mean would run 10 spm slow on this device
     * purely because of missed steps.
     */
    @Test
    fun `locks to the measured cadence of the recorded run`() {
        val recording = Fixtures.load(Fixtures.RUN)

        val settled = loop(TrackingMode.MEASURE_THEN_LOCK).replay(recording.steps)

        assertTrue(settled.locked, "five minutes of running should have locked")
        assertEquals(Motion.MOVING, settled.motion)
        assertTrue(
            abs(settled.target - 187) <= 3,
            "expected a target near the recorded median stride of 187 spm, " +
                "got ${settled.target}",
        )
    }

    /**
     * Runs 2 and 3 were recorded at walking pace, which was a limitation of the
     * spike and is now the most valuable thing about them: they are the only
     * recordings of the pace this loop used to be blind to.
     *
     * Both walk at around 100 spm, and both must produce a target at that
     * cadence rather than sitting on the slider forever.
     */
    @Test
    fun `locks onto a recorded walk at the pace it was walked`() {
        for (name in listOf(Fixtures.WALK, Fixtures.WALK_REPEAT)) {
            val recording = Fixtures.load(name)

            val settled = loop(TrackingMode.CONTINUOUS).replay(recording.steps)
            val baseline = assertNotNull(settled.baseline, "$name never locked")

            assertTrue(
                abs(baseline - 103) <= 8,
                "$name locked at $baseline, expected the recorded walking cadence",
            )
        }
    }

    /**
     * The regression that sent the feature out of the door broken, kept as the
     * narrowest possible test of it.
     *
     * The floor used to be 140, and a walk sits below it. That alone would have
     * frozen the target, which was bad enough, but the real damage was quieter:
     * a cadence hovering near the floor crossed it every few seconds, and every
     * crossing reset the stretch of movement the measurement median is taken
     * over. The median needs thirty intervals to report at all, so it never
     * reported. On all four walk fixtures `measuredSpm` was null at every single
     * tick, for the entire recording, and the screen showed a run that was never
     * measuring anything.
     *
     * So this asserts the measurement exists and the classification is steady,
     * not that the target is any particular number.
     */
    @Test
    fun `measures a walk instead of flickering in and out of movement`() {
        for (name in listOf(Fixtures.WALK, Fixtures.WALK_REPEAT, Fixtures.TETHERED_WALK)) {
            val recording = Fixtures.load(name)

            val frames = loop(TrackingMode.CONTINUOUS).timeline(recording.steps)
            val settled = frames.last().loop

            assertNotNull(settled.measuredSpm, "$name never produced a measurement")
            assertFalse(
                frames.any { it.loop.motion == Motion.IDLING },
                "$name still drops out of movement mid-walk",
            )
        }
    }

    /**
     * A brisk walk, median stride 137 spm, recorded tethered.
     *
     * This was the recording that set the old floor at 140: pitched any lower,
     * it locked, and locking onto a walk was thought to be the failure. It is
     * now the expected behaviour, and the number it produces is its own cadence
     * rather than the 150 an earlier floor of 130 drew out of it.
     */
    @Test
    fun `locks onto a brisk walk at its own cadence`() {
        val recording = Fixtures.load(Fixtures.TETHERED_WALK)

        val settled = loop(TrackingMode.CONTINUOUS).replay(recording.steps)
        val baseline = assertNotNull(settled.baseline, "never locked onto a 137 spm walk")

        assertTrue(abs(baseline - 137) <= 4, "locked at $baseline, expected near 137")
    }

    /**
     * Steps that are not going anywhere: pacing a kitchen, shuffling at a
     * crossing. Below the floor the target still has to hold, or the music
     * follows someone standing about down to the bottom of the range.
     */
    @Test
    fun `holds the target for steps too slow to be going anywhere`() {
        val gait = StepStream().steady(40.0, seconds = 300)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val settled = frames.last().loop

        assertEquals(Motion.IDLING, settled.motion)
        assertFalse(settled.locked, "locked onto someone standing about")
        assertTrue(frames.all { it.loop.target == SLIDER }, "the target moved")
    }

    /** Twenty-eight seconds is not a measurement, and must not be treated as one. */
    @Test
    fun `does not lock on a recording too short to measure`() {
        val recording = Fixtures.load(Fixtures.SHORT)

        val settled = loop(TrackingMode.CONTINUOUS).replay(recording.steps)

        assertFalse(settled.locked)
        assertEquals(SLIDER, settled.target)
    }

    // Runaway feedback.

    /**
     * The failure mode the Overview singles out. Faster music makes a faster
     * runner which makes faster music, and a loop that simply follows will chase
     * a runner all the way to the top of its range.
     *
     * What prevents it is not the drift cap. The cap used to pin the target
     * within 10 spm of the opening minute for the rest of the run, which stopped
     * a runaway by also refusing every genuine change of pace. What actually
     * prevents it is that the target never *leads*: it moves toward the measured
     * cadence and never past it, so the music is only ever somewhere the runner
     * has already been, and a loop that cannot get ahead cannot pull.
     */
    @Test
    fun `never gets ahead of a runner who keeps speeding up`() {
        val gait = StepStream().ramp(170.0, 220.0, seconds = 600)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val settled = frames.last().loop

        assertTrue(settled.locked)

        for (frame in frames.filter { it.loop.locked }) {
            val measured = frame.loop.measuredSpm ?: continue
            // Plus one for the rounding to whole spm at the lock, which can put
            // the target half a step above the reading it was taken from.
            assertTrue(
                frame.loop.target <= measured + 1,
                "target ${frame.loop.target} got ahead of the runner's $measured spm",
            )
        }

        assertTrue(
            settled.target < 220,
            "the target caught the runner's top speed at ${settled.target}",
        )
    }

    /**
     * The drift cap bounds how fast the target may move, not how far it may
     * eventually get.
     *
     * A runner who finishes at a walk used to be held near the pace they opened
     * with for the rest of the run: the cap was anchored to the first minute and
     * 50 spm of genuine slowing did not fit inside it, so the music stayed where
     * they had plainly stopped being. Holding a pace moves the anchor, and the
     * music arrives late but correct.
     */
    @Test
    fun `follows a runner who drops to a walk and stays there`() {
        val gait = StepStream()
            .steady(170.0, seconds = 90)
            .steady(120.0, seconds = 900)

        val settled = loop(TrackingMode.CONTINUOUS).replay(gait.samples())

        assertTrue(settled.locked)
        assertTrue(
            abs(settled.target - 120) <= 4,
            "fifteen minutes of walking at 120 spm left the target at ${settled.target}",
        )
    }

    /**
     * Only a pace held counts. A burst shorter than the re-anchor window leaves
     * the anchor alone, so a hill or a sprint for a crossing does not redefine
     * the run.
     */
    @Test
    fun `a brief burst does not move the anchor`() {
        val gait = StepStream()
            .steady(170.0, seconds = 90)
            .steady(205.0, seconds = 30)
            .steady(170.0, seconds = 180)

        val settled = loop(TrackingMode.CONTINUOUS).replay(gait.samples())
        val baseline = assertNotNull(settled.baseline)

        assertTrue(
            abs(baseline - 170) <= 5,
            "a thirty-second burst moved the anchor to $baseline",
        )
    }

    /** The default mode does not follow at all, so a runaway cannot even start. */
    @Test
    fun `measure then lock ignores a runner who keeps speeding up`() {
        val gait = StepStream().ramp(170.0, 220.0, seconds = 600)

        val settled = loop(TrackingMode.MEASURE_THEN_LOCK).replay(gait.samples())

        assertEquals(settled.baseline, settled.target)
    }

    /**
     * Even inside the drift cap the target is not allowed to lurch. A runner who
     * genuinely changes pace by 25 spm should hear the music follow over
     * minutes, not in one ramp.
     *
     * The limit is spm per minute, not spm per tick, so it is asserted as an
     * invariant across the whole replay rather than as a step size: at every
     * moment after the lock, the distance travelled must fit in the time taken
     * to travel it.
     */
    @Test
    fun `rate limits the target when the pace changes abruptly`() {
        val gait = StepStream()
            .steady(170.0, seconds = 80)
            .steady(195.0, seconds = 45)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val lock = frames.first { it.loop.locked }
        val settled = frames.last().loop

        for (frame in frames.filter { it.loop.locked }) {
            val minutes = (frame.atNs - lock.atNs).toDouble() / (60 * SECOND_NS)
            // Plus one for the rounding to whole spm, which can only ever put
            // the target half a step ahead of the allowance.
            val allowed = settled.config.maxDriftPerMinuteSpm * minutes + 1
            val moved = abs(frame.loop.target - lock.loop.target)
            assertTrue(
                moved <= allowed,
                "moved $moved spm in ${"%.1f".format(minutes)} min, allowance ${"%.1f".format(allowed)}",
            )
        }

        assertTrue(settled.target > lock.loop.target, "the target never started following")
        assertTrue(settled.target < 190, "the target reached the new pace far too quickly")
    }

    // Holding the target.

    /**
     * A traffic light. Steps stop entirely for a minute and then resume at the
     * same pace. Collapsing the target here is the single most obvious way for
     * this feature to feel broken.
     */
    @Test
    fun `holds the target through a traffic light`() {
        val gait = StepStream()
            .steady(175.0, seconds = 90)
            .still(seconds = 60)
            .steady(175.0, seconds = 60)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples(), endNs = gait.endNs)
        val locked = frames.first { it.loop.locked }.loop.target

        assertTrue(frames.any { it.loop.motion == Motion.STOPPED }, "the stop was not noticed")
        assertTrue(
            frames.filter { it.loop.locked }.all { it.loop.target == locked },
            "the target moved during or after the stop",
        )
    }

    /**
     * A walking break in the middle of a run, which differs from a stop only in
     * that steps keep coming.
     *
     * Now that a walk is a cadence the loop will measure, the target does follow
     * this down, and it should: a runner who drops to a walk for a minute and a
     * half has changed pace. What must not happen is the music arriving at
     * walking pace, and what prevents that is the rate limit rather than any
     * judgement about gait. A 65 spm drop cannot be absorbed inside a break this
     * short at a few spm a minute, however long the loop is left to think about
     * it, so the break costs the run a nudge downward and nothing more.
     */
    @Test
    fun `a walking break moves the target no faster than the rate limit`() {
        val gait = StepStream()
            .steady(175.0, seconds = 90)
            .steady(110.0, seconds = 90)
            .steady(175.0, seconds = 60)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val locked = frames.first { it.loop.locked }.loop.target
        val lowest = frames.filter { it.loop.locked }.minOf { it.loop.target }

        assertTrue(lowest > 150, "the music followed the walk down to $lowest spm")
        assertTrue(lowest < locked, "the target ignored the walk entirely, at $lowest spm")
    }

    /**
     * The sensor stops delivering and never resumes. The loop has to distinguish
     * this from a long traffic light and, either way, leave the music alone
     * rather than winding it down to nothing.
     */
    @Test
    fun `holds the target when the sensor dies mid-run`() {
        val gait = StepStream()
            .steady(175.0, seconds = 90)
            .still(seconds = 120)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples(), endNs = gait.endNs)
        val settled = frames.last().loop

        assertEquals(Motion.SENSOR_LOST, settled.motion)
        assertEquals(frames.first { it.loop.locked }.loop.target, settled.target)
        assertEquals(null, settled.measuredSpm, "a dead sensor must not report a cadence")
    }

    /** A sensor that never delivers anything at all, rather than one that stops. */
    @Test
    fun `reports a sensor that never delivers`() {
        val settled = loop(TrackingMode.CONTINUOUS)
            .replay(emptyList(), startNs = 0, endNs = 120 * SECOND_NS)

        assertEquals(Motion.SENSOR_LOST, settled.motion)
        assertEquals(SLIDER, settled.target)
        assertFalse(settled.locked)
    }

    // A sleeping CPU.
    //
    // The OnePlus case. Neither of its step sensors has a wakeup variant, so
    // with the screen off the phone suspends and the loop hears nothing until
    // something else wakes it. Nothing is lost while it sleeps — the counter is
    // cumulative and its pedometer is always on — so the only question is
    // whether the loop can tell a sleeping phone from a stopped runner.
    //
    // The loop's own timer cannot help it: it is an ordinary delay, so it is
    // spent in awake time and fires on the far side of a suspend rather than
    // during one. Both scenarios below are built on that, which is why replay
    // ticks on the awake clock too.

    /**
     * A phone up for two seconds in every ten. The runner never stops, but every
     * wakeup lands well after the last one on the wall clock, and the gap is
     * wider than the stall threshold.
     *
     * This is the shape of the bug: silence judged on a clock that runs through
     * suspend reads every one of those gaps as a runner standing still.
     */
    @Test
    fun `a wakeup that hears nothing is not a stop`() {
        val gait = StepStream().steady(175.0, seconds = 180)
        val dozing = sleepingCpu(awakeNs = 2 * SECOND_NS, cycleNs = 10 * SECOND_NS)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(
            gait.samples(bursty(periodNs = 30 * SECOND_NS, lagNs = 0)),
            endNs = gait.endNs,
            awake = dozing,
        )
        val settled = frames.last().loop

        assertTrue(
            frames.none { it.loop.motion == Motion.STOPPED },
            "a sleeping CPU was mistaken for a stopped runner",
        )
        assertTrue(settled.locked, "three minutes of running should have locked")
        assertEquals(175.0, assertNotNull(settled.measuredSpm), absoluteTolerance = 3.0)
    }

    /**
     * The same run, told the CPU was up the whole time. It reads as a runner who
     * stops every half minute and never holds a pace long enough to measure,
     * which is what the loop used to conclude and why a wake lock looked like
     * the answer.
     *
     * Here to keep the test above honest: without it, that one would pass just
     * as well on a gait no version of the loop ever had trouble with.
     */
    @Test
    fun `the same run reads as stopping if the CPU is assumed awake`() {
        val gait = StepStream().steady(175.0, seconds = 180)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(
            gait.samples(bursty(periodNs = 30 * SECOND_NS, lagNs = 0)),
            endNs = gait.endNs,
        )

        assertTrue(
            frames.any { it.loop.motion == Motion.STOPPED },
            "the awake clock is not what the test above is measuring",
        )
        assertFalse(frames.last().loop.locked, "as above")
    }

    /**
     * A phone asleep so deeply that the only thing waking it is the delivery
     * itself: half a minute of running arrives at once, four or five times over.
     *
     * The measurement has to be satisfied out of those, and it can be, because a
     * batch spanning half a minute is a cumulative counter's word that half a
     * minute of running happened. Crediting each wakeup only the stall threshold
     * instead — on the older reasoning that a suspended CPU cannot vouch for
     * what the runner was doing — leaves this run still measuring at the end of
     * it, having thrown away three quarters of the evidence.
     */
    @Test
    fun `a phone that wakes only to be handed steps still reaches a lock`() {
        val gait = StepStream().steady(175.0, seconds = 150)
        val asleep = sleepingCpu(awakeNs = 2 * SECOND_NS, cycleNs = 30 * SECOND_NS)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(
            gait.samples(bursty(periodNs = 30 * SECOND_NS, lagNs = 0)),
            endNs = gait.endNs,
            awake = asleep,
        )
        val locked = frames.first { it.loop.locked }

        assertEquals(175.0, assertNotNull(locked.loop.measuredSpm), absoluteTolerance = 3.0)
        assertTrue(
            frames.none { it.loop.motion == Motion.STOPPED },
            "a sleeping CPU was mistaken for a stopped runner",
        )
    }

    /**
     * And the case the awake clock must not paper over: a runner who genuinely
     * stops while the phone is wide awake is still stopped, on time.
     */
    @Test
    fun `a stop with the CPU awake is still noticed within the stall threshold`() {
        val gait = StepStream()
            .steady(175.0, seconds = 90)
            .still(seconds = 30)

        val config = LoopConfig()
        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples(), endNs = gait.endNs)
        val lastStep = frames.last { it.loop.motion == Motion.MOVING }.atNs
        val noticed = frames.first { it.atNs > lastStep && it.loop.motion == Motion.STOPPED }

        assertTrue(
            noticed.atNs - lastStep <= config.stallNs + 5 * SECOND_NS,
            "the stop took ${(noticed.atNs - lastStep) / SECOND_NS}s to notice",
        )
    }

    // Smoothing.

    /**
     * Human cadence is not constant, and a loop that acts on every wobble would
     * be permanently rewriting the queue and re-ramping playback speed. Nothing
     * inside the deadband may reach the target at all.
     */
    @Test
    fun `a small wobble moves nothing`() {
        val gait = StepStream().steady(175.0, seconds = 90)
        repeat(6) { gait.steady(if (it % 2 == 0) 177.0 else 173.0, seconds = 20) }

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val after = frames.filter { it.loop.locked }.map { it.loop.target }.distinct()

        assertEquals(1, after.size, "the target wandered across $after")
    }

    /**
     * The platform hands over roughly a second of steps at a time rather than one
     * event at a time, so the burst pattern is the normal case. It must not
     * change the answer, and it must not read as a stop every time a batch ends.
     */
    @Test
    fun `bursty delivery reaches the same target as immediate delivery`() {
        val gait = StepStream().steady(178.0, seconds = 180)

        val direct = loop(TrackingMode.CONTINUOUS).replay(gait.samples(Immediate))
        val batched = loop(TrackingMode.CONTINUOUS).replay(gait.samples(bursty()))

        assertEquals(Motion.MOVING, batched.motion)
        assertEquals(direct.target, batched.target)
    }

    /**
     * A missed step doubles one interval and a doubled step halves another. Both
     * happen on real hardware, and both are why the smoothing is a median rather
     * than a mean: a mean carries the error into the target.
     */
    @Test
    fun `a handful of missed steps does not move the target`() {
        val gait = StepStream().steady(176.0, seconds = 180)
        val clean = gait.samples()
        val lossy = clean.filterIndexed { index, _ -> index % 17 != 0 }

        assertEquals(
            loop(TrackingMode.CONTINUOUS).replay(clean).target,
            loop(TrackingMode.CONTINUOUS).replay(lossy).target,
        )
    }

    // Modes and timing.

    @Test
    fun `manual mode never moves the target`() {
        val gait = StepStream().steady(190.0, seconds = 300)

        val settled = loop(TrackingMode.MANUAL).replay(gait.samples())

        assertEquals(SLIDER, settled.target)
        assertFalse(settled.locked)
    }

    /**
     * The opening measurement is a minute long for a reason, and a loop that
     * locked after fifteen seconds would be committing to whatever the runner
     * happened to be doing as they put their phone in their pocket.
     */
    @Test
    fun `does not lock before a full minute of running`() {
        val gait = StepStream().steady(175.0, seconds = 300)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val lockedAt = frames.indexOfFirst { it.loop.locked }

        assertTrue(lockedAt > 0, "never locked")
        assertTrue(
            frames[lockedAt].loop.movingNs >= 60 * SECOND_NS,
            "locked after only ${frames[lockedAt].loop.movingNs / SECOND_NS}s of running",
        )
    }

    /**
     * A stop during the opening minute must extend the measurement rather than
     * restart it or, far worse, count the stop as running. Sixty seconds of
     * running split by a pause is still sixty seconds of running.
     */
    @Test
    fun `a stop during the opening minute extends the measurement`() {
        val gait = StepStream()
            .steady(176.0, seconds = 40)
            .still(seconds = 40)
            .steady(176.0, seconds = 90)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples(), endNs = gait.endNs)
        val settled = frames.last().loop

        assertTrue(settled.locked)
        assertTrue(
            abs(settled.target - 176) <= 2,
            "the pause leaked into the measurement, giving ${settled.target}",
        )
    }

    /**
     * The one clock assumption in the loop, made explicit. Step timestamps are
     * only ever subtracted from each other, so a device whose sensor counts from
     * a different epoch than `elapsedRealtimeNanos` behaves identically. Getting
     * this wrong would read as a permanently dead sensor on that hardware.
     */
    @Test
    fun `tolerates a sensor clock on a different base`() {
        val gait = StepStream().steady(175.0, seconds = 180)
        val skewed = gait.samples().map {
            it.copy(sensorTimestampNs = it.sensorTimestampNs + 3L * 24 * 3600 * SECOND_NS)
        }

        val settled = loop(TrackingMode.CONTINUOUS).replay(skewed)

        assertEquals(Motion.MOVING, settled.motion)
        assertTrue(settled.locked)
        assertTrue(abs(settled.target - 175) <= 2, "got ${settled.target}")
    }

    /** The target is a cadence, and cadences outside the slider's range are not. */
    @Test
    fun `never leaves the cadence range`() {
        val gait = StepStream().steady(240.0, seconds = 300)
        val config = LoopConfig(maxDriftFromLockSpm = 100.0)

        val settled = loop(TrackingMode.CONTINUOUS, config = config).replay(gait.samples())

        assertTrue(settled.target in config.cadenceRange, "got ${settled.target}")
    }

    @Test
    fun `rejects a configuration that could never notice a stop`() {
        assertFailsWith<IllegalArgumentException> {
            LoopConfig(stallNs = 30 * SECOND_NS, sensorLostNs = 10 * SECOND_NS)
        }
    }
}
