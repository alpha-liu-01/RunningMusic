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

    /**
     * Records what the file's own tempo tag said.
     *
     * Confidence is left null rather than set high: a tag is a claim, and often
     * enough another detector's claim, so there is no measurement here to attach
     * a number to.
     */
    suspend fun setTaggedBpm(track: CuteTrack, bpm: Float) = write(track) {
        it.copy(
            bpm = bpm,
            bpmConfidence = null,
            bpmSource = BPM_SOURCE_TAG,
            analysedAt = System.currentTimeMillis()
        )
    }

    /**
     * Records the outcome of analysing a track, including the outcome "we could
     * not tell".
     *
     * A failure is stored, not skipped. `bpm = null` with the source set and
     * [TrackMetadata.analysedAt] filled in is what distinguishes a track we
     * examined and could not read from one nobody has looked at yet, and it is
     * the only thing stopping the job from decoding its own failures on every
     * future run.
     */
    suspend fun setAnalysedBpm(
        track: CuteTrack,
        bpm: Float?,
        confidence: Float?
    ) = write(track) {
        it.copy(
            bpm = bpm,
            bpmConfidence = confidence,
            bpmSource = BPM_SOURCE_ANALYSIS,
            analysedAt = System.currentTimeMillis()
        )
    }

    /** What is already known, keyed durably, for deciding what still needs work. */
    suspend fun storedByTrackKey(): Map<String, TrackMetadata> =
        dao.getAll().associateBy { it.trackKey }

    /**
     * Forgets every tempo the app worked out for itself.
     *
     * Hand-entered and tagged tempos survive, because neither came from the
     * analysis whose parameters changed.
     */
    suspend fun clearAnalysed() = dao.deleteBySource(BPM_SOURCE_ANALYSIS)

    private suspend inline fun write(
        track: CuteTrack,
        update: (TrackMetadata) -> TrackMetadata
    ) {
        val key = track.trackKey ?: return

        val base = dao.get(key) ?: TrackMetadata(trackKey = key)

        dao.upsert(
            update(base).copy(
                durationMs = track.durationMs,
                fileName = track.fileName,
                lastSeenPath = track.path
            )
        )
    }
}
