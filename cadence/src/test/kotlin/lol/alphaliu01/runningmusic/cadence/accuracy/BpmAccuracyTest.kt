package lol.alphaliu01.runningmusic.cadence.accuracy

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BpmAccuracyTest {

    @Test
    fun `an exact estimate is zero octaves away`() {
        assertEquals(0.0, octaveDistance(170.0, 170.0), 1e-12)
    }

    @ParameterizedTest(name = "{0} bpm against a truth of 170 is an octave error, not an error")
    @CsvSource("85.0", "42.5", "340.0", "680.0")
    fun `octaves of the truth score as correct`(estimate: Double) {
        assertEquals(0.0, octaveDistance(estimate, 170.0), 1e-12)
        assertTrue(scoreOf(estimate, 170.0).octaveCorrect)
    }

    @Test
    fun `the worst possible estimate is half an octave away`() {
        // 170 / sqrt(2) sits exactly between two octaves of the truth, which is
        // the furthest anything can be once octaves stop counting.
        val worst = 170.0 / 1.4142135623730951

        assertEquals(0.5, octaveDistance(worst, 170.0), 1e-9)
        assertFalse(scoreOf(worst, 170.0).octaveCorrect)
    }

    @Test
    fun `the strict metric does not forgive octaves`() {
        val scored = scoreOf(estimate = 85.0, truth = 170.0)

        assertTrue(scored.octaveCorrect)
        assertFalse(scored.strictCorrect)
    }

    @Test
    fun `an estimate just inside four percent counts, just outside does not`() {
        assertTrue(scoreOf(170.0 * 1.03, 170.0).octaveCorrect)
        assertFalse(scoreOf(170.0 * 1.05, 170.0).octaveCorrect)
    }

    @Test
    fun `no estimate is unknown rather than infinitely wrong`() {
        val scored = scoreOf(estimate = 0.0, truth = 170.0)

        assertTrue(scored.unknown)
        assertFalse(scored.octaveCorrect)
        assertFalse(scored.playedCorrectly)
    }

    @Test
    fun `halving the tempo plays the track identically`() {
        // The whole reason the octave-agnostic metric is the right one: at a
        // cadence of 170, an 85 bpm track is played at one speed and a 170 bpm
        // track at the same speed, because the fold absorbs the difference.
        assertEquals(0.0, playbackSpeedError(85.0, 170.0, targetCadence = 170.0), 1e-12)
    }

    @Test
    fun `quadrupling the tempo is octave-correct but plays wrongly`() {
        // The case that justifies keeping a third metric, and keeping it built
        // on the shipped fold. Four steps per beat is out of reach, so the fold
        // clamps and the two tempos end up at different speeds even though they
        // are an exact power of two apart.
        val scored = scoreOf(estimate = 680.0, truth = 170.0)

        assertTrue(scored.octaveCorrect)
        assertFalse(scored.playedCorrectly)
    }

    @Test
    fun `a wrong tempo plays audibly wrongly`() {
        // 125 against a truth of 170 is the 120-130 bpm cluster landing at the
        // worst case of the fold.
        val scored = scoreOf(estimate = 125.0, truth = 170.0)

        assertFalse(scored.octaveCorrect)
        assertFalse(scored.playedCorrectly)
        assertTrue(scored.playbackSpeedError > 0.4)
    }

    @Test
    fun `accuracy counts unknowns against the total`() {
        // A detector that answers once and gets it right is not 100% accurate,
        // it is quiet. The confidence sweep is where that trade is made openly.
        val scored = listOf(
            scoreOf(170.0, 170.0),
            scoreOf(0.0, 170.0),
            scoreOf(0.0, 170.0),
            scoreOf(0.0, 170.0)
        )

        val accuracy = scored.accuracy()

        assertEquals(4, accuracy.total)
        assertEquals(3, accuracy.unknown)
        assertEquals(0.25, accuracy.octaveAccuracy, 1e-12)
    }

    @Test
    fun `the median ignores tracks with no estimate`() {
        val scored = listOf(
            scoreOf(170.0, 170.0),
            scoreOf(85.0, 170.0),
            scoreOf(0.0, 170.0)
        )

        assertEquals(0.0, scored.accuracy().medianOctaveDistance, 1e-12)
    }

    @Test
    fun `an empty set has no median rather than a median of zero`() {
        assertTrue(emptyList<Scored>().accuracy().medianOctaveDistance.isNaN())
    }

    private fun scoreOf(estimate: Double, truth: Double) = score(
        corpus = "test",
        track = "track",
        variant = "click",
        truth = truth,
        estimate = estimate,
        confidence = 1.0,
        beats = 60,
        targetCadence = 170.0
    )
}
