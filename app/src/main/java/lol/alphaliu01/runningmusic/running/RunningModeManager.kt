package lol.alphaliu01.runningmusic.running

import androidx.media3.common.Player
import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.datastore.RunningSettings
import com.sosauce.chocola.data.datastore.UserPreferences
import com.sosauce.chocola.data.mappers.toMediaItem
import com.sosauce.chocola.data.models.CuteTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lol.alphaliu01.runningmusic.cadence.Candidate
import lol.alphaliu01.runningmusic.cadence.Fold
import lol.alphaliu01.runningmusic.cadence.RunPlan
import lol.alphaliu01.runningmusic.cadence.Selected
import lol.alphaliu01.runningmusic.cadence.ToleranceBand
import lol.alphaliu01.runningmusic.cadence.foldAt
import lol.alphaliu01.runningmusic.cadence.selectForRun
import lol.alphaliu01.runningmusic.library.TrackMetadataRepository
import kotlin.math.abs
import kotlin.math.roundToLong

/** How many tracks past the one playing the player is allowed to know about. */
private const val LOOKAHEAD = 3

/**
 * Speed is moved over this many milliseconds rather than assigned.
 *
 * Stepping it produces an audible click, and running mode changes speed at every
 * single track boundary, so the click would become the defining sound of the
 * feature.
 */
private const val RAMP_MS = 400L
private const val RAMP_STEPS = 12

/** Below this a ramp is not worth running and the speed is simply set. */
private const val SPEED_EPSILON = 0.002f

/** How long [startRun] waits for PlaybackService to hand over its player. */
private const val ATTACH_TIMEOUT_MS = 2_000L
private const val ATTACH_POLL_MS = 50L

private const val MINUTE_MS = 60_000L

/** The tempo, stretch and steps-per-beat of whatever is playing right now. */
data class RunningTrack(
    val track: CuteTrack,
    val bpm: Double,
    val fold: Fold,
) {
    val speed: Double get() = fold.speed
    val stepsPerBeat: Int get() = fold.stepsPerBeat
}

/**
 * What is left of the run.
 *
 * @property worstBand the tolerance the selection actually needed, which is the
 * honest figure to show: "42 minutes, 13 tracks, at most 7% stretch".
 * @property shortfallMs how much of the requested run the library could not
 * cover.
 */
data class RunSummary(
    val trackCount: Int,
    val remainingMs: Long,
    val shortfallMs: Long,
    val worstBand: ToleranceBand?,
)

data class RunningState(
    val active: Boolean = false,
    val settings: RunningSettings? = null,
    val summary: RunSummary? = null,
    val current: RunningTrack? = null,
)

/**
 * Plans a run and keeps playback matched to a target cadence.
 *
 * A singleton rather than part of a view model, on purpose. On a real run the
 * screen is off and the activity is usually destroyed while PlaybackService
 * carries on playing; anything in the UI layer stops being told about track
 * transitions at that point, and the speed would freeze wherever the last
 * foreground track left it. The service hands its player over with [attach] and
 * calls [onTransition], so a run continues with nothing on screen.
 *
 * Everything that touches the player is confined to the main thread, which is
 * the looper Media3 built it on.
 */
