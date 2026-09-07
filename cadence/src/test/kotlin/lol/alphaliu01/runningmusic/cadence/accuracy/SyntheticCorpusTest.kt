package lol.alphaliu01.runningmusic.cadence.accuracy

import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SyntheticCorpusTest {

    @Test
    fun `the corpus is seventeen tempos across five characters`() {
        val corpus = syntheticCorpus()

        assertEquals(17, SYNTHETIC_TEMPOS.size)
        assertEquals(5, Variant.entries.size)
        assertEquals(85, corpus.size)
        assertEquals(85, corpus.map { it.name }.distinct().size)
    }

    @Test
    fun `the awkward tempos are in it`() {
        // 85 and 170 are the classic halving pair, and 120 to 130 is the cluster
        // where a small error changes which playback speed a track lands on.
        assertTrue(SYNTHETIC_TEMPOS.containsAll(listOf(85.0, 170.0, 120.0, 125.0, 128.0, 130.0)))
    }

    @Test
    fun `the same track renders identically every time`() {
        // The property that makes two runs comparable at all.
        val track = syntheticCorpus().first { it.name == "click-120" }

        assertContentEquals(track.render(durationSeconds = 2), track.render(durationSeconds = 2))
    }

    @Test
    fun `the order is fixed, so results sort the same way run to run`() {
        assertEquals(syntheticCorpus().map { it.name }, syntheticCorpus().map { it.name })
    }

    @Test
    fun `a rendered track is the length it says it is`() {
        val track = syntheticCorpus(sampleRate = 22050).first()

        assertEquals(22050 * 3, track.render(durationSeconds = 3).size)
    }

    @Test
    fun `every variant carries the noise floor plan 6 showed is necessary`() {
        // Digital silence between clicks is the pathological case: aubio throws
        // away beats predicted inside a hop below -90 dBFS, and the tempo comes
        // out several times too slow.
        for (variant in Variant.entries) {
            val samples = SyntheticTrack("t", variant, 60.0, 44100).render(durationSeconds = 2)

            // Half a second in, well clear of the click on beat one.
            val quiet = samples.slice(30_000 until 40_000)

            assertTrue(
                quiet.any { it != 0.0f },
                "$variant has digital silence between beats"
            )
            assertTrue(
                quiet.all { abs(it) < 0.2f },
                "$variant's floor is not quiet"
            )
        }
    }

    @Test
    fun `clicks land on the beat`() {
        val samples = SyntheticTrack("t", Variant.CLICK, 120.0, 44100).render(durationSeconds = 3)

        // 120 bpm is a beat every half second.
        for (beat in 0..5) {
            val at = beat * 22_050
            val loudest = samples.slice(at until at + 1000).maxOf { abs(it) }

            assertTrue(loudest > 0.5f, "no click at beat $beat")
        }
    }

    @Test
    fun `sparse leaves beats two and four empty`() {
        // This is what invites the half-tempo reading, and the reason the
        // variant exists.
        val samples = SyntheticTrack("t", Variant.SPARSE, 120.0, 44100).render(durationSeconds = 3)

        assertTrue(samples.slice(0 until 1000).maxOf { abs(it) } > 0.5f, "beat 1 is empty")
        assertTrue(samples.slice(22_050 until 23_050).maxOf { abs(it) } < 0.1f, "beat 2 is not empty")
        assertTrue(samples.slice(44_100 until 45_100).maxOf { abs(it) } > 0.5f, "beat 3 is empty")
    }

    @Test
    fun `swung puts its offbeat late rather than halfway`() {
        val samples = SyntheticTrack("t", Variant.SWUNG, 120.0, 44100).render(durationSeconds = 3)

        val halfway = samples.slice(11_025 until 12_025).maxOf { abs(it) }
        val twoThirds = samples.slice(14_700 until 15_700).maxOf { abs(it) }

        assertTrue(twoThirds > halfway, "the offbeat is not swung")
    }

    @Test
    fun `padded holds energy that is not the beat`() {
        val padded = SyntheticTrack("t", Variant.PADDED, 120.0, 44100).render(durationSeconds = 2)
        val plain = SyntheticTrack("t", Variant.CLICK, 120.0, 44100).render(durationSeconds = 2)

        val between = 30_000 until 40_000
        val paddedLevel = padded.slice(between).maxOf { abs(it) }
        val plainLevel = plain.slice(between).maxOf { abs(it) }

        assertTrue(paddedLevel > plainLevel * 5, "the pad is not audible between beats")
    }

    @Test
    fun `nothing clips`() {
        for (track in syntheticCorpus()) {
            val peak = track.render(durationSeconds = 2).maxOf { abs(it) }

            assertTrue(peak <= 1.0f, "${track.name} peaks at $peak")
        }
    }

    @Test
    fun `a track needs a positive tempo and a positive length`() {
        val track = SyntheticTrack("t", Variant.CLICK, 120.0, 44100)

        assertTrue(runCatching { track.render(durationSeconds = 0) }.isFailure)
        assertTrue(runCatching { track.copy(truthBpm = 0.0).render() }.isFailure)
        assertTrue(runCatching { track.copy(sampleRate = 0).render() }.isFailure)
    }
}
