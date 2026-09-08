package lol.alphaliu01.runningmusic.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackMetadataDao {

    @Query("SELECT * FROM track_metadata WHERE trackKey = :trackKey")
    fun observe(trackKey: String): Flow<TrackMetadata?>

    @Query("SELECT * FROM track_metadata")
    fun observeAll(): Flow<List<TrackMetadata>>

    @Query("SELECT * FROM track_metadata WHERE trackKey = :trackKey")
    suspend fun get(trackKey: String): TrackMetadata?

    @Query("SELECT * FROM track_metadata")
    suspend fun getAll(): List<TrackMetadata>

    @Upsert
    suspend fun upsert(metadata: TrackMetadata)

    @Query("DELETE FROM track_metadata WHERE trackKey = :trackKey")
    suspend fun delete(trackKey: String)

    /**
     * Throws away everything the app worked out for itself, leaving hand-entered
     * and tagged tempos alone.
     *
     * The whole re-analysis story, in place of an analysis-version column. A
     * column would make staleness automatic, but it would also cost a migration
     * on every parameter change, and pressing a button is an honest substitute
     * while the analysis parameters are still being chosen.
     */
    @Query("DELETE FROM track_metadata WHERE bpmSource = :source")
    suspend fun deleteBySource(source: String)
}
