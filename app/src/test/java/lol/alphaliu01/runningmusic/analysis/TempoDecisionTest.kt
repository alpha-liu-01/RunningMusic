package lol.alphaliu01.runningmusic.analysis

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_ANALYSIS
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_MANUAL
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_NONE
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_TAG
import lol.alphaliu01.runningmusic.library.TrackMetadata
import org.junit.jupiter.api.Test

class TempoDecisionTest {

    @Test
    fun `two windows that agree give a tempo`() {
        val verdict = decideTempo(estimate(128f, beats = 60), estimate(128f, beats = 58))

        assertEquals(128f, assertIs<TempoVerdict.Known>(verdict).bpm)
    }

    @Test
    fun `an octave apart is agreement, because it is the same music`() {
        // The whole reason agreement is measured in octaves. At any cadence the
        // fold turns 85 and 170 into the same playback speed, so calling this a
        // disagreement would throw away a track over a distinction the app
        // cannot hear.
        val verdict = decideTempo(estimate(85f, beats = 30), estimate(170f, beats = 60))

        assertIs<TempoVerdict.Known>(verdict)
    }

    @Test
    fun `windows that disagree give no tempo`() {
        val verdict = decideTempo(estimate(128f), estimate(97f))

        assertEquals(DISAGREED, assertIs<TempoVerdict.Unknown>(verdict).reason)
    }

    @Test
    fun `a small disagreement is still agreement`() {
        // Real recordings drift by a fraction of a percent, and a metric that
        // refused those would refuse almost everything.
        val verdict = decideTempo(estimate(128f), estimate(129f))

        assertIs<TempoVerdict.Known>(verdict)
    }

    @Test
    fun `the better supported of two agreeing answers is the one kept`() {
        // Both are usable; the window that saw more beats saw more evidence.
        val verdict = decideTempo(estimate(85f, beats = 20), estimate(170f, beats = 90))

        assertEquals(170f, assertIs<TempoVerdict.Known>(verdict).bpm)
    }

    @Test
    fun `one silent window is not agreement`() {
        // A half of a track with no beats says nothing about the half that had
        // them, so accepting the single answer would be exactly the unchecked
        // guess this function exists to prevent.
        val verdict = decideTempo(estimate(128f, beats = 60), TempoEstimate.UNKNOWN)

        assertEquals(ONE_WINDOW_SILENT, assertIs<TempoVerdict.Unknown>(verdict).reason)
        assertEquals(
            ONE_WINDOW_SILENT,
            assertIs<TempoVerdict.Unknown>(
                decideTempo(TempoEstimate.UNKNOWN, estimate(128f))
            ).reason
        )
    }

    @Test
    fun `neither window finding a beat gives no tempo`() {
        val verdict = decideTempo(TempoEstimate.UNKNOWN, TempoEstimate.UNKNOWN)

        assertEquals(NO_BEATS, assertIs<TempoVerdict.Unknown>(verdict).reason)
    }

    @Test
    fun `a track too short to split is accepted on one window`() {
        // Less evidence, but refusing short tracks outright would be a worse
        // answer than analysing them with what there is.
        val verdict = decideTempo(estimate(128f), second = null)

        assertEquals(128f, assertIs<TempoVerdict.Known>(verdict).bpm)
    }

    @Test
    fun `a short track with no beats still gives no tempo`() {
        val verdict = decideTempo(TempoEstimate.UNKNOWN, second = null)

        assertEquals(NO_BEATS, assertIs<TempoVerdict.Unknown>(verdict).reason)
    }

    @Test
    fun `confidence does not enter into it`() {
        // docs/private/BPM_ACCURACY.md found aubio's confidence anti-correlated
        // with correctness, so a wildly confident disagreement is still a
        // disagreement and a diffident agreement is still an agreement.
        assertIs<TempoVerdict.Unknown>(
            decideTempo(estimate(128f, confidence = 9f), estimate(97f, confidence = 9f))
        )
        assertIs<TempoVerdict.Known>(
            decideTempo(estimate(128f, confidence = 0f), estimate(128f, confidence = 0f))
        )
    }

    @Test
    fun `a track nobody has looked at needs analysis`() {
        assertTrue(needsAnalysis(existing = null, force = false))
        assertTrue(needsAnalysis(stored(source = BPM_SOURCE_NONE, analysedAt = null), force = false))
    }

    @Test
    fun `a track already analysed is left alone, tempo or not`() {
        // Including the failures. Re-decoding a file that could not be read last
        // time, every single run, forever, is the cost of not storing this.
        assertFalse(
            needsAnalysis(
                stored(bpm = 128f, source = BPM_SOURCE_ANALYSIS, analysedAt = 1L),
                force = false
            )
        )
        assertFalse(
            needsAnalysis(
                stored(bpm = null, source = BPM_SOURCE_ANALYSIS, analysedAt = 1L),
                force = false
            )
        )
    }

    @Test
    fun `a hand-entered tempo is never touched, even by a forced pass`() {
        // Somebody measured this. A detector does not get to overrule them.
        val manual = stored(bpm = 174f, source = BPM_SOURCE_MANUAL, analysedAt = 1L)

        assertFalse(needsAnalysis(manual, force = false))
        assertFalse(needsAnalysis(manual, force = true))
    }

    @Test
    fun `a forced pass revisits tagged and analysed tracks`() {
        assertTrue(
            needsAnalysis(stored(bpm = 128f, source = BPM_SOURCE_ANALYSIS, analysedAt = 1L), force = true)
        )
        assertTrue(
            needsAnalysis(stored(bpm = 128f, source = BPM_SOURCE_TAG, analysedAt = 1L), force = true)
        )
    }

    private fun estimate(bpm: Float, confidence: Float = 1.5f, beats: Int = 50) =
        TempoEstimate(bpm = bpm, confidence = confidence, beats = beats)

    private fun stored(bpm: Float? = null, source: String, analysedAt: Long?) = TrackMetadata(
        trackKey = "1024|track.mp3",
        bpm = bpm,
        bpmSource = source,
        analysedAt = analysedAt
    )
}
