package lol.alphaliu01.runningmusic.library

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class TrackKeyTest {

    @Test
    fun `a key is the size and then the file name`() {
        assertEquals("5242880|song.mp3", trackKey(5_242_880, "song.mp3"))
    }

    @Test
    fun `the same file reached by two different paths keys the same`() {
        // The whole point: a rescan that moves a track between folders, or the
        // same file seen once through MediaStore and once through SAF, must
        // land on one row.
        val fromMediaStore = trackKey(5_242_880, "song.mp3")
        val fromSaf = trackKey(5_242_880, "song.mp3")

        assertEquals(fromMediaStore, fromSaf)
    }

    @Test
    fun `a file name containing the separator cannot be confused with another file`() {
        // "a|b.mp3" at 12 bytes and "b.mp3" at 12 bytes named "a" would collide
        // under a naive join. Putting the size first keeps the encoding
        // injective, because a size is always digits.
        val awkward = trackKey(12, "a|b.mp3")
        val plain = trackKey(12, "b.mp3")

        assertEquals("12|a|b.mp3", awkward)
        assertNotEquals(awkward, plain)
    }

    @Test
    fun `size discriminates two files that share a name`() {
        assertNotEquals(trackKey(100, "track.mp3"), trackKey(101, "track.mp3"))
    }

    @Test
    fun `non-ascii file names survive intact`() {
        assertEquals("42|ひとり.flac", trackKey(42, "ひとり.flac"))
    }

    @Test
    fun `case is significant because the filesystem treats it that way`() {
        assertNotEquals(trackKey(42, "Song.mp3"), trackKey(42, "song.mp3"))
    }

    @Test
    fun `a track with no file identity has no key`() {
        // QuickPlay builds a CuteTrack out of player metadata alone, with no
        // size and no name. Nothing may be stored against it.
        assertNull(trackKey(0, "song.mp3"))
        assertNull(trackKey(5_242_880, ""))
        assertNull(trackKey(0, ""))
    }

    @Test
    fun `a negative size is treated as no identity rather than trusted`() {
        assertNull(trackKey(-1, "song.mp3"))
    }
}
