package lol.alphaliu01.runningmusic.library

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.datastore.UserPreferences
import com.sosauce.chocola.data.models.CuteTrack
import com.sosauce.chocola.data.playlist.PlaylistDatabase
import com.sosauce.chocola.data.repositories.SafManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackMetadataRepositoryTest {

    private lateinit var database: PlaylistDatabase
    private lateinit var repository: TrackMetadataRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()

        val userPreferences = UserPreferences(context)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        repository = TrackMetadataRepository(
            dao = database.trackMetadataDao,
            scanner = AbstractTracksScanner(
                context = context,
                userPreferences = userPreferences,
                ioCoroutineScope = scope,
                safManager = SafManager(context, userPreferences)
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aStoredTempoSurvivesTheMediaIdChanging() = runBlocking {
        // What a rescan does: same file on disk, brand new MediaStore._ID. If
        // the store were keyed on mediaId, this is where a library's worth of
        // analysis would be lost.
        val beforeRescan = track(mediaId = "1234")
        val afterRescan = track(mediaId = "99999999")

        repository.setManualBpm(beforeRescan, 172f)

        val found = repository.observe(afterRescan).first()

        assertNotNull(found)
        assertEquals(172f, found!!.bpm)
        assertEquals(BPM_SOURCE_MANUAL, found.bpmSource)
    }

    @Test
    fun theSameFileReachedThroughMediaStoreAndSafSharesOneRow() = runBlocking {
        // A SAF mediaId is a uri hash, nothing like a MediaStore id, but the
        // file underneath is the same one.
        val scanned = track(mediaId = "1234", isSaf = false)
        val viaSaf = track(mediaId = "-874219", isSaf = true)

        repository.setManualBpm(scanned, 160f)

        assertEquals(160f, repository.observe(viaSaf).first()?.bpm)
    }

    @Test
    fun movingAFileBetweenFoldersKeepsItsTempo() = runBlocking {
        val before = track(path = "/storage/emulated/0/Music/song.mp3")
        val after = track(path = "/storage/emulated/0/Running/song.mp3")

        repository.setManualBpm(before, 172f)

        assertEquals(172f, repository.observe(after).first()?.bpm)
    }

    @Test
    fun aDifferentFileWithTheSameNameIsNotConfusedForIt() = runBlocking {
        val original = track(sizeBytes = 5_242_880)
        val other = track(sizeBytes = 4_000_000)

        repository.setManualBpm(original, 172f)

        assertNull(repository.observe(other).first())
    }

    @Test
    fun clearingATempoReturnsTheTrackToNeverAnalysed() = runBlocking {
        val track = track()

        repository.setManualBpm(track, 172f)
        repository.setManualBpm(track, null)

        val row = repository.observe(track).first()

        assertNotNull(row)
        assertNull(row!!.bpm)
        // Not analysed-and-rejected: a person deleting a value is retracting
        // it, not recording a negative result.
        assertNull(row.analysedAt)
        assertEquals(BPM_SOURCE_NONE, row.bpmSource)
    }

    @Test
    fun aTrackWithNoFileBehindItStoresNothing() = runBlocking {
        // What QuickPlay builds out of player metadata alone.
        val quickPlay = CuteTrack(title = "Something", artist = "Someone")

        repository.setManualBpm(quickPlay, 172f)

        assertNull(repository.observe(quickPlay).first())
        assertEquals(0, database.trackMetadataDao.observeAll().first().size)
    }

    private fun track(
        mediaId: String = "1234",
        sizeBytes: Long = 5_242_880,
        path: String = "/storage/emulated/0/Music/song.mp3",
        isSaf: Boolean = false
    ) = CuteTrack(
        mediaId = mediaId,
        title = "Song",
        path = path,
        isSaf = isSaf,
        sizeBytes = sizeBytes,
        fileName = "song.mp3",
        durationMs = 213_000
    )
}
