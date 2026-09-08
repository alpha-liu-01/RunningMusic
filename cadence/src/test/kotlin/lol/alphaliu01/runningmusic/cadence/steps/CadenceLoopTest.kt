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
): List<Frame> {
    val frames = mutableListOf<Frame>()
    replay(samples, endNs = endNs) { nowNs, state -> frames += Frame(nowNs, state) }
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
        assertEquals(Motion.RUNNING, settled.motion)
        assertTrue(
            abs(settled.target - 187) <= 3,
            "expected a target near the recorded median stride of 187 spm, " +
                "got ${settled.target}",
        )
    }

    /**
     * Runs 2 and 3 were recorded at walking pace, which was a limitation of the
     * spike and is now the most valuable thing about them. A walk must never
     * become a target, or the first traffic light of a run permanently halves
     * the tempo of the music.
     */
    @Test
    fun `never locks onto a recorded walk`() {
        for (name in listOf(Fixtures.WALK, Fixtures.WALK_REPEAT)) {
            val recording = Fixtures.load(name)

            val frames = loop(TrackingMode.CONTINUOUS).timeline(recording.steps)
            val settled = frames.last().loop

            // Asserted over the whole replay rather than at the final tick. A
            // walk contains bursts quick enough to read as running for a few
            // seconds, and that is fine; what must never happen is those bursts
            // adding up to a lock.
            assertTrue(frames.any { it.loop.motion == Motion.WALKING }, name)
            assertFalse(settled.locked, "$name locked onto a walk")
            assertTrue(frames.all { it.loop.target == SLIDER }, "$name moved the target")
        }
    }

    /**
     * The hardest of the recordings, and the one that set the walking floor.
     *
     * This is a brisk walk whose median stride is 137 spm, with stretches well
     * above that. Against a floor pitched at a textbook walking cadence of 130
     * it looked like running for long enough to lock, and the loop committed to
     * a 150 spm target for a walk. The floor is now the bottom of the cadence
     * range, on the grounds that a reading the app would refuse to set as a
     * target has no business being measured as one.
     */
    @Test
    fun `does not mistake a brisk walk for a slow run`() {
        val recording = Fixtures.load(Fixtures.TETHERED_WALK)

        val settled = loop(TrackingMode.CONTINUOUS).replay(recording.steps)

        assertFalse(settled.locked, "locked onto a 137 spm walk")
        assertEquals(SLIDER, settled.target)
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
     * Here the runner accelerates from 170 to 220 spm over ten minutes. The
     * target is allowed to move, but never further from the locked baseline than
     * the drift cap, so the runaway terminates instead of compounding.
     */
    @Test
    fun `refuses to chase a runner who keeps speeding up`() {
        val gait = StepStream().ramp(170.0, 220.0, seconds = 600)

        val settled = loop(TrackingMode.CONTINUOUS).replay(gait.samples())
        val baseline = assertNotNull(settled.baseline)

        assertTrue(settled.locked)
        assertTrue(
            settled.target <= baseline + settled.config.maxDriftFromLockSpm,
            "target ${settled.target} escaped the drift cap around $baseline",
        )
        assertTrue(
            settled.target < 190,
            "the runner reached 220 spm and the loop followed to ${settled.target}",
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
     * A walking break, which differs from a stop only in that steps keep coming.
     * A loop that watched for silence alone would happily lock onto 110 spm.
     */
    @Test
    fun `holds the target through a walking break`() {
        val gait = StepStream()
            .steady(175.0, seconds = 90)
            .steady(110.0, seconds = 90)
            .steady(175.0, seconds = 60)

        val frames = loop(TrackingMode.CONTINUOUS).timeline(gait.samples())
        val locked = frames.first { it.loop.locked }.loop.target

        assertTrue(frames.any { it.loop.motion == Motion.WALKING }, "the walk was not noticed")
        assertTrue(
            frames.filter { it.loop.locked }.all { abs(it.loop.target - locked) <= 1 },
            "the target followed the walk down to ${frames.minOf { it.loop.target }}",
        )
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

        assertEquals(Motion.RUNNING, batched.motion)
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
            frames[lockedAt].loop.runningNs >= 60 * SECOND_NS,
            "locked after only ${frames[lockedAt].loop.runningNs / SECOND_NS}s of running",
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

        assertEquals(Motion.RUNNING, settled.motion)
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
