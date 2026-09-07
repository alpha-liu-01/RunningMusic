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

    @Upsert
    suspend fun upsert(metadata: TrackMetadata)

    @Query("DELETE FROM track_metadata WHERE trackKey = :trackKey")
    suspend fun delete(trackKey: String)
}
