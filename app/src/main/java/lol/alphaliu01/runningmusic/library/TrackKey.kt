package lol.alphaliu01.runningmusic.library

import com.sosauce.chocola.data.models.CuteTrack

/**
 * The durable identity of a track, used to key everything the app remembers
 * about it.
 *
 * [CuteTrack.mediaId] cannot be used for this. It is `MediaStore._ID` for
 * scanned tracks and `uri.hashCode()` for SAF tracks, so it changes whenever
 * the media store is rebuilt and the two sources share one id space with no way
 * to tell them apart. Analysis keyed on it would be lost on every rescan.
 *
 * Size and file name are both filesystem facts, so neither moves when a file is
 * re-indexed. Duration is deliberately left out even though it looks like a
 * strong discriminator: MediaStore and TagLib parse it separately and disagree
 * by a few milliseconds, and MediaStore's value can shift when the platform
 * re-extracts metadata after an OS update. That is the exact failure this key
 * exists to prevent, so nothing decoder-derived belongs in it.
 *
 * The remaining cost is that a rename breaks the key, and that two files with
 * the same name and the same byte count collide. The second is by design: in
 * practice those are duplicates of the same audio, where one shared row is the
 * right answer.
 */
fun trackKey(sizeBytes: Long, fileName: String): String? {
    if (sizeBytes <= 0L || fileName.isEmpty()) return null

    // Size first, so the first separator always terminates it. File names may
    // legally contain the separator; sizes cannot, so this stays injective.
    return "$sizeBytes$TRACK_KEY_SEPARATOR$fileName"
}

/**
 * Null when the track has no file identity, which is the case for the ad-hoc
 * [CuteTrack] that QuickPlay builds out of player metadata. Nothing may be
 * stored against those.
 */
val CuteTrack.trackKey: String?
    get() = trackKey(sizeBytes, fileName)

private const val TRACK_KEY_SEPARATOR = '|'
