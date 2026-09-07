package com.sosauce.chocola.data.playlist

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import lol.alphaliu01.runningmusic.library.BPM_SOURCE_MANUAL
import lol.alphaliu01.runningmusic.library.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration that introduces the track metadata store. The point of the key
 * design is that analysis survives a rescan, so losing playlists to the
 * migration that enables it would be a poor start.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PlaylistDatabase::class.java
    )

    @Test
    fun migrating2To3KeepsPlaylistsIntact() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO Playlist (id, emoji, name, musics, color, tags) " +
                        "VALUES (1, '\uD83C\uDFC3', 'Long run', ?, -1, '[]')",
                // One MediaStore id and one SAF hash, which is the mix that
                // PlaylistCleanup used to destroy.
                arrayOf("""["1234","-98765432"]""")
            )
        }

        // Fails loudly if the hand-written CREATE TABLE has drifted from what
        // Room expects for version 3.
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT name, musics FROM Playlist WHERE id = 1").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()

                assertEquals("Long run", cursor.getString(0))
                assertEquals("""["1234","-98765432"]""", cursor.getString(1))
            }
        }
    }

    @Test
    fun theMigratedDatabaseStoresAndReadsBackTrackMetadata() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, PlaylistDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        try {
            val row = TrackMetadata(
                trackKey = "5242880|song.mp3",
                bpm = 172f,
                bpmConfidence = 1f,
                bpmSource = BPM_SOURCE_MANUAL,
                analysedAt = 1_700_000_000_000,
                durationMs = 213_000,
                fileName = "song.mp3",
                lastSeenPath = "/storage/emulated/0/Music/song.mp3"
            )

            runBlocking {
                database.trackMetadataDao.upsert(row)

                assertEquals(row, database.trackMetadataDao.get("5242880|song.mp3"))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun aTrackWithNoStoredMetadataIsDistinctFromOneStoredWithNoTempo() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, PlaylistDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        try {
            runBlocking {
                // Analysed, and rejected as not confident enough. This has to
                // stay tellable apart from never analysed, or the analysis job
                // reconsiders every rejected track on every pass.
                database.trackMetadataDao.upsert(
                    TrackMetadata(
                        trackKey = "1|rejected.mp3",
                        bpm = null,
                        analysedAt = 1_700_000_000_000
                    )
                )

                val rejected = database.trackMetadataDao.get("1|rejected.mp3")

                assertNotNull(rejected)
                assertEquals(null, rejected!!.bpm)
                assertNotNull(rejected.analysedAt)

                // Never analysed: no row at all.
                assertEquals(null, database.trackMetadataDao.get("1|untouched.mp3"))
            }
        } finally {
            database.close()
        }
    }
}

private const val TEST_DB = "migration-test.db"
