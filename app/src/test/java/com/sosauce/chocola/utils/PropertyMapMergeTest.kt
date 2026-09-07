package com.sosauce.chocola.utils

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The regression test for a live data-loss bug.
 *
 * Saving the metadata editor used to write a fresh property map built from the
 * eight fields the screen shows, which meant every other tag the file carried was
 * gone the moment anyone corrected a typo in a title.
 */
class PropertyMapMergeTest {

    @Test
    fun `a tag the editor does not know about survives a save`() {
        val original = propertyMap(
            "TITLE" to "Old",
            "BPM" to "128",
            "COMPOSER" to "Someone",
            "REPLAYGAIN_TRACK_GAIN" to "-6.5 dB"
        )

        val merged = original.mergedWith(metadata(title = "New"))

        assertContentEquals(arrayOf("128"), merged["BPM"])
        assertContentEquals(arrayOf("Someone"), merged["COMPOSER"])
        assertContentEquals(arrayOf("-6.5 dB"), merged["REPLAYGAIN_TRACK_GAIN"])
    }

    @Test
    fun `an edited field is written`() {
        val merged = propertyMap("TITLE" to "Old").mergedWith(metadata(title = "New"))

        assertContentEquals(arrayOf("New"), merged["TITLE"])
    }

    @Test
    fun `clearing a field removes the tag rather than blanking it`() {
        val merged = propertyMap("TITLE" to "Old", "GENRE" to "Pop")
            .mergedWith(metadata(title = "New", genre = null))

        assertFalse("GENRE" in merged)
    }

    @Test
    fun `a multi-valued field is split on commas`() {
        val merged = propertyMap().mergedWith(metadata(artist = "A, B"))

        assertContentEquals(arrayOf("A", "B"), merged["ARTIST"])
    }

    @Test
    fun `the original map is not modified`() {
        val original = propertyMap("TITLE" to "Old")

        original.mergedWith(metadata(title = "New"))

        assertContentEquals(arrayOf("Old"), original["TITLE"])
    }

    @Test
    fun `an empty edit set leaves an untouched file with only its own tags`() {
        val original = propertyMap("BPM" to "128")

        val merged = original.mergedWith(metadata())

        assertEquals(1, merged.size)
        assertTrue("BPM" in merged)
    }

    private fun propertyMap(vararg entries: Pair<String, String>) =
        HashMap(entries.associate { (key, value) -> key to arrayOf(value) })

    private fun metadata(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        trackNumber: String? = null,
        discNumber: String? = null,
        date: String? = null,
        genre: String? = null,
        lyrics: String? = null
    ) = AudioFileMetadata(
        title = title,
        artist = artist,
        album = album,
        trackNumber = trackNumber,
        discNumber = discNumber,
        date = date,
        genre = genre,
        lyrics = lyrics
    )
}
