package com.sosauce.chocola.data.playlist

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import com.sosauce.chocola.data.datastore.UserPreferences
import com.sosauce.chocola.utils.observe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class PlaylistCleanup(
    private val context: Context,
    private val playlistDao: PlaylistDao,
    private val userPreferences: UserPreferences
) {

    private val mediaStoreObserver =
        context.contentResolver.observe(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

    /**
     * Removes deleted mediaIds from playlists.
     *
     * A playlist entry is a bare mediaId string drawn from two different id
     * spaces: MediaStore._ID for scanned tracks, and uri.hashCode() for SAF
     * tracks. A SAF id can never appear in a MediaStore result, so checking
     * only against MediaStore deletes every SAF track from every playlist. Both
     * sources have to be consulted.
     */
    suspend fun startCleanup() = withContext(Dispatchers.IO) {
        combine(
            mediaStoreObserver,
            playlistDao.getPlaylists(),
            userPreferences.getSafTracks()
        ) { _, playlists, safUris ->
            playlists to safUris
        }.collectLatest { (playlists, safUris) ->

            val existingMediaIds = queryMediaStoreIds() ?: return@collectLatest
            val safMediaIds = safUris.mapTo(mutableSetOf()) { it.toUri().hashCode().toString() }

            // Nothing known from either source means the library could not be
            // read, not that the user deleted all their music. Cleanup runs
            // from MainActivity.onCreate, so it can easily land before the
            // media permission is granted, and a MediaStore query made without
            // it comes back empty rather than failing. Purging on that signal
            // would empty every playlist the first time the app is opened.
            if (existingMediaIds.isEmpty() && safMediaIds.isEmpty()) return@collectLatest

            playlists.forEach { playlist ->
                val cleanedMusics = playlist.musics.filterTo(mutableSetOf()) { id ->
                    id in existingMediaIds || id in safMediaIds
                }
                val newPlaylist = playlist.copy(
                    musics = cleanedMusics
                )

                // means there's IDs to delete
                if (cleanedMusics.size != playlist.musics.size) {
                    playlistDao.upsertPlaylist(newPlaylist)
                }
            }
        }
    }

    /** Null when MediaStore could not be queried at all. */
    private fun queryMediaStoreIds(): Set<String>? {
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            null,
            null,
            null
        ) ?: return null

        return cursor.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)

            buildSet {
                while (it.moveToNext()) {
                    add(it.getString(idColumn))
                }
            }
        }
    }
}
