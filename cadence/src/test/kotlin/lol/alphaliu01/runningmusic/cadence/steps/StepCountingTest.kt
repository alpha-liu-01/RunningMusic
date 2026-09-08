package lol.alphaliu01.runningmusic.cadence.steps

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SECOND_NS = 1_000_000_000L

/**
 * Why cadence is counted rather than timed.
 *
 * The loop measures the median gap between steps, which is right whenever a step
 * detector fires once per step. A OnePlus PKX110 does not: it declares its
 * detector `SPECIAL_TRIGGER` and then emits on a 994.3 ms hardware tick, so its
 * gaps measure the tick and every gait comes out at 60 spm. These tests pin down
 * both halves of that — the failure on the recording it was found in, and the
 * recovery once the same walk is counted instead.
 */
class StepCountingTest {

    private val counting = LoopConfig(source = StepSource.COUNTED)

    @Test
    fun `a counter that has not moved yields no steps`() {
        val first = CounterReading(atNs = 1_000 * SECOND_NS, steps = 4_000f)
        val same = CounterReading(atNs = 1_001 * SECOND_NS, steps = 4_000f)

        assertEquals(emptyList(), stepTimestampsFrom(previous = null, next = first))
        assertEquals(emptyList(), stepTimestampsFrom(previous = first, next = same))
    }

    @Test
    fun `steps are spread across the interval they were counted over`() {
        val previous = CounterReading(atNs = 1_000 * SECOND_NS, steps = 4_000f)
        val next = CounterReading(atNs = 1_002 * SECOND_NS, steps = 4_004f)

        val steps = stepTimestampsFrom(previous, next)

        assertEquals(4, steps.size)
        assertEquals(next.atNs, steps.last(), "the last step lands on the reading")
        assertEquals(
            listOf(500L, 500L, 500L),
            steps.zipWithNext { a, b -> (b - a) / 1_000_000 },
            "four steps over two seconds is one every 500 ms",
        )
    }

    @Test
    fun `a counter going backwards is a reboot, not a walk in reverse`() {
        val before = CounterReading(atNs = 1_000 * SECOND_NS, steps = 40_000f)
        val after = CounterReading(atNs = 1_001 * SECOND_NS, steps = 3f)

        assertEquals(emptyList(), stepTimestampsFrom(before, after))
    }

    @Test
    fun `an implausible jump is refused rather than flooding the window`() {
        val before = CounterReading(atNs = 1_000 * SECOND_NS, steps = 0f)
        val after = CounterReading(atNs = 1_001 * SECOND_NS, steps = 90_000f)

        assertEquals(emptyList(), stepTimestampsFrom(before, after))
    }

    @Test
    fun `a walk counted every second measures the pace it was walked`() {
        val walk = StepStream().steady(spm = 112.0, seconds = 180)

        val counted = countedSteps(walk.counterSamples(periodNs = SECOND_NS))
        val loop = CadenceLoop(target = 170, mode = TrackingMode.CONTINUOUS, config = counting)
            .replay(counted, endNs = walk.endNs)

        val measured = assertNotNull(loop.measuredSpm)
        assertTrue(
            abs(measured - 112.0) < 3.0,
            "counting recovers the 112 spm walk, measured $measured",
        )
        assertTrue(loop.locked, "and the target locks onto it")
        assertTrue(abs(loop.target - 112) <= 3, "at ${loop.target} spm")
    }

    @Test
    fun `a detector ticking once a second cannot be timed, but can be counted`() {
        // The exact gait and the exact defect the OnePlus recording shows: a real
        // 112 spm walk, delivered as one detector event per 994.3 ms tick.
        val walk = StepStream().steady(spm = 112.0, seconds = 180)
        val tickNs = 994_300_000L

        val ticking = StepStream().steady(spm = 60.0 * SECOND_NS / tickNs, seconds = 180)
        val timed = CadenceLoop(target = 170, mode = TrackingMode.CONTINUOUS)
            .replay(ticking.samples(), endNs = ticking.endNs)

        val fromTiming = assertNotNull(timed.measuredSpm)
        assertTrue(
            abs(fromTiming - 60.3) < 1.0,
            "timing the tick reads the tick, not the walk: $fromTiming",
        )

        // The counter saw the same 180 seconds and did not have to guess when
        // each step fell, only how many there were.
        val counted = countedSteps(walk.counterSamples(periodNs = tickNs))
        val loop = CadenceLoop(target = 170, mode = TrackingMode.CONTINUOUS, config = counting)
            .replay(counted, endNs = walk.endNs)

        val fromCounting = assertNotNull(loop.measuredSpm)
        assertTrue(
            abs(fromCounting - 112.0) < 3.0,
            "counting over the same ticks recovers the walk: $fromCounting",
        )
    }

    @Test
    fun `the recording that started this reads 60 spm however it is replayed`() {
        val recording = Fixtures.load(Fixtures.TICKING_DETECTOR)

        assertTrue(
            recording.counter.isEmpty(),
            "it predates the counter, so there is nothing to fall back to",
        )

        val loop = CadenceLoop(target = 170, mode = TrackingMode.CONTINUOUS)
            .replay(recording.steps)

        val measured = assertNotNull(loop.measuredSpm)
        assertTrue(
            abs(measured - 60.3) < 1.0,
            "every gait on this phone measured $measured spm, which is the tick",
        )
    }

    @Test
    fun `a version 3 recording round-trips both the counter and the detector's count`() {
        val header = RecordingHeader(
            device = "OnePlus PKX110",
            androidRelease = "16",
            sdkInt = 36,
            sensorName = "pedometer Oplus Step_detect Sensor Non-wakeup",
            sensorVendor = "oplus",
            isWakeUp = false,
            fifoMaxEvents = 10_000,
            fgsType = "mediaPlayback",
            batchLatencyUs = 0,
            startWallClockMs = 1_788_831_911_439,
            startElapsedRealtimeNs = 2_595_108_717_433_586,
        )

        val text = buildString {
            val writer = StepRecordingWriter(this)
            writer.writeHeader(header)
            writer.writeStep(StepSample(1_000L, 1_002L, 900L, reportedSteps = 2f))
            writer.writeCounter(CounterSample(1_000L, 1_002L, steps = 4_242f))
        }

        val parsed = parseStepRecording(text)

        assertEquals(2f, parsed.steps.single().reportedSteps)
        assertEquals(4_242f, parsed.counter.single().steps)
        assertEquals(1_000L, parsed.counter.single().sensorTimestampNs)
    }
}
