package com.sosauce.chocola.data.models

import androidx.core.net.toUri
import kotlinx.serialization.Serializable

@Serializable
data class CuteTrack(
    val mediaId: String = "",
    private val uriString: String = "",
    private val artUriString: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val trackNumber: Int = 0,
    val folder: String = "",
    val path: String = "",
    val isSaf: Boolean = false,
    /**
     * File size in bytes, and the file's own name. Together these form the
     * durable identity used by the track metadata store; see TrackKey. New
     * fields need defaults because this class is serialized into DataStore as
     * part of MusicState, so older saved states are missing them.
     */
    val sizeBytes: Long = 0,
    val fileName: String = "",
    val durationMs: Long = 0
) {
    val uri
        get() = uriString.toUri()

    val artUri
        get() = artUriString.toUri()
}



