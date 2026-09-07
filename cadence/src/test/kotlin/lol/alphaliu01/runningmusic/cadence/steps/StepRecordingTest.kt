package lol.alphaliu01.runningmusic.cadence.steps

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Matches what the device under test actually reports, so the fixture is realistic. */
private val header = RecordingHeader(
    device = "Redmi Note 12 5G",
    androidRelease = "16",
    sdkInt = 36,
    sensorName = "step_detect  Wakeup",
    sensorVendor = "qualcomm",
    isWakeUp = true,
    fifoMaxEvents = 10_000,
    fgsType = "mediaPlayback",
    batchLatencyUs = 0,
    startWallClockMs = 1_757_000_000_000,
    startElapsedRealtimeNs = 123_456_789_000,
)

private fun write(block: StepRecordingWriter.() -> Unit): String =
    StringBuilder().also { StepRecordingWriter(it).apply { writeHeader(header) }.block() }.toString()

class StepRecordingTest {

    @Test
    fun `round-trips a recording`() {
        val steps = listOf(
            StepSample(1_000_000_000, 1_000_500_000, 900_000_000),
            StepSample(1_352_941_176, 1_353_400_000, 1_100_000_000),
            StepSample(1_705_882_352, 1_706_100_000, 1_300_000_000),
        )
        val accel = listOf(
            AccelSample(1_000_000_000, 0.1f, -9.8f, 0.25f),
            AccelSample(1_020_000_000, 0.2f, -9.7f, 0.30f),
        )

        val text = write {
            steps.forEach(::writeStep)
            accel.forEach(::writeAccel)
        }
        val parsed = parseStepRecording(text)

        assertEquals(header, parsed.header)
        assertContentEquals(steps, parsed.steps)
        assertContentEquals(accel, parsed.accel)
    }

    @Test
    fun `an empty recording still carries its header`() {
        val parsed = parseStepRecording(write { })

        assertEquals(header, parsed.header)
        assertTrue(parsed.steps.isEmpty())
        assertTrue(parsed.accel.isEmpty())
    }

    /**
     * The reason the format is line-oriented. A recording whose process was
     * killed mid-write must still yield everything up to the last whole line.
     */
    @Test
    fun `a truncated final line is dropped and the rest survives`() {
        val complete = write {
            writeStep(StepSample(1_000_000_000, 1_000_500_000, 900_000_000))
            writeStep(StepSample(1_352_941_176, 1_353_400_000, 1_100_000_000))
        }
        // Cut a few characters into the final line, as a killed process would.
        val lastLineStart = complete.dropLast(1).lastIndexOf('\n') + 1
        val truncated = complete.substring(0, lastLineStart + 6)

        val parsed = parseStepRecording(truncated)

        assertEquals(1, parsed.steps.size)
        assertEquals(1_000_000_000, parsed.steps.single().sensorTimestampNs)
    }

    @Test
    fun `unknown record types from a future version are ignored`() {
        val text = write { writeStep(StepSample(1_000_000_000, 1_000_500_000)) } +
            "G 1234 55.9 -3.2\n" +
            "\n" +
            "S 2000000000 2000400000\n"

        val parsed = parseStepRecording(text)

        assertEquals(2, parsed.steps.size)
    }

    @Test
    fun `malformed numbers are skipped rather than failing the whole file`() {
        val text = write { writeStep(StepSample(1_000_000_000, 1_000_500_000)) } +
            "S notanumber 123\n" +
            "A 1 2 3\n" + // too few fields
            "S 2000000000 2000400000\n"

        val parsed = parseStepRecording(text)

        assertEquals(2, parsed.steps.size)
        assertTrue(parsed.accel.isEmpty())
    }

    @Test
    fun `a missing header field is fatal`() {
        val text = write { }.lineSequence().filterNot { it.startsWith("# sensorVendor") }
            .joinToString("\n")

        val failure = assertFailsWith<StepRecordingFormatException> { parseStepRecording(text) }
        assertTrue(failure.message!!.contains("sensorVendor"))
    }

