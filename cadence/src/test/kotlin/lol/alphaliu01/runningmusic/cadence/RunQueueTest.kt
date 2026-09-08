package lol.alphaliu01.runningmusic.cadence

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.math.roundToLong
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FIVE_MINUTES = 300_000L
private const val CADENCE = 170.0

private fun track(name: String, bpm: Double, durationMs: Long = FIVE_MINUTES) =
    Candidate(name, bpm, durationMs)

class RunQueueTest {

    @Test
    fun `takes tracks in deviation order until the run is covered`() {
        val library = listOf(
            track("perfect", 170.0),   // speed 1.0
            track("slightly fast", 175.0),
            track("slightly slow", 165.0),
            track("half tempo", 85.0), // speed 1.0, two steps per beat
            track("unreachable", 125.0),
        )

        val queue = selectForRun(library, CADENCE, runLengthMs = 2 * FIVE_MINUTES)

        // The two zero-deviation tracks fill the run exactly, in input order.
        assertContentEquals(listOf("perfect", "half tempo"), queue.tracks.map { it.ref })
        assertEquals(2 * FIVE_MINUTES, queue.filledMs)
        assertEquals(0L, queue.shortfallMs)
        assertEquals(ToleranceBand.symmetric(1.0), queue.worstBand)
    }

    /**
     * The run is filled with stretched time, not raw time. A track played at 1.1x
     * covers less of the run than its file duration suggests.
     */
    @Test
    fun `fills the run using stretched duration`() {
        val cadence = 176.0
        val library = listOf(track("sped up", 160.0, durationMs = 330_000)) // speed 1.1

        val queue = selectForRun(library, cadence, runLengthMs = FIVE_MINUTES)

        assertEquals(1.1, queue.tracks.single().fold.speed, 1e-12)
        assertEquals(FIVE_MINUTES, queue.tracks.single().stretchedDurationMs) // 330s / 1.1 = 300s
        assertEquals(0L, queue.shortfallMs)
    }

    @Test
    fun `an empty library returns an empty queue and the full shortfall`() {
        val queue = selectForRun(emptyList<Candidate<String>>(), CADENCE, runLengthMs = 2_700_000)

        assertTrue(queue.tracks.isEmpty())
        assertEquals(0L, queue.filledMs)
        assertEquals(2_700_000L, queue.shortfallMs)
        assertNull(queue.worstBand)
    }

    /**
     * A library too thin for the run comes back short. It must never widen the
     * band to cover the gap, because that trades a shorter queue for
     * unlistenable audio.
     */
    @Test
    fun `a library too thin to fill the run reports a shortfall`() {
        val library = listOf(track("a", 170.0), track("b", 85.0))

        val queue = selectForRun(library, CADENCE, runLengthMs = 30 * 60_000)

        assertEquals(2, queue.tracks.size)
        assertEquals(2 * FIVE_MINUTES, queue.filledMs)
        assertEquals(30 * 60_000L - 2 * FIVE_MINUTES, queue.shortfallMs)
        assertTrue(queue.worstBand!!.widest <= ToleranceBand.DEFAULT.widest)
    }

    @Test
    fun `a library of only unreachable tempos selects nothing`() {
        val library = listOf(track("a", 125.0), track("b", 122.0), track("c", 128.0))

        val queue = selectForRun(library, CADENCE, runLengthMs = FIVE_MINUTES)

        assertTrue(queue.tracks.isEmpty())
        assertNull(queue.worstBand)
        assertEquals(FIVE_MINUTES, queue.shortfallMs)
    }

    @Test
    fun `the worst band that fell out is reported as an asymmetric shape`() {
        val library = listOf(
            track("perfect", 170.0),   // speed 1.0
            track("fast", 160.0),      // speed 1.0625
            track("slow", 180.0),      // speed 0.9444
        )

        val queue = selectForRun(library, CADENCE, runLengthMs = 3 * FIVE_MINUTES)

        assertEquals(3, queue.tracks.size)
        val band = queue.worstBand!!
        assertEquals(170.0 / 160.0, band.maxSpeedUp, 1e-12)
        assertEquals(180.0 / 170.0, band.maxSlowDown, 1e-12)
    }

    @Test
    fun `a wider band admits more of the library`() {
        val library = listOf(track("awkward", 145.0)) // speed 1.1724 at 170 spm

        val tight = selectForRun(library, CADENCE, runLengthMs = FIVE_MINUTES)
        val loose = selectForRun(
            library,
            CADENCE,
            runLengthMs = FIVE_MINUTES,
            band = ToleranceBand.symmetric(1.2),
        )

        assertTrue(tight.tracks.isEmpty())
        assertEquals(1, loose.tracks.size)
    }

