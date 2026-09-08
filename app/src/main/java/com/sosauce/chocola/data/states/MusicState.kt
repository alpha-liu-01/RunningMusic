package com.sosauce.chocola.data.states

import androidx.media3.common.Player
import com.sosauce.chocola.data.models.CuteTrack
import com.sosauce.chocola.domain.model.Lyrics
import kotlinx.serialization.Serializable

@Serializable
data class MusicState(
    val track: CuteTrack = CuteTrack(),
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val isPlayerReady: Boolean = false,
    val sleepTimerRemainingDuration: Long = 0,
    val mediaIndex: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val loadedMedias: List<CuteTrack> = emptyList(),
    val audioSessionAudio: Int = 0,
    val lyrics: List<Lyrics> = emptyList(),
    /**
     * Whether a cadence-matched run is in progress.
     *
     * Mirrored here from RunningModeManager so that playback UI far from the run
     * screen can react to it, chiefly to know that [speed] is no longer a setting
     * the user chose but a property of the track being played.
     *
     * Serialised along with the rest of this state and then ignored on restore: a
     * run does not survive the process it was planned in.
     */
    val runningMode: Boolean = false,
)
