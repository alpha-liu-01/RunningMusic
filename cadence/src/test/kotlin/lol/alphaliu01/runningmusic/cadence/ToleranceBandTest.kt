package lol.alphaliu01.runningmusic.cadence

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToleranceBandTest {

    /**
     * The most important test in this module.
     *
     * 120-130 bpm is the single largest tempo cluster in pop and EDM, and none of
     * it is reachable from a 170 spm cadence: 170 sits at almost exactly
     * `120 * sqrt(2)`, the worst case of the fold. If this test ever starts
     * failing because someone widened the ceiling, they have bought that cluster
     * at the price of audibly mangled playback.
     */
    @ParameterizedTest
    @ValueSource(doubles = [120.0, 122.0, 125.0, 128.0, 130.0])
    fun `the 120 to 130 bpm cluster is unreachable at 170 spm`(bpm: Double) {
        val fold = fold(bpm, 170.0)

        assertFalse(
            ToleranceBand.DEFAULT.accepts(fold),
            "$bpm bpm folded to speed ${fold.speed}, which the 1.15 ceiling should reject",
        )
        assertTrue(fold.absLogDeviation > 0.35, "expected a near-worst-case fold, got $fold")
    }

    /**
     * Coverage is a lumpy function of cadence, not a smooth one. 180 spm halves
     * to 90, sitting on a real tempo cluster; 170 halves to 85, in the trough
     * between the 80 and 90 clusters.
     */
    @Test
    fun `coverage depends on where half the cadence lands`() {
        val onAPeak = fold(90.0, 180.0)
        assertEquals(1.0, onAPeak.speed, 1e-12)
        assertTrue(ToleranceBand.DEFAULT.accepts(onAPeak))

        // The same track is a worse fit at 170, though still comfortably usable.
        val inATrough = fold(90.0, 170.0)
        assertTrue(inATrough.absLogDeviation > onAPeak.absLogDeviation)
        assertTrue(ToleranceBand.DEFAULT.accepts(inATrough))
    }

    /**
     * At `r = sqrt(2)` the accepted windows meet, so the band tiles the tempo
     * range and the filter stops filtering.
     */
    @ParameterizedTest
    @ValueSource(doubles = [61.0, 75.0, 85.0, 100.0, 120.2, 125.0, 150.0, 170.0, 200.0, 239.0])
    fun `a sqrt two band accepts every reachable tempo`(bpm: Double) {
        val fold = fold(bpm, 170.0)
        assertFalse(fold.wasClamped, "$bpm bpm should be reachable at 170 spm")
        assertTrue(
            ToleranceBand.EVERYTHING.accepts(fold),
            "$bpm bpm folded to speed ${fold.speed}, which a sqrt(2) band should accept",
        )
    }

    /**
     * One window per reachable exponent, in exponent order. The k = -1 window
     * sits above the cadence rather than below it: stepping every other beat
     * needs a track at twice the tempo.
     */
    @Test
    fun `bands for a cadence are one window per reachable exponent`() {
        val bands = REACHABLE_EXPONENTS.zip(ToleranceBand.symmetric(1.2).bandsFor(170.0)).toMap()

        assertEquals(REACHABLE_EXPONENTS.count(), bands.size)

        assertEquals(340.0 / 1.2, bands.getValue(-1).start, 1e-9)   // 283.33
        assertEquals(340.0 * 1.2, bands.getValue(-1).endInclusive, 1e-9) // 408.0
        assertEquals(170.0 / 1.2, bands.getValue(0).start, 1e-9)    // 141.67
        assertEquals(170.0 * 1.2, bands.getValue(0).endInclusive, 1e-9)  // 204.0
        assertEquals(85.0 / 1.2, bands.getValue(1).start, 1e-9)     // 70.83
        assertEquals(85.0 * 1.2, bands.getValue(1).endInclusive, 1e-9)   // 102.0
    }

    /**
     * The windows returned by [ToleranceBand.bandsFor] must agree with what
     * [ToleranceBand.accepts] actually does, or the two halves of the design have
     * drifted apart.
     */
    @Test
    fun `band inversion round-trips against acceptance`() {
        val band = ToleranceBand.symmetric(1.2)
        val cadence = 170.0

        for (window in band.bandsFor(cadence)) {
            // Just inside either edge, and the middle. The edges themselves are
            // left alone: they land on the bound to within a rounding error, so
            // asserting them would be testing floating point, not the design.
            val inside = listOf(
                window.start * 1.001,
                window.endInclusive * 0.999,
                (window.start + window.endInclusive) / 2,
            )
            for (bpm in inside) {
                assertTrue(
                    band.accepts(fold(bpm, cadence)),
                    "$bpm bpm is inside $window but was rejected",
                )
            }
            assertFalse(band.accepts(fold(window.start * 0.97, cadence)))
            assertFalse(band.accepts(fold(window.endInclusive * 1.03, cadence)))
        }

        // And the gap between the two windows really is a gap.
        assertFalse(band.accepts(fold(120.0, cadence)))
    }

    /**
     * The two bounds have to be genuinely independent, otherwise the +12%/-8%
     * hypothesis cannot be tested without reshaping every signature.
     */
    @Test
    fun `an asymmetric band is not equivalent to any symmetric one`() {
        val asymmetric = ToleranceBand.ASYMMETRIC_HYPOTHESIS // 1.12 up, 1.08 down
        val cadence = 170.0

        val mustSpeedUp = fold(cadence / 1.10, cadence)   // speed 1.10
        val mustSlowDown = fold(cadence * 1.10, cadence)  // speed 0.909

        // Happy to run a track 10% fast...
        assertTrue(asymmetric.accepts(mustSpeedUp))
        assertFalse(ToleranceBand.symmetric(1.08).accepts(mustSpeedUp))

        // ...but not to drag it 10% slow, which a symmetric band of the wider
        // bound would have allowed.
        assertFalse(asymmetric.accepts(mustSlowDown))
        assertTrue(ToleranceBand.symmetric(1.12).accepts(mustSlowDown))
    }

    @Test
    fun `min speed and widest describe the band`() {
        val band = ToleranceBand(maxSpeedUp = 1.12, maxSlowDown = 1.08)
        assertEquals(1.0 / 1.08, band.minSpeed, 1e-12)
        assertEquals(1.12, band.widest)
    }

    @Test
    fun `rejects bounds that would speed nothing up or slow nothing down`() {
        assertFailsWith<IllegalArgumentException> { ToleranceBand(0.9, 1.1) }
        assertFailsWith<IllegalArgumentException> { ToleranceBand(1.1, 0.9) }
        assertFailsWith<IllegalArgumentException> { ToleranceBand(Double.NaN, 1.1) }
    }

    @Test
    fun `unity band accepts only an exact match`() {
        val band = ToleranceBand.symmetric(1.0)
        assertTrue(band.accepts(fold(170.0, 170.0)))
        assertFalse(band.accepts(fold(169.0, 170.0)))
    }
}
