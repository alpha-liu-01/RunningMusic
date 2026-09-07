package lol.alphaliu01.runningmusic.analysis

import android.content.Context
import android.net.Uri
import com.kyant.taglib.TagLib

/**
 * The narrowest tempo worth believing from a tag, and the widest.
 *
 * A value outside this is not a slow or fast song, it is a different unit, a
 * typo, or a field someone used for something else. Ordinary music runs roughly
 * 40 to 250; the bounds are a little wider so that genuinely extreme material is
 * not thrown away.
 */
private val PLAUSIBLE_BPM = 30f..300f

/** The key taglib canonicalises every format's tempo field onto, ID3v2 TBPM included. */
private const val BPM_KEY = "BPM"

/**
 * The tempo the file already claims, if it claims one.
 *
 * Free and instant compared with decoding, so it is worth asking first. It is
 * also less trustworthy than it looks -- a BPM tag is very often another
 * detector's output, which is the same class of guess this app is making -- but
 * a stated tempo still beats a computed one, because whoever wrote it had the
 * option of knowing.
 */
fun parseBpmTag(raw: String?): Float? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    // Tags in the wild carry things like "128 BPM" and "128,5".
    val number = value
        .removeSuffix("BPM").removeSuffix("bpm")
        .trim()
        .replace(',', '.')
        .toFloatOrNull()
        ?: return null

    if (!number.isFinite() || number !in PLAUSIBLE_BPM) return null

    return number
}

/** Reads the BPM tag off a file, returning null for anything unreadable or absent. */
class BpmTagReader(private val context: Context) {

    fun read(uri: Uri): Float? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            val metadata = TagLib.getMetadata(
                fd = fd.dup().detachFd(),
                readPictures = false
            )

            parseBpmTag(metadata?.propertyMap?.get(BPM_KEY)?.firstOrNull())
        }
    }.getOrNull()
}