class RunningModeManager(
    scanner: AbstractTracksScanner,
    metadata: TrackMetadataRepository,
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RunningState())
    val state: StateFlow<RunningState> = _state.asStateFlow()

    /**
     * The analysed part of the library, in the shape the cadence maths wants.
     *
     * Hot only while something is watching. For planning that means the run
     * screen, which a runner reads coverage figures off before they can press
     * start, so it is warm by then; a run in progress holds its own subscription
     * ([librarySubscription]) so replanning still works with the screen off.
     */
    val library: StateFlow<List<Candidate<CuteTrack>>> =
        combine(scanner.latestTracks, metadata.observeByMediaId()) { tracks, byMediaId ->
            tracks.mapNotNull { track ->
                val bpm = byMediaId[track.mediaId]?.bpm?.toDouble() ?: return@mapNotNull null
                if (bpm <= 0.0 || track.durationMs <= 0L) return@mapNotNull null
                Candidate(ref = track, bpm = bpm, durationMs = track.durationMs)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var player: Player? = null
    private var plan: RunPlan<CuteTrack>? = null
    private var rampJob: Job? = null
    private var librarySubscription: Job? = null

    /** Called by PlaybackService once its player exists. */
    fun attach(player: Player) {
        this.player = player
    }

    /**
     * The service is going away, and with it the only player a run could be
     * happening on, so the run ends here rather than lingering as state that
     * nothing can act on. Deliberately not a suspending tear-down: the player is
     * about to be released, so there is nothing left to ramp.
     */
    fun detach() {
        player = null
        rampJob?.cancel()
        librarySubscription?.cancel()
        librarySubscription = null
        plan = null
        _state.update { it.copy(active = false, summary = null, current = null) }
        scope.launch { userPreferences.setRunningModeEnabled(false) }
    }

    /**
     * Plans a run and starts playing it.
     *
     * The selection is deterministic, so it is shuffled before it reaches the
     * player: taking the best-matching tracks in order would open every run with
     * the same songs.
     */
    suspend fun startRun(settings: RunningSettings) {
        userPreferences.setRunningSettings(settings)
        _state.update { it.copy(settings = settings) }

        val player = awaitPlayer() ?: return

        librarySubscription?.cancel()
        librarySubscription = scope.launch { library.collect { } }

        val queue = selectForRun(
            library = library.value,
            targetCadence = settings.targetCadence.toDouble(),
            runLengthMs = settings.runLengthMinutes * MINUTE_MS,
        )

        if (queue.tracks.isEmpty()) {
            endRun()
            _state.update {
                it.copy(settings = settings, summary = emptyRun(settings.runLengthMinutes))
            }
            return
        }

        val (materialised, opening) = RunPlan(queue.tracks.shuffled()).materialise(LOOKAHEAD)
        plan = materialised
        userPreferences.setRunningModeEnabled(true)

        withContext(Dispatchers.Main.immediate) {
            // Marked active before the player is touched, not after. Media3
            // reports the first transition from inside setMediaItems, on this
            // thread, so [onTransition] re-enters here synchronously; a run not
            // yet flagged active would see that transition dropped.
            publish(active = true, shortfall = queue.shortfallMs)

            // A planned order only means something if the player keeps it, and
            // the run should end where the plan does rather than loop.
            player.shuffleModeEnabled = false
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItems(opening.map { it.ref.toMediaItem() }, 0, 0)
            player.prepare()
            player.play()

            ramp(materialised.current!!.fold.speed.toFloat())
        }
    }

    /**
     * Moves the target, during a run or before one.
     *
     * The steps-per-beat exponent of a playing track is deliberately kept.
     * Recomputing it could flip a track from two steps per beat to one, and
     * applying that mid-song is a doubling of speed. Only the residual moves, and
     * it is clamped, so a large drift leaves the track slightly off target rather
     * than unlistenable. Everything not yet playing is replanned freely.
     */
    suspend fun setCadence(targetCadence: Int) {
        val settings = currentSettings().copy(targetCadence = targetCadence)
        userPreferences.setRunningSettings(settings)
        _state.update { it.copy(settings = settings) }

        if (!_state.value.active) return

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext
            val current = previous.current ?: return@withContext
            val bpm = bpmOf(current.ref) ?: return@withContext

            val held = ToleranceBand.CEILING.clamp(
                foldAt(bpm, targetCadence.toDouble(), current.fold.exponent)
            )
            val rebased = current.copy(
                fold = current.fold.copy(speed = held),
                stretchedDurationMs = (current.ref.durationMs / held).roundToLong(),
            )

            val played = previous.tracks
                .take(previous.cursor + 1)
                .mapTo(mutableSetOf()) { it.ref.mediaId }

            val replanned = selectForRun(
                library = library.value.filterNot { it.ref.mediaId in played },
                targetCadence = targetCadence.toDouble(),
                runLengthMs = (settings.runLengthMinutes * MINUTE_MS - rebased.stretchedDurationMs)
                    .coerceAtLeast(0L),
            )

            val next = previous
                .withCurrent(rebased)
                .replaceTail(replanned.tracks.shuffled())

            // The player is still holding the tail that was just discarded.
            // Player and plan indices agree because the window only ever grows
            // forwards, so nothing already played is ever removed.
            if (player.mediaItemCount > next.cursor + 1) {
                player.removeMediaItems(next.cursor + 1, player.mediaItemCount)
            }

            val (materialised, added) = next.materialise(LOOKAHEAD)
            plan = materialised
            if (added.isNotEmpty()) player.addMediaItems(added.map { it.ref.toMediaItem() })

            publish(active = true, shortfall = replanned.shortfallMs)
            ramp(held.toFloat())
        }
    }

    suspend fun setRunLength(minutes: Int) {
        val settings = currentSettings().copy(runLengthMinutes = minutes)
        userPreferences.setRunningSettings(settings)
        _state.update { it.copy(settings = settings) }
    }

    /**
     * The player moved on. Re-speeds for the new track and extends the window.
     *
     * A track that is not in the plan means something outside running mode took
     * the queue over, which is a deliberate act by the user, so the run ends
     * rather than fighting them for control of the player.
     */
    suspend fun onTransition(mediaId: String?) {
        if (!_state.value.active) return
        val previous = plan ?: return

        if (mediaId == null || previous.indexOf { it.mediaId == mediaId } < 0) {
            endRun()
            return
        }

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext

            val (materialised, added) = previous
                .advanceTo { it.mediaId == mediaId }
                .materialise(LOOKAHEAD)
            plan = materialised

            if (added.isNotEmpty()) player.addMediaItems(added.map { it.ref.toMediaItem() })

            publish(active = true, shortfall = null)
            materialised.current?.let { ramp(it.fold.speed.toFloat()) }
        }
    }

    /** Ends the run and hands the player back at its normal speed. */
    suspend fun endRun() {
        librarySubscription?.cancel()
        librarySubscription = null
        plan = null

        val wasActive = _state.value.active
        _state.update { it.copy(active = false, summary = null, current = null) }
        userPreferences.setRunningModeEnabled(false)

        if (wasActive) withContext(Dispatchers.Main.immediate) { ramp(1f) }
    }

    /**
     * Whether the playback speed in the saved music state should be thrown away
     * rather than restored, and a chance to forget that it ever needed to be.
     *
     * A speed derived from one track's tempo says nothing about the next, so
     * restoring it only starts the app playing something at 1.07x for no reason.
     */
    suspend fun shouldSkipSpeedRestore(): Boolean {
        if (_state.value.active) return true

        val interrupted = userPreferences.getRunningModeEnabled().first()
        if (interrupted) userPreferences.setRunningModeEnabled(false)
        return interrupted
    }

    /** The stored settings, for a screen opening before any run has started. */
    suspend fun currentSettings(): RunningSettings =
        _state.value.settings
            ?: userPreferences.getRunningSettings().first().also { settings ->
                _state.update { if (it.settings == null) it.copy(settings = settings) else it }
            }

    /**
     * PlaybackService is started by the media controller the UI connects on
     * launch, so by the time anyone can press start the player is nearly always
     * here already. Nearly: waiting briefly beats a start button that silently
     * does nothing.
     */
    private suspend fun awaitPlayer(): Player? {
        var waited = 0L
        while (player == null && waited < ATTACH_TIMEOUT_MS) {
            delay(ATTACH_POLL_MS)
            waited += ATTACH_POLL_MS
        }
        return player
    }

    private fun publish(active: Boolean, shortfall: Long?) {
        val plan = plan
        _state.update { previous ->
            previous.copy(
                active = active,
                summary = plan?.let {
                    RunSummary(
                        trackCount = (it.size - it.cursor).coerceAtLeast(0),
                        remainingMs = it.remainingMs,
                        shortfallMs = shortfall ?: previous.summary?.shortfallMs ?: 0L,
                        worstBand = it.tracks.drop(it.cursor).worstBand(),
                    )
                },
                current = plan?.current?.let { entry ->
                    RunningTrack(
                        track = entry.ref,
                        bpm = bpmOf(entry.ref) ?: 0.0,
                        fold = entry.fold,
                    )
                },
            )
        }
    }

    private fun bpmOf(track: CuteTrack): Double? =
        library.value.firstOrNull { it.ref.mediaId == track.mediaId }?.bpm

    private fun ramp(target: Float) {
        rampJob?.cancel()
        val player = player ?: return
        val from = player.playbackParameters.speed

        if (abs(from - target) < SPEED_EPSILON) {
            setSpeed(target)
            return
        }

        rampJob = scope.launch(Dispatchers.Main.immediate) {
            repeat(RAMP_STEPS) { step ->
                delay(RAMP_MS / RAMP_STEPS)
                setSpeed(from + (target - from) * (step + 1) / RAMP_STEPS)
            }
        }
    }

    /**
     * Pitch is untouched on purpose. Media3 time-stretches through Sonic, which
     * preserves pitch already, so tying the two together the way the manual speed
     * control offers would transpose every track in the run.
     */
    private fun setSpeed(speed: Float) {
        val player = player ?: return
        player.playbackParameters = player.playbackParameters.withSpeed(speed)
    }

    private fun emptyRun(runLengthMinutes: Int) = RunSummary(
        trackCount = 0,
        remainingMs = 0L,
        shortfallMs = runLengthMinutes * MINUTE_MS,
        worstBand = null,
    )
}

/** The stretch a stretch of run actually needed, in the shape it was applied. */
private fun List<Selected<CuteTrack>>.worstBand(): ToleranceBand? {
    if (isEmpty()) return null
    return ToleranceBand(
        maxSpeedUp = maxOf(1.0, maxOf { it.fold.speed }),
        maxSlowDown = maxOf(1.0, maxOf { 1.0 / it.fold.speed }),
    )
}
