package lol.alphaliu01.runningmusic.library

import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.models.CuteTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The only place that knows tracks are keyed on anything other than a mediaId.
 *
 * Callers keep working in whatever they already hold, a [CuteTrack] or a bare
 * mediaId, and the mediaId to durable key resolution happens here against the
 * scanner's current view of the library.
 */
class TrackMetadataRepository(
    private val dao: TrackMetadataDao,
    private val scanner: AbstractTracksScanner
) {

    fun observe(track: CuteTrack): Flow<TrackMetadata?> {
        val key = track.trackKey ?: return flowOf(null)

        return dao.observe(key)
    }

    /**
     * A mediaId only means something relative to the currently scanned library,
     * so this re-resolves whenever that library changes. A track that vanishes
     * from the scan reports null rather than a stale row.
     */
    fun observe(mediaId: String): Flow<TrackMetadata?> =
        combine(
            scanner.latestTracks.map { tracks ->
                tracks.firstOrNull { it.mediaId == mediaId }?.trackKey
            },
            dao.observeAll()
        ) { key, all ->
            if (key == null) null else all.firstOrNull { it.trackKey == key }
        }

    /**
     * Metadata for the whole library, presented in the id space callers use.
     * Rebuilt from scratch on every emission, which is fine at library sizes
     * and avoids caching a mapping that the next rescan would invalidate.
     */
    fun observeByMediaId(): Flow<Map<String, TrackMetadata>> =
        combine(scanner.latestTracks, dao.observeAll()) { tracks, all ->
            val byKey = all.associateBy { it.trackKey }

            buildMap {
                tracks.forEach { track ->
                    val row = track.trackKey?.let(byKey::get) ?: return@forEach
                    put(track.mediaId, row)
                }
            }
        }

    /**
     * Records a hand-entered tempo. Confidence is 1, because a person who took
     * the trouble to measure it is not guessing, and the source marks it so
     * that automated analysis later leaves it alone.
     *
     * Passing null clears the tempo back to never analysed rather than to
     * analysed-and-rejected, since a person deleting a value is retracting it,
     * not recording a negative result.
     */
    suspend fun setManualBpm(track: CuteTrack, bpm: Float?) {
        val key = track.trackKey ?: return

        val existing = dao.get(key)

        dao.upsert(
            (existing ?: TrackMetadata(trackKey = key)).copy(
                bpm = bpm,
                bpmConfidence = if (bpm == null) null else 1f,
                bpmSource = if (bpm == null) BPM_SOURCE_NONE else BPM_SOURCE_MANUAL,
                analysedAt = if (bpm == null) null else System.currentTimeMillis(),
                durationMs = track.durationMs,
                fileName = track.fileName,
                lastSeenPath = track.path
            )
        )
    }
}
