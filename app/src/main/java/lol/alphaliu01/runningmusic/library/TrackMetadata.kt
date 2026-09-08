package lol.alphaliu01.runningmusic.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the app remembers about one track between launches, keyed on the durable
 * [trackKey] rather than on a mediaId.
 *
 * Three states have to stay distinguishable, which is why both [bpm] and
 * [analysedAt] are nullable:
 *
 * - no row at all, or [analysedAt] null: never analysed.
 * - [analysedAt] set and [bpm] null: analysed, and the result was not confident
 *   enough to use. Do not analyse again, and do not use a tempo.
 * - [bpm] set: usable.
 *
 * Collapsing those last two into "bpm is null" would make the analysis job
 * re-analyse every track it had already rejected, on every pass.
 */
@Entity(tableName = "track_metadata")
data class TrackMetadata(
    @PrimaryKey val trackKey: String,
    val bpm: Float? = null,
    val bpmConfidence: Float? = null,
    val bpmSource: String = BPM_SOURCE_NONE,
    val analysedAt: Long? = null,
    /**
     * Denormalised copies of what the track looked like when the row was
     * written. Diagnostics only: neither takes part in identity, and a stale
     * value here is harmless.
     */
    val durationMs: Long = 0,
    val fileName: String = "",
    val lastSeenPath: String = ""
)

/** No tempo has been established by any means. */
const val BPM_SOURCE_NONE = "NONE"

/**
 * Typed in by hand. Automated analysis must never overwrite one of these; a
 * person who bothered to measure a tempo outranks a detector.
 */
const val BPM_SOURCE_MANUAL = "MANUAL"

/** Read out of the file's own TBPM/BPM tag. */
const val BPM_SOURCE_TAG = "TAG"

/** Produced by the app's own analysis. */
const val BPM_SOURCE_ANALYSIS = "ANALYSIS"
