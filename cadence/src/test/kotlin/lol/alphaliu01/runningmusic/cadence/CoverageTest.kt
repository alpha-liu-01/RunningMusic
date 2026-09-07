package lol.alphaliu01.runningmusic.cadence

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {

    private val library = listOf(
        Candidate("perfect", 170.0, 300_000),
        Candidate("half tempo", 85.0, 300_000),
        Candidate("unreachable", 125.0, 300_000),
        Candidate("broken", 0.0, 300_000),
    )

    @Test
    fun `counts only usable rows in the total`() {
        val coverage = coverageAt(library, 170.0, ToleranceBand.CEILING)

        assertEquals(2, coverage.accepted)
        assertEquals(3, coverage.total) // the bpm-less row is not counted at all
        assertEquals(600_000L, coverage.stretchedMs)
        assertEquals(2.0 / 3.0, coverage.fraction, 1e-12)
    }

    @Test
    fun `a sqrt two band accepts everything reachable`() {
        val coverage = coverageAt(library, 170.0, ToleranceBand.EVERYTHING)

        assertEquals(3, coverage.accepted)
        assertEquals(1.0, coverage.fraction, 1e-12)
    }

    @Test
    fun `an empty library has zero coverage rather than dividing by zero`() {
        val coverage = coverageAt(emptyList<Candidate<String>>(), 170.0, ToleranceBand.CEILING)

        assertEquals(0, coverage.accepted)
        assertEquals(0, coverage.total)
        assertEquals(0.0, coverage.fraction)
    }

    /**
     * The measurement that makes a cadence suggestion possible: sweep the cadence
     * and see where the library actually lives.
     */
    @Test
    fun `coverage varies with cadence`() {
        val cluster = (120..130).map { Candidate("t$it", it.toDouble(), 300_000) }

        val awkward = coverageAt(cluster, 170.0, ToleranceBand.CEILING)
        val better = coverageAt(cluster, 250.0, ToleranceBand.CEILING)

        assertEquals(0, awkward.accepted, "a 170 spm cadence cannot use the 120-130 cluster")
        assertTrue(better.accepted > 0, "the same cluster is reachable from other cadences")
    }

    @Test
    fun `stretched duration reflects playback speed`() {
        val sped = listOf(Candidate("fast", 160.0, 330_000)) // speed 1.1 at 176 spm

        val coverage = coverageAt(sped, 176.0, ToleranceBand.CEILING)

        assertEquals(1, coverage.accepted)
        assertEquals(300_000L, coverage.stretchedMs)
    }
}