    @Test
    fun `a file that is not a recording is rejected`() {
        assertFailsWith<StepRecordingFormatException> { parseStepRecording("hello\nworld\n") }
        assertFailsWith<StepRecordingFormatException> {
            parseStepRecording("# format=something-else\n")
        }
    }

    @Test
    fun `a header value containing an equals sign survives`() {
        val odd = header.copy(sensorName = "step_detect a=b")
        val text = StringBuilder()
            .also { StepRecordingWriter(it).writeHeader(odd) }
            .toString()

        assertEquals("step_detect a=b", parseStepRecording(text).header.sensorName)
    }

    @Test
    fun `delivery lag exposes buffered events`() {
        val live = StepSample(1_000_000_000, 1_000_500_000)
        val batched = StepSample(1_000_000_000, 61_000_000_000)

        assertEquals(500_000, live.deliveryLagNs)
        assertEquals(60_000_000_000, batched.deliveryLagNs)
    }

    @Test
    fun `intervals and cadence are derived from the sensor clock`() {
        // Three steps at 170 spm: 352.94 ms apart.
        val text = write {
            writeStep(StepSample(0, 100))
            writeStep(StepSample(352_941_176, 352_941_276))
            writeStep(StepSample(705_882_352, 705_882_452))
        }

        val intervals = parseStepRecording(text).stepIntervalsNs()

        assertEquals(2, intervals.size)
        intervals.forEach { assertEquals(170.0, cadenceSpm(it), 0.01) }
    }

    @Test
    fun `cadence of a non-positive interval is zero rather than infinite`() {
        assertEquals(0.0, cadenceSpm(0))
        assertEquals(0.0, cadenceSpm(-1))
    }

    /**
     * The measurement the whole third clock exists for. Over ten seconds of wall
     * time the CPU was up for two, so eight were spent suspended.
     */
    @Test
    fun `suspended time is the divergence between the two receive clocks`() {
        val text = write {
            writeStep(StepSample(0, 100_000_000_000, 50_000_000_000))
            writeStep(StepSample(10_000_000_000, 110_000_000_000, 52_000_000_000))
        }

        val parsed = parseStepRecording(text)

        assertEquals(8_000_000_000, parsed.suspendedNs())
        assertEquals(10_000_000_000, parsed.observedSpanNs())
    }

    @Test
    fun `a CPU that never slept reports no suspended time`() {
        val text = write {
            writeStep(StepSample(0, 1_000_000_000, 1_000_000_000))
            writeStep(StepSample(5_000_000_000, 6_000_000_000, 6_000_000_000))
        }

        assertEquals(0, parseStepRecording(text).suspendedNs())
    }

    /**
     * Recordings made before the uptime clock existed must still parse, and must
     * say they cannot answer the suspend question rather than answering it wrong.
     */
    @Test
    fun `version 1 recordings still parse and report suspend as unknown`() {
        val v1 = write { }.replace("# version=2", "# version=1") +
            "S 1000000000 1000500000\n" +
            "S 1352941176 1353400000\n"

        val parsed = parseStepRecording(v1)

        assertEquals(2, parsed.steps.size)
        assertNull(parsed.steps.first().receivedUptimeNs)
        assertFalse(parsed.hasUptimeClock)
        assertNull(parsed.suspendedNs())
        // The clocks it does have still work.
        assertEquals(500_000, parsed.steps.first().deliveryLagNs)
    }

    @Test
    fun `a single step is not enough to measure suspend`() {
        val text = write { writeStep(StepSample(0, 100_000_000_000, 50_000_000_000)) }

        assertEquals(0, parseStepRecording(text).suspendedNs())
    }

    /** The uptime field's presence must survive a write-read cycle either way. */
    @Test
    fun `a sample without the uptime clock round-trips as absent`() {
        val text = write { writeStep(StepSample(1_000_000_000, 1_000_500_000)) }

        assertNull(parseStepRecording(text).steps.single().receivedUptimeNs)
    }
}
