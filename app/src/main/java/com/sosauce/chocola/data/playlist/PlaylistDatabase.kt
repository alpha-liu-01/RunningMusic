package com.sosauce.chocola.data.playlist

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sosauce.chocola.data.MediaItemConverter
import com.sosauce.chocola.data.models.Playlist
import lol.alphaliu01.runningmusic.library.TrackMetadata
import lol.alphaliu01.runningmusic.library.TrackMetadataDao

/**
 * Still called PlaylistDatabase although it now holds track metadata too.
 * Renaming it would rewrite the file for every reader of it and widen the diff
 * against upstream for no functional gain, which this fork tries to avoid.
 */
@Database(
    entities = [Playlist::class, TrackMetadata::class],
    version = 3
)
@TypeConverters(MediaItemConverter::class)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract val dao: PlaylistDao
    abstract val trackMetadataDao: TrackMetadataDao

}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Playlist ADD COLUMN color INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE Playlist ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
    }
}

/**
 * Purely additive: no existing row is read or rewritten, so there is nothing
 * here that can lose a playlist.
 *
 * The statement is copied verbatim out of the generated
 * schemas/.../3.json, with only ${'$'}{TABLE_NAME} substituted. Writing it by hand
 * is how migrations end up failing validateMigration over a detail like a
 * missing DEFAULT that Room did not emit.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `track_metadata` (" +
                    "`trackKey` TEXT NOT NULL, " +
                    "`bpm` REAL, " +
                    "`bpmConfidence` REAL, " +
                    "`bpmSource` TEXT NOT NULL, " +
                    "`analysedAt` INTEGER, " +
                    "`durationMs` INTEGER NOT NULL, " +
                    "`fileName` TEXT NOT NULL, " +
                    "`lastSeenPath` TEXT NOT NULL, " +
                    "PRIMARY KEY(`trackKey`))"
        )
    }
}