    /**
     * A tempo detector that fails on one file must not take the whole run with
     * it, so unusable rows are dropped rather than thrown on.
     */
    @Test
    fun `drops unusable rows instead of failing`() {
        val library = listOf(
            track("no bpm", 0.0),
            track("negative bpm", -120.0),
            track("not a number", Double.NaN),
            track("no duration", 170.0, durationMs = 0),
            track("fine", 170.0),
        )

        val queue = selectForRun(library, CADENCE, runLengthMs = FIVE_MINUTES)

        assertContentEquals(listOf("fine"), queue.tracks.map { it.ref })
    }

    @Test
    fun `ties in deviation keep input order`() {
        val library = listOf(track("first", 170.0), track("second", 170.0))

        val queue = selectForRun(library, CADENCE, runLengthMs = 2 * FIVE_MINUTES)

        assertContentEquals(listOf("first", "second"), queue.tracks.map { it.ref })
    }

    @Test
    fun `a zero length run selects nothing`() {
        val queue = selectForRun(listOf(track("a", 170.0)), CADENCE, runLengthMs = 0)

        assertTrue(queue.tracks.isEmpty())
        assertEquals(0L, queue.shortfallMs)
    }

    @Test
    fun `rejects invalid arguments`() {
        val library = listOf(track("a", 170.0))
        assertFailsWith<IllegalArgumentException> { selectForRun(library, 0.0, FIVE_MINUTES) }
        assertFailsWith<IllegalArgumentException> { selectForRun(library, CADENCE, -1) }
    }
}

class RebaseTailTest {

    private val library = listOf(
        track("perfect", 170.0),
        track("slightly fast", 175.0),
        track("half tempo", 85.0),
        track("slightly slow", 165.0),
    )

    private fun rebase(
        tail: List<Selected<String>>,
        cadence: Double,
        band: ToleranceBand = ToleranceBand.DEFAULT,
    ) = rebaseTail(tail, cadence, band) { name -> library.firstOrNull { it.ref == name } }

    private fun planned(cadence: Double, vararg names: String) = names.map { name ->
        val candidate = library.first { it.ref == name }
        val folded = fold(candidate.bpm, cadence)
        Selected(name, folded, (candidate.durationMs / folded.speed).roundToLong())
    }

    /**
     * The point of the whole function. Reselecting instead would rank by
     * deviation from the new target and hand back a different order, which the
     * runner sees as their queue rearranging itself for no visible reason.
     */
    @Test
    fun `a small cadence move leaves the order alone`() {
        val tail = planned(170.0, "slightly slow", "perfect", "half tempo", "slightly fast")

        val rebased = rebase(tail, 172.0)

        assertContentEquals(
            listOf("slightly slow", "perfect", "half tempo", "slightly fast"),
            rebased.map { it.ref },
        )
    }

    @Test
    fun `speeds and stretched durations follow the new cadence`() {
        val tail = planned(170.0, "perfect")

        val rebased = rebase(tail, 187.0)

        // 187 / 170 is 1.1, and a five-minute track played 10% fast fills less.
        assertEquals(1.1, rebased.single().fold.speed, 1e-9)
        assertEquals((FIVE_MINUTES / 1.1).roundToLong(), rebased.single().stretchedDurationMs)
    }

    /**
     * Dropping is by tempo, not by position, so a run does not lose the tracks
     * that happened to sit next to the one it could no longer use.
     */
    @Test
    fun `an entry the new cadence cannot reach is dropped and its neighbours are not`() {
        val tail = planned(170.0, "perfect", "slightly slow", "half tempo")

        // At 178 spm 165 bpm needs 1.079x, past a 1.05 band. 170 bpm needs
        // 1.047x, and 85 bpm reaches the same speed at two steps per beat.
        val rebased = rebase(tail, 178.0, ToleranceBand.symmetric(1.05))

        assertContentEquals(listOf("perfect", "half tempo"), rebased.map { it.ref })
    }

    /**
     * A track picked mid-run before anything had analysed it. It plays as it was
     * recorded whatever the cadence does, and the runner asked for it, so a
     * replan has no business quietly taking it back out of the queue.
     */
    @Test
    fun `an entry with no tempo to rebase against survives untouched`() {
        val unanalysed = Selected("picked", Fold(exponent = 0, rawExponent = 0, speed = 1.0), 1L)
        val tail = planned(170.0, "perfect") + unanalysed

        val rebased = rebase(tail, 187.0)

        assertContentEquals(listOf("perfect", "picked"), rebased.map { it.ref })
        assertEquals(unanalysed, rebased.last())
    }

    /** The same rule, and the reason it matters most: no library, no changes. */
    @Test
    fun `a library that has not loaded yet does not empty the run`() {
        val tail = planned(170.0, "perfect", "half tempo")

        assertContentEquals(tail, rebaseTail(tail, 187.0) { null })
    }

    @Test
    fun `nothing to rebase is not an error`() {
        assertTrue(rebase(emptyList(), CADENCE).isEmpty())
    }

    @Test
    fun `rejects an impossible cadence`() {
        assertFailsWith<IllegalArgumentException> { rebase(planned(170.0, "perfect"), 0.0) }
    }
}
