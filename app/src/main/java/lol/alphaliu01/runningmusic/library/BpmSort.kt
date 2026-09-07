package lol.alphaliu01.runningmusic.library

import com.sosauce.chocola.data.models.CuteTrack

/**
 * Orders a library by tempo, putting tracks with no known tempo last in both
 * directions.
 *
 * The other in-memory sorts in the app follow the shape "sort ascending, then
 * reverse the whole list for descending". That cannot be used here: reversing
 * would float every un-analysed track to the top, which is the least useful
 * place for them. So being un-analysed is a primary key that always sorts the
 * same way, and only the tempo comparison itself flips.
 *
 * The sort is stable, so tracks sharing a tempo keep the order they arrived in.
 * That order is the title ordering the MediaStore query already applied, which
 * makes it a free and sensible tiebreak.
 *
 * @param bpmByTrackKey known tempos, keyed the durable way rather than by
 *   mediaId. A track missing from this map is un-analysed, which includes any
 *   track with no file identity at all.
 */
fun List<CuteTrack>.orderedByBpm(
    bpmByTrackKey: Map<String, Float>,
    ascending: Boolean
): List<CuteTrack> {
    fun bpmOf(track: CuteTrack) = track.trackKey?.let(bpmByTrackKey::get)

    return sortedWith(
        compareBy<CuteTrack> { bpmOf(it) == null }
            .thenComparator { a, b ->
                val first = bpmOf(a)
                val second = bpmOf(b)

                when {
                    // Two un-analysed tracks are not ordered relative to each
                    // other; the stable sort leaves them as they were.
                    first == null || second == null -> 0
                    ascending -> first.compareTo(second)
                    else -> second.compareTo(first)
                }
            }
    )
}
