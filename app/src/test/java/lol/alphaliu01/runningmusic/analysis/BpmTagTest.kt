package lol.alphaliu01.runningmusic.analysis

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class BpmTagTest {

    @Test
    fun `a plain integer is a tempo`() {
        assertEquals(128f, parseBpmTag("128"))
    }

    @Test
    fun `a decimal is a tempo`() {
        assertEquals(128.5f, parseBpmTag("128.5"))
    }

    @Test
    fun `a comma decimal separator is a tempo`() {
        // Non-English taggers write it this way, and refusing it would silently
        // send half of Europe's libraries through a decode they did not need.
        assertEquals(128.5f, parseBpmTag("128,5"))
    }

    @Test
    fun `the unit is tolerated`() {
        assertEquals(128f, parseBpmTag("128 BPM"))
        assertEquals(128f, parseBpmTag("128bpm"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(128f, parseBpmTag("  128  "))
    }

    @Test
    fun `an absent or empty tag is not a tempo`() {
        assertNull(parseBpmTag(null))
        assertNull(parseBpmTag(""))
        assertNull(parseBpmTag("   "))
    }

    @Test
    fun `junk is not a tempo`() {
        assertNull(parseBpmTag("moderato"))
        assertNull(parseBpmTag("128-140"))
        assertNull(parseBpmTag("NaN"))
    }

    @Test
    fun `a value outside the plausible range is not a tempo`() {
        // 0 is the common way a tagger writes "I did not know", and a four
        // figure value is a different unit or a typo. Either would send the
        // track to a wildly wrong playback speed if it were believed.
        assertNull(parseBpmTag("0"))
        assertNull(parseBpmTag("-128"))
        assertNull(parseBpmTag("12"))
        assertNull(parseBpmTag("1280"))
    }

    @Test
    fun `the edges of the plausible range are accepted`() {
        assertEquals(30f, parseBpmTag("30"))
        assertEquals(300f, parseBpmTag("300"))
    }
}
