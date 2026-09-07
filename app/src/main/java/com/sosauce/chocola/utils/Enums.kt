package com.sosauce.chocola.utils

enum class AlbumSort {
    NAME,
    ARTIST
}

/**
 * Persisted as an index into [entries], so new entries may only be appended.
 * Inserting one anywhere else silently rewrites the saved sort of every
 * existing user. TrackSortOrdinalsTest pins the order against that.
 */
enum class TrackSort {
    TITLE,
    ARTIST,
    ALBUM,
    YEAR,
    DATE_MODIFIED,
    AS_ADDED, // For playlist tracks ONLY
    BPM
}

enum class ArtistSort {
    NAME,
    NB_TRACKS,
    NB_ALBUMS
}

enum class PlaylistSort {
    NAME,
    NB_TRACKS,
    TAGS,
    COLOR
}
