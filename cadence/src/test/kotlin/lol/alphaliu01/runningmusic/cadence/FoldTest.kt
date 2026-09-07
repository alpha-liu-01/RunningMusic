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
        assertEquals(1, fold(170.0, 170.0).stepsPerBeat)
        assertEquals(2, fold(85.0, 170.0).stepsPerBeat)
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
        "400, -1",  // faster than any real music: nearest match is one step per two beats
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
