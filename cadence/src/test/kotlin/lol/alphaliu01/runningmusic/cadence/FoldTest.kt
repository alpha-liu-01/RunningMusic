package lol.alphaliu01.runningmusic.cadence

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldTest {

    @ParameterizedTest(name = "{0} bpm at {1} spm folds to k={2}, speed={3}")
    @CsvSource(
        // A track already at cadence needs no stretch.
        "170, 170, 0, 1.0",
        // Half cadence is a perfect two-steps-per-beat match.
        "85,  170, 1, 1.0",
        // Just inside the k=0 window, a mild speed-up.
        "160, 176, 0, 1.1",
        // Just inside the k=1 window, a mild slow-down.
        "88,  170, 1, 0.9659090909090909",
    )
    fun `folds tempo onto cadence`(bpm: Double, cadence: Double, k: Int, speed: Double) {
        val fold = fold(bpm, cadence)
        assertEquals(k, fold.exponent)
        assertEquals(speed, fold.speed, 1e-12)
        assertFalse(fold.wasClamped)
    }

    @Test
    fun `steps per beat is two to the power of the exponent`() {
        assertEquals(1.0, fold(170.0, 170.0).stepsPerBeat)
        assertEquals(2.0, fold(85.0, 170.0).stepsPerBeat)
        assertEquals(0.5, fold(250.0, 125.0).stepsPerBeat)
    }

    /**
     * The same three gaits said the way a person would say them, because "0.5
     * steps per beat" is not a sentence anybody means.
     */
    @Test
    fun `the gait reads as a whole-number ratio`() {
        assertEquals(1 to 1, fold(170.0, 170.0).stepsToBeats)
        assertEquals(2 to 1, fold(85.0, 170.0).stepsToBeats)
        assertEquals(1 to 2, fold(250.0, 125.0).stepsToBeats)
    }

    @Test
    fun `log deviation is signed and absolute deviation is not`() {
        val fast = fold(160.0, 176.0) // speed 1.1, must speed up
        val slow = fold(88.0, 170.0)  // speed 0.966, must slow down

        assertTrue(fast.logDeviation > 0.0)
        assertTrue(slow.logDeviation < 0.0)
        assertEquals(abs(fast.logDeviation), fast.absLogDeviation, 1e-15)
        assertEquals(abs(slow.logDeviation), slow.absLogDeviation, 1e-15)
    }

    /**
     * The bound that makes the whole scheme work: because k is rounded, no track
     * is ever more than half an octave from its nearest multiple of the cadence.
     *
     * Asserted against the *raw* exponent, which is where the guarantee lives.
     */
    @ParameterizedTest
    @ValueSource(doubles = [150.0, 160.0, 170.0, 175.0, 180.0, 190.0])
    fun `unclamped residual never escapes half an octave`(cadence: Double) {
        var bpm = 30.0
        while (bpm <= 300.0) {
            val fold = fold(bpm, cadence)
            val unclampedSpeed = cadence / (bpm * 2.0.pow(fold.rawExponent))

            assertTrue(
                unclampedSpeed >= 1.0 / MAX_RESIDUAL - 1e-12 && unclampedSpeed <= MAX_RESIDUAL + 1e-12,
                "$bpm bpm at $cadence spm gave unclamped speed $unclampedSpeed, outside " +
                    "[${1.0 / MAX_RESIDUAL}, $MAX_RESIDUAL]",
            )
            bpm += 0.25
        }
    }

    /**
     * The companion to the test above. Clamping k deliberately breaks the bound,
     * and that is the mechanism that rejects nonsense rather than a defect.
     */
    @ParameterizedTest(name = "{0} bpm at 170 spm clamps and escapes the residual bound")
    @CsvSource(
        "40,  2",   // a mis-detected ambient track: nearest match is four steps per beat
        "800, -2",  // faster than any real music: nearest match is one step per four beats
    )
    fun `clamping lets speed escape the residual bound`(bpm: Double, expectedRaw: Int) {
        val fold = fold(bpm, 170.0)

        assertEquals(expectedRaw, fold.rawExponent)
        assertTrue(fold.wasClamped)
        assertTrue(fold.exponent in REACHABLE_EXPONENTS)
        assertTrue(
            fold.speed > MAX_RESIDUAL || fold.speed < 1.0 / MAX_RESIDUAL,
            "expected $bpm bpm to clamp to an unusable speed, got ${fold.speed}",
        )
    }

    /**
     * A track exactly between two octaves, equidistant from both. This is the
     * worst case of the fold and the reason folding alone is not enough.
     */
    @Test
    fun `a tempo midway between octaves folds to the slower option`() {
        val cadence = 170.0
        val bpm = cadence / MAX_RESIDUAL // 120.208..., the worst case

        val fold = fold(bpm, cadence)

        assertEquals(1, fold.exponent)
        assertEquals(1.0 / MAX_RESIDUAL, fold.speed, 1e-12)
        assertEquals(0.5, fold.absLogDeviation, 1e-12)
        assertFalse(fold.wasClamped)
    }

    /**
     * Two facts about the rounding, pinned because they are easy to assume wrong.
     *
     * `kotlin.math.round` is half-to-even, the same rule as `Math.rint`, not the
     * half-away-from-zero of `Math.round`. And the worst-case tempo is not an
     * exact tie anyway: `log2(sqrt(2))` lands a single ulp above 0.5, so it folds
     * to the higher exponent whichever rule is in force.
     *
     * The clamp in [fold], not the rounding, is what keeps k usable.
     */
    @Test
    fun `rounding at the midpoint is half-to-even and the midpoint is not an exact tie`() {
        assertEquals(0.0, round(0.5))
        assertEquals(round(0.5), Math.rint(0.5))
        assertTrue(log2(MAX_RESIDUAL) > 0.5)
        assertEquals(1.0, round(log2(MAX_RESIDUAL)))
    }

    @ParameterizedTest
    @CsvSource("0, 170", "-120, 170", "120, 0", "120, -170")
    fun `rejects non-positive input`(bpm: Double, cadence: Double) {
        assertFailsWith<IllegalArgumentException> { fold(bpm, cadence) }
    }

    @Test
    fun `rejects non-finite input`() {
        assertFailsWith<IllegalArgumentException> { fold(Double.NaN, 170.0) }
        assertFailsWith<IllegalArgumentException> { fold(Double.POSITIVE_INFINITY, 170.0) }
        assertFailsWith<IllegalArgumentException> { fold(120.0, Double.NaN) }
    }
}

