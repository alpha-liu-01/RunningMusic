package lol.alphaliu01.runningmusic.cadence

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FOUR_MINUTES = 240_000L
private val TIGHT = ToleranceBand.symmetric(1.03)

private fun library(bpm: Double, count: Int) =
    List(count) { Candidate("track $it", bpm, FOUR_MINUTES) }

class CadenceSuggestionTest {

    /**
     * The point of the whole feature. A library clustered at 89 bpm is out of
     * reach at 170 spm and fully in reach a few spm up, and a runner has no way
     * to discover that by dragging a slider.
     */
    @Test
    fun `finds a nearby cadence that unlocks a cluster the requested one misses`() {
        val suggestion = suggestCadence(library(89.0, 20), around = 170.0, band = TIGHT)

        assertEquals(0, suggestion.requested.coverage.accepted)
        assertEquals(20, suggestion.best.coverage.accepted)
        assertTrue(suggestion.worthMoving)
    }

    /**
     * 173 through 178 all accept the whole library, and 173 is the smallest nudge
     * that does. Asking a runner to change their gait more than necessary is a
     * worse suggestion even though it lands closer to a unity stretch.
     */
    @Test
    fun `prefers the smallest nudge that reaches the cluster`() {
        val suggestion = suggestCadence(library(89.0, 20), around = 170.0, band = TIGHT)

        assertEquals(173.0, suggestion.best.cadence)
    }

    /**
     * Also a regression test for the ranking. Every cadence from 166 to 175 takes
     * this whole library, and the slowest of them fills the most minutes, because
     * a track slowed to 0.976x occupies more of a run than one played straight.
     * Ranking on stretched time would therefore tell a runner to slow down for no
     * reason at all, and it did, so the ranking is on reachable music instead.
     */
    @Test
    fun `leaves a cadence alone when its own coverage is already the best`() {
        val suggestion = suggestCadence(library(170.0, 20), around = 170.0, band = TIGHT)

        assertEquals(170.0, suggestion.best.cadence)
        assertEquals(20, suggestion.requested.coverage.accepted)
        assertFalse(suggestion.worthMoving)

        val slowest = suggestion.options.first { it.cadence == 166.0 }
        assertTrue(slowest.coverage.stretchedMs > suggestion.best.coverage.stretchedMs)
        assertEquals(slowest.coverage.rawMs, suggestion.best.coverage.rawMs)
    }

    /**
     * Coverage moves smoothly enough near a peak that most cadences pick up a
     * track or two from their neighbours. Reporting that as a suggestion would
     * fire constantly, so a small gain is not worth mentioning.
     */
    @Test
    fun `a marginal gain is not worth moving for`() {
        val suggestion = suggestCadence(
            library = library(174.0, 20) + library(165.0, 2),
            around = 174.0,
            band = TIGHT,
        )

        assertEquals(20, suggestion.requested.coverage.accepted)
        assertEquals(22, suggestion.best.coverage.accepted)
        assertFalse(suggestion.worthMoving)
    }

    @Test
    fun `an empty library suggests nothing and stays where it was asked`() {
        val suggestion = suggestCadence(emptyList<Candidate<String>>(), around = 170.0)

        assertEquals(170.0, suggestion.best.cadence)
        assertEquals(0, suggestion.best.coverage.total)
        assertFalse(suggestion.worthMoving)
    }

    @Test
    fun `sweeps symmetrically about the requested cadence and includes it exactly`() {
        val suggestion = suggestCadence(library(170.0, 1), around = 170.0, radius = 3.0)

        assertEquals(
            listOf(167.0, 168.0, 169.0, 170.0, 171.0, 172.0, 173.0),
            suggestion.options.map { it.cadence },
        )
        assertEquals(170.0, suggestion.requested.cadence)
    }

    @Test
    fun `a zero radius considers only the requested cadence`() {
        val suggestion = suggestCadence(library(170.0, 5), around = 170.0, radius = 0.0)

        assertEquals(listOf(170.0), suggestion.options.map { it.cadence })
        assertFalse(suggestion.worthMoving)
    }

    @Test
    fun `a step wider than the radius still considers the requested cadence`() {
        val suggestion = suggestCadence(library(170.0, 5), around = 170.0, radius = 2.0, step = 5.0)

        assertEquals(listOf(170.0), suggestion.options.map { it.cadence })
    }

    @Test
    fun `rejects unusable input`() {
        val library = library(170.0, 1)

        assertFailsWith<IllegalArgumentException> { suggestCadence(library, around = 0.0) }
        assertFailsWith<IllegalArgumentException> { suggestCadence(library, around = Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            suggestCadence(library, around = 170.0, radius = -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            suggestCadence(library, around = 170.0, step = 0.0)
        }
    }
}
