package lol.alphaliu01.runningmusic.library

import com.sosauce.chocola.data.models.CuteTrack
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BpmSortTest {

    @Test
    fun `ascending puts the slowest track first`() {
        val library = listOf(track("fast", 180f), track("slow", 90f), track("mid", 130f))

        assertEquals(
            listOf("slow", "mid", "fast"),
            library.orderedByBpm(bpm(library), ascending = true).titles()
        )
    }

    @Test
    fun `descending puts the fastest track first`() {
        val library = listOf(track("fast", 180f), track("slow", 90f), track("mid", 130f))

        assertEquals(
            listOf("fast", "mid", "slow"),
            library.orderedByBpm(bpm(library), ascending = false).titles()
        )
    }

    @Test
    fun `un-analysed tracks sort last when ascending`() {
        val library = listOf(track("unknown", null), track("fast", 180f), track("slow", 90f))

        assertEquals(
            listOf("slow", "fast", "unknown"),
            library.orderedByBpm(bpm(library), ascending = true).titles()
        )
    }

    @Test
    fun `un-analysed tracks sort last when descending too`() {
        // The case that rules out "sort ascending then reverse the list": a
        // plain reversal would put the un-analysed track at the top.
        val library = listOf(track("unknown", null), track("fast", 180f), track("slow", 90f))

        assertEquals(
            listOf("fast", "slow", "unknown"),
            library.orderedByBpm(bpm(library), ascending = false).titles()
        )
    }

    @Test
    fun `tracks sharing a tempo keep the order they arrived in`() {
        // Which is the title ordering the MediaStore query already applied.
        val library = listOf(track("apple", 120f), track("banana", 120f), track("cherry", 120f))

        assertEquals(
            listOf("apple", "banana", "cherry"),
            library.orderedByBpm(bpm(library), ascending = true).titles()
        )
        assertEquals(
            listOf("apple", "banana", "cherry"),
            library.orderedByBpm(bpm(library), ascending = false).titles()
        )
    }

    @Test
    fun `un-analysed tracks keep the order they arrived in`() {
        val library = listOf(
            track("apple", null),
            track("known", 120f),
            track("banana", null),
            track("cherry", null)
        )

        assertEquals(
            listOf("known", "apple", "banana", "cherry"),
            library.orderedByBpm(bpm(library), ascending = true).titles()
        )
    }

    @Test
    fun `a track with no file identity counts as un-analysed`() {
        // What QuickPlay builds out of player metadata: no size, no file name,
        // so no durable key and nowhere for a tempo to have been stored.
        val quickPlay = CuteTrack(title = "quickplay")
        val library = listOf(quickPlay, track("slow", 90f))

        assertEquals(
            listOf("slow", "quickplay"),
            library.orderedByBpm(bpm(library), ascending = true).titles()
        )
        assertEquals(
            listOf("slow", "quickplay"),
            library.orderedByBpm(bpm(library), ascending = false).titles()
        )
    }

    @Test
    fun `a library where nothing has been analysed is left alone`() {
        val library = listOf(track("c", null), track("a", null), track("b", null))

        assertEquals(listOf("c", "a", "b"), library.orderedByBpm(emptyMap(), true).titles())
        assertEquals(listOf("c", "a", "b"), library.orderedByBpm(emptyMap(), false).titles())
    }

    @Test
    fun `an empty library sorts to an empty library`() {
        assertTrue(emptyList<CuteTrack>().orderedByBpm(emptyMap(), ascending = true).isEmpty())
    }

    @Test
    fun `fractional tempos are ordered by value, not by text`() {
        val library = listOf(track("ninety", 90f), track("hundred", 100.5f))

        assertEquals(
            listOf("ninety", "hundred"),
            library.orderedByBpm(bpm(library), ascending = true).titles()
        )
    }

    private fun List<CuteTrack>.titles() = map { it.title }

    /** Builds the lookup the scanner would hand in, skipping un-analysed rows. */
    private fun bpm(library: List<CuteTrack>) =
        library.mapNotNull { track ->
            val key = track.trackKey ?: return@mapNotNull null
            val value = tempos[track.title] ?: return@mapNotNull null

            key to value
        }.toMap()

    private val tempos = mutableMapOf<String, Float>()

    private fun track(title: String, bpm: Float?): CuteTrack {
        bpm?.let { tempos[title] = it }

        return CuteTrack(
            mediaId = title,
            title = title,
            // Distinct sizes keep the durable keys distinct.
            sizeBytes = 1_000L + title.hashCode().toLong().and(0xffff),
            fileName = "$title.mp3"
        )
    }
}