class FoldAtTest {

    @Test
    fun `agrees with fold when given the exponent fold chose`() {
        for (bpm in listOf(85.0, 90.0, 125.0, 170.0, 178.0)) {
            val folded = fold(bpm, 170.0)
            assertEquals(folded.speed, foldAt(bpm, 170.0, folded.exponent), 1e-12)
        }
    }

    /**
     * The reason this function exists. An 88 bpm track at 170 spm runs at two
     * steps per beat; drifting the target up to 178 must speed it up a little,
     * not reinterpret it as one step per beat and double it.
     */
    @Test
    fun `holding the exponent keeps a drifting target from flipping the octave`() {
        val original = fold(88.0, 170.0)
        assertEquals(1, original.exponent)

        val drifted = foldAt(88.0, 178.0, original.exponent)

        assertEquals(178.0 / 176.0, drifted, 1e-12)
        assertTrue(drifted < 1.02)
    }

    /**
     * Held far enough from its own best exponent, the speed leaves the residual
     * bound entirely. That is intended, and is why callers clamp.
     */
    @Test
    fun `a held exponent is not bounded by the residual`() {
        val speed = foldAt(200.0, 170.0, 1)

        assertEquals(0.425, speed, 1e-12)
        assertTrue(speed < 1.0 / MAX_RESIDUAL)
        assertEquals(ToleranceBand.DEFAULT.minSpeed, ToleranceBand.DEFAULT.clamp(speed), 1e-12)
    }

    @Test
    fun `rejects an exponent no runner can use`() {
        assertFailsWith<IllegalArgumentException> { foldAt(120.0, 170.0, -2) }
        assertFailsWith<IllegalArgumentException> { foldAt(120.0, 170.0, 2) }
    }

    /**
     * The fold that the old 0..1 range could not express, and the reason every
     * cadence below about 100 spm matched nothing at all.
     *
     * A 125 bpm track is the single most common tempo in pop and EDM, and at one
     * step every two beats it carries a 62.5 spm walk with no stretch whatsoever.
     */
    @Test
    fun `a walking cadence reaches the biggest tempo cluster in the library`() {
        val fold = fold(125.0, 62.5)

        assertEquals(-1, fold.exponent)
        assertFalse(fold.wasClamped)
        assertEquals(1.0, fold.speed, 1e-12)
    }

    /**
     * The invariant that made the bug visible: halving a target cadence cannot
     * lose tracks, because every track reachable at k is reachable at k - 1 one
     * octave down. With the exponents clamped to 0..1 this failed outright —
     * 140 spm found tracks and 70 spm found none of them.
     */
    @ParameterizedTest
    @ValueSource(doubles = [140.0, 170.0, 180.0, 200.0])
    fun `halving the cadence keeps everything the faster cadence accepted`(cadence: Double) {
        var bpm = 60.0
        while (bpm <= 220.0) {
            val fast = fold(bpm, cadence)
            if (!fast.wasClamped && ToleranceBand.DEFAULT.accepts(fast)) {
                val half = fold(bpm, cadence / 2)
                assertTrue(
                    !half.wasClamped && ToleranceBand.DEFAULT.accepts(half),
                    "$bpm bpm fits ${cadence.toInt()} spm but not ${(cadence / 2).toInt()} spm",
                )
                assertEquals(fast.speed, half.speed, 1e-12)
            }
            bpm += 0.25
        }
    }

    @ParameterizedTest
    @CsvSource("0, 170", "-120, 170", "120, 0", "120, -170")
    fun `rejects non-positive input`(bpm: Double, cadence: Double) {
        assertFailsWith<IllegalArgumentException> { foldAt(bpm, cadence, 0) }
    }
}
