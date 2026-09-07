package lol.alphaliu01.runningmusic.cadence.accuracy

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ConfidenceSweepTest {

    @Test
    fun `raising the threshold trades coverage for accuracy`() {
        // Wrong answers carry low confidence, right ones carry high, which is
        // the arrangement that makes a threshold worth having at all.
        val scored = listOf(
            wrong(confidence = 0.1),
            wrong(confidence = 0.2),
            right(confidence = 0.8),
            right(confidence = 0.9)
        )

        val sweep = scored.sweepConfidence()
        val recommended = assertNotNull(sweep.recommended)

        assertEquals(0.8, recommended.threshold, 1e-12)
        assertEquals(2, recommended.retained)
        assertEquals(1.0, recommended.accuracy, 1e-12)
        assertEquals(0.5, recommended.coverage, 1e-12)
    }

    @Test
    fun `the lowest threshold that reaches the target is the one recommended`() {
        // 0.5 and 0.8 are both perfectly accurate; 0.5 keeps more tracks, so it
        // is the better trade and the one to report.
        val scored = listOf(
            wrong(confidence = 0.1),
            right(confidence = 0.5),
            right(confidence = 0.8)
        )

        val recommended = assertNotNull(scored.sweepConfidence().recommended)

        assertEquals(0.5, recommended.threshold, 1e-12)
        assertEquals(2, recommended.retained)
    }

    @Test
    fun `accepting everything is always in the table`() {
        val scored = listOf(right(confidence = 0.7), wrong(confidence = 0.3))

        val baseline = scored.sweepConfidence().points.first()

        assertEquals(0.0, baseline.threshold, 1e-12)
        assertEquals(2, baseline.retained)
        assertEquals(0.5, baseline.accuracy, 1e-12)
    }

    @Test
    fun `confidence that does not separate right from wrong recommends nothing`() {
        // A real finding rather than a reason to pick a threshold anyway: here
        // the wrong answer is the most confident one.
        val scored = listOf(
            right(confidence = 0.5),
            wrong(confidence = 0.9),
            wrong(confidence = 0.9)
        )

        val sweep = scored.sweepConfidence()

        assertNull(sweep.recommended)
        assertTrue(sweep.noUsableThreshold)
    }

    @Test
    fun `tracks with no estimate are left out of the thresholding`() {
        // They have no tempo to accept or reject, but they still count against
        // the coverage the app would actually get.
        val scored = listOf(
            right(confidence = 0.9),
            unknown()
        )

        val point = scored.sweepConfidence().points.single { it.threshold == 0.0 }

        assertEquals(1, point.retained)
        assertEquals(2, point.total)
        assertEquals(0.5, point.coverage, 1e-12)
        assertEquals(1.0, point.accuracy, 1e-12)
    }

    @Test
    fun `a corpus with no answers at all recommends nothing`() {
        val sweep = listOf(unknown(), unknown()).sweepConfidence()

        assertNull(sweep.recommended)
        assertEquals(1, sweep.points.size)
        assertEquals(0.0, sweep.points.single().threshold, 1e-12)
    }

    @Test
    fun `the display table is thinned but keeps the recommendation and the ends`() {
        val scored = (1..200).map { i ->
            if (i < 50) wrong(confidence = i / 1000.0) else right(confidence = i / 1000.0)
        }

        val sweep = scored.sweepConfidence()
        val shown = sweep.forDisplay(limit = 10)

        assertTrue(shown.size <= 12, "expected a short table, got ${shown.size} rows")
        assertTrue(shown.contains(sweep.recommended))
        assertEquals(0.0, shown.first().threshold, 1e-12)
        assertEquals(sweep.points.last(), shown.last())
        assertEquals(shown.sortedBy { it.threshold }, shown)
    }

    @Test
    fun `a threshold that buys nothing is reported as such, not as a recommendation of zero`() {
        // Everything is already accurate enough, which says as much about the
        // material as about the estimator and should not be dressed up as a
        // threshold worth applying.
        val sweep = listOf(right(confidence = 0.2), right(confidence = 0.9)).sweepConfidence()

        assertEquals(0.0, assertNotNull(sweep.recommended).threshold, 1e-12)
        assertTrue(sweep.thresholdBuysNothing)
    }

    @Test
    fun `a threshold that does something is not reported as buying nothing`() {
        val sweep = listOf(wrong(confidence = 0.1), right(confidence = 0.9)).sweepConfidence()

        assertTrue(!sweep.thresholdBuysNothing)
    }

    @Test
    fun `confidence that runs the wrong way is called out`() {
        // The failure mode a threshold cannot fix: the estimator is most sure
        // exactly when it is wrong.
        val sweep = listOf(
            right(confidence = 0.2),
            wrong(confidence = 0.9)
        ).sweepConfidence()

        assertEquals(0.2, sweep.meanConfidenceWhenRight, 1e-12)
        assertEquals(0.9, sweep.meanConfidenceWhenWrong, 1e-12)
        assertTrue(sweep.confidenceIsUninformative)
    }

    @Test
    fun `confidence that separates right from wrong is not called out`() {
        val sweep = listOf(
            right(confidence = 0.9),
            wrong(confidence = 0.2)
        ).sweepConfidence()

        assertTrue(!sweep.confidenceIsUninformative)
    }

    @Test
    fun `a corpus with nothing wrong in it cannot say whether confidence means anything`() {
        val sweep = listOf(right(confidence = 0.5)).sweepConfidence()

        assertTrue(sweep.meanConfidenceWhenWrong.isNaN())
        assertTrue(!sweep.confidenceIsUninformative)
    }

    @Test
    fun `a short sweep is shown whole`() {
        val scored = listOf(right(confidence = 0.4), right(confidence = 0.6))

        val sweep = scored.sweepConfidence()

        assertEquals(sweep.points, sweep.forDisplay(limit = 20))
    }

    private fun right(confidence: Double) = scored(estimate = 170.0, confidence = confidence)

    private fun wrong(confidence: Double) = scored(estimate = 125.0, confidence = confidence)

    private fun unknown() = scored(estimate = 0.0, confidence = 0.0)

    private fun scored(estimate: Double, confidence: Double) = score(
        corpus = "test",
        track = "track",
        variant = "click",
        truth = 170.0,
        estimate = estimate,
        confidence = confidence,
        beats = 60,
        targetCadence = 170.0
    )
}
