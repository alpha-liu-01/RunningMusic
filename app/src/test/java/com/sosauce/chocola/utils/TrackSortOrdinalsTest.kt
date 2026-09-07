package com.sosauce.chocola.utils

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The track sort is stored in DataStore as a bare integer and read back as
 * `TrackSort.entries[sort]`, so the ordinals are a persistence format, not an
 * implementation detail. Reordering the enum would silently change what every
 * existing user has selected, with nothing to notice it at runtime.
 *
 * This pins the order so that becomes a build failure instead.
 */
class TrackSortOrdinalsTest {

    @Test
    fun `the persisted order of every entry is fixed`() {
        assertEquals(0, TrackSort.TITLE.ordinal)
        assertEquals(1, TrackSort.ARTIST.ordinal)
        assertEquals(2, TrackSort.ALBUM.ordinal)
        assertEquals(3, TrackSort.YEAR.ordinal)
        assertEquals(4, TrackSort.DATE_MODIFIED.ordinal)
        assertEquals(5, TrackSort.AS_ADDED.ordinal)
        assertEquals(6, TrackSort.BPM.ordinal)
    }

    @Test
    fun `no entry has been added without pinning it here`() {
        assertEquals(7, TrackSort.entries.size)
    }
}
