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
import lol.alphaliu01.runningmusic.cadence.fold
import lol.alphaliu01.runningmusic.cadence.foldAt
import lol.alphaliu01.runningmusic.cadence.rebaseTail
import lol.alphaliu01.runningmusic.cadence.selectForRun
import lol.alphaliu01.runningmusic.cadence.steps.LoopConfig
import lol.alphaliu01.runningmusic.cadence.steps.TrackingMode
import lol.alphaliu01.runningmusic.cadence.suggestCadence
import lol.alphaliu01.runningmusic.library.TrackMetadataRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Hands the player everything the run has planned.
 *
 * The player used to be told about the next three tracks only, because a replan
 * reshuffled the tail and anything further ahead was about to be wrong anyway.
 * Now that a replan keeps the order, "up next" is stable enough to be worth
 * looking at, and the queue screen shows the run rather than a four-track window
 * of it. A run is a dozen or so tracks; there is nothing here to ration.
 */
private fun <T> RunPlan<T>.materialiseAll() = materialise(size)

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

/**
 * Taken from the control loop rather than restated, so the slider, the loop and
 * the library nudge cannot drift apart into three different ideas of what counts
 * as a running cadence.
 */
private val CADENCE_RANGE = LoopConfig().cadenceRange

/**
 * The fold of a track with no analysed tempo: play it as it was recorded.
 *
 * Not a real fold, and deliberately not dressed up as one. There is no cadence
 * this track is matched to, so one step per beat at unity speed is the only
 * honest thing to claim, and a zero bpm alongside it is what the UI reads to say
 * as much.
 */
private val UNMATCHED = Fold(exponent = 0, rawExponent = 0, speed = 1.0)

/** The tempo, stretch and steps-per-beat of whatever is playing right now. */
data class RunningTrack(
    val track: CuteTrack,
    val bpm: Double,
    val fold: Fold,
) {
    val speed: Double get() = fold.speed
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
    private val scanner: AbstractTracksScanner,
    metadata: TrackMetadataRepository,
    private val userPreferences: UserPreferences,
    private val cadenceTracker: CadenceTracker,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RunningState())
    val state: StateFlow<RunningState> = _state.asStateFlow()

    /** What the step detector is doing, passed through so the UI has one source. */
    val tracking: StateFlow<CadenceTrackerState> = cadenceTracker.state

    /** False on hardware with no step detector, where only the slider can drive. */
    fun hasStepDetector(): Boolean = cadenceTracker.capabilities().hasAnyDetector

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
        cadenceTracker.stop()
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
            band = settings.tolerance,
        )

        if (queue.tracks.isEmpty()) {
            endRun()
            _state.update {
                it.copy(settings = settings, summary = emptyRun(settings.runLengthMinutes))
            }
            return
        }

        val (materialised, opening) = RunPlan(queue.tracks.shuffled()).materialiseAll()
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

        // Started once the run is audibly under way. The opening minute plays at
        // whatever the runner set, which is the whole reason the slider survives
        // into a tracked run rather than being replaced by it.
        startTracking(settings)
    }

    /**
     * Hands the target to the step detector for the rest of the run.
     *
     * The library-fit nudge belongs to [TrackingMode.MEASURE_THEN_LOCK] and to
     * nothing else. That mode measures once and then holds, so the nudge is a
     * single offer of a better-covered cadence and the runner is free to ignore
     * it. Follow mode closes a loop through the runner, who hears the target and
     * synchronises to it, and a *constant* offset inside a closed loop is not
     * absorbed, it is integrated: the runner speeds up to the nudged target, the
     * loop measures the faster cadence, the nudge is added again, and the two
     * walk each other up until the drift cap stops them several spm above the
     * pace the runner actually chose. Freezing the offset, which is what this
     * used to do, prevents it from jittering and does nothing about that.
     */
    private fun startTracking(settings: RunningSettings) {
        val nudges = settings.trackingMode == TrackingMode.MEASURE_THEN_LOCK
        var offset: Int? = null

        cadenceTracker.start(
            mode = settings.trackingMode,
            initialTarget = settings.targetCadence,
        ) { target ->
            scope.launch {
                if (nudges && offset == null) offset = snapToLibrary(target) - target
                applyCadence((target + (offset ?: 0)).coerceIn(CADENCE_RANGE))
            }
        }
    }

    /**
     * Moves a measured cadence onto one the library can actually fill a run at.
     *
     * A runner measured at 170 is sitting at the worst case of the octave fold
     * for the largest tempo cluster in most libraries, and a few spm either way
     * can unlock a large part of it. Nudging a runner slightly is defensible;
     * playing them 40 minutes of badly stretched music is not.
     */
    private suspend fun snapToLibrary(measured: Int): Int {
        val library = library.value
        if (library.isEmpty()) return measured
        val band = currentSettings().tolerance
        return suggestCadence(library, measured.toDouble(), band = band).best.cadence.roundToInt()
    }

    /**
     * The runner moved the target themselves.
     *
     * A hand on the slider ends sensor tracking for the rest of the run. Leaving
     * it running would mean the loop quietly undoing a deliberate correction a
     * minute later, and there is no way to present that which does not look like
     * a bug.
     */
    suspend fun setCadence(targetCadence: Int) {
        cadenceTracker.stop()
        userPreferences.setRunningSettings(storedSettings().copy(targetCadence = targetCadence))
        applyCadence(targetCadence)
    }

    /**
     * Moves the target, during a run or before one, without persisting it.
     *
     * Persistence is the caller's decision because the two sources differ in
     * kind: a slider is a preference and belongs in DataStore, while a
     * measurement is a fact about one run and does not. Writing the latter would
     * mean a runner who tracked once finds their slider somewhere new every time
     * they open the screen.
     *
     * The steps-per-beat exponent of a playing track is deliberately kept.
     * Recomputing it could flip a track from two steps per beat to one, and
     * applying that mid-song is a doubling of speed. Only the residual moves, and
     * it is clamped, so a large drift leaves the track slightly off target rather
     * than unlistenable. Everything not yet playing is replanned freely.
     */
    private suspend fun applyCadence(targetCadence: Int) {
        val settings = currentSettings().copy(targetCadence = targetCadence)
        _state.update { it.copy(settings = settings) }

        if (!_state.value.active) return

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext
            val current = previous.current ?: return@withContext
            val bpm = bpmOf(current.ref) ?: return@withContext

            val held = settings.tolerance.clamp(
                foldAt(bpm, targetCadence.toDouble(), current.fold.exponent)
            )
            val rebased = current.copy(
                fold = current.fold.copy(speed = held),
                stretchedDurationMs = (current.ref.durationMs / held).roundToLong(),
            )

            val known = library.value.associateBy { it.ref.mediaId }
            val tail = previous.tracks.drop(previous.cursor + 1)
            val rebasedTail = rebaseTail(tail, targetCadence.toDouble(), settings.tolerance) {
                known[it.mediaId]
            }

            // Re-speeding changes how much of the run the same tracks cover, in
            // both directions: a slower target stretches them past the length
            // that was asked for. Filled the same way selectForRun fills, so a
            // run that has been replanned is the length a fresh one would be.
            val budget = (settings.runLengthMinutes * MINUTE_MS - rebased.stretchedDurationMs)
                .coerceAtLeast(0L)

            var filled = 0L
            val kept = rebasedTail.takeWhile { entry ->
                val room = filled < budget
                filled += entry.stretchedDurationMs
                room
            }

            // Nothing else is dropped, so this usually removes nothing at all.
            // Backwards, because each removal shifts what is after it, and plan
            // and player indices agree.
            val keptIds = kept.mapTo(mutableSetOf()) { it.ref.mediaId }
            for (index in previous.materialised - 1 downTo previous.cursor + 1) {
                if (previous.tracks[index].ref.mediaId !in keptIds) player.removeMediaItem(index)
            }

            // Whatever the drops and the re-speeding cost, made up from tracks
            // the run has not already spoken for.
            val spokenFor =
                keptIds + previous.tracks.take(previous.cursor + 1).map { it.ref.mediaId }

            val topUp = selectForRun(
                library = library.value.filterNot { it.ref.mediaId in spokenFor },
                targetCadence = targetCadence.toDouble(),
                runLengthMs = (budget - kept.sumOf { it.stretchedDurationMs }).coerceAtLeast(0L),
                band = settings.tolerance,
            )

            val (materialised, added) = previous
                .withCurrent(rebased)
                .rebuildTail(kept, topUp.tracks.shuffled())
                .materialiseAll()

            plan = materialised
            if (added.isNotEmpty()) player.addMediaItems(added.map { it.ref.toMediaItem() })

            publish(active = true, shortfall = topUp.shortfallMs)
            ramp(held.toFloat())
        }
    }

    suspend fun setRunLength(minutes: Int) = persist { it.copy(runLengthMinutes = minutes) }

    suspend fun setTrackingMode(mode: TrackingMode) = persist { it.copy(trackingMode = mode) }

    /**
     * Widening or tightening the tolerance changes which tracks are eligible, so
     * a run already under way is replanned at its current target rather than
     * carrying on with a queue chosen under the old limits.
     */
    suspend fun setTolerance(band: ToleranceBand) {
        persist { it.copy(tolerance = band) }
        if (_state.value.active) applyCadence(currentSettings().targetCadence)
    }

    /**
     * Writes one field through to DataStore and mirrors it into the live state.
     *
     * Built from the stored settings rather than from [RunningState.settings],
     * which during a tracked run holds a measured cadence. Reading the live
     * state here would mean changing the run length halfway round a run silently
     * saved the sensor's target as the runner's preference.
     */
    private suspend fun persist(change: (RunningSettings) -> RunningSettings) {
        val stored = change(storedSettings())
        userPreferences.setRunningSettings(stored)
        _state.update { previous ->
            previous.copy(settings = previous.settings?.let(change) ?: stored)
        }
    }

    /**
     * Plays [mediaId] inside the run rather than instead of it.
     *
     * A run is a mode the player is in, not a playlist it owns, so asking for a
     * particular song is not an act of abandoning the run. Anything the runner
     * picks anywhere in the app arrives here and joins the plan.
     *
     * Something already planned is simply seeked to, wherever in the run it sits.
     * Anything else is put next, in front of what was coming rather than in place
     * of it, so the run resumes where it was once the detour finishes.
     */
    suspend fun playNow(mediaId: String) {
        if (!_state.value.active) return
        val settings = currentSettings()

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext

            val planned = previous.indexOf { it.mediaId == mediaId }
            if (planned >= 0) {
                player.seekTo(planned, 0)
                player.play()
                return@withContext
            }

            val track = scanner.latestTracks.value.firstOrNull { it.mediaId == mediaId }
                ?: return@withContext

            val spliced = previous.insertAfterCursor(entryFor(track, settings))
            plan = spliced

            // Located by searching rather than by repeating the arithmetic
            // insertAfterCursor just did. There was no entry for this track a
            // moment ago, so the first match is the one that was inserted.
            val at = spliced.indexOf { it.mediaId == mediaId }
            player.addMediaItem(at, track.toMediaItem())
            player.seekTo(at, 0)
            player.play()
        }
    }

    /** Puts [mediaId] next without interrupting what is playing. */
    suspend fun queueNext(mediaId: String) {
        if (!_state.value.active) return
        val settings = currentSettings()

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext

            // Something the run had already planned for later is brought
            // forward rather than added a second time.
            val planned = previous.indexOf { it.mediaId == mediaId }
            if (planned >= 0) {
                moveInQueue(planned, previous.cursor + 1)
                return@withContext
            }

            val track = scanner.latestTracks.value.firstOrNull { it.mediaId == mediaId }
                ?: return@withContext

            val spliced = previous.insertAfterCursor(entryFor(track, settings))
            plan = spliced
            player.addMediaItem(spliced.indexOf { it.mediaId == mediaId }, track.toMediaItem())
            publish(active = true, shortfall = null)
        }
    }

    /** Adds [mediaIds] to the end of the run, skipping any already in it. */
    suspend fun appendToQueue(mediaIds: List<String>) {
        if (!_state.value.active) return
        val settings = currentSettings()

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext

            val tracks = scanner.latestTracks.value
            val added = mediaIds
                .filter { id -> previous.indexOf { it.mediaId == id } < 0 }
                .mapNotNull { id -> tracks.firstOrNull { it.mediaId == id } }
                .map { entryFor(it, settings) }

            if (added.isEmpty()) return@withContext

            plan = previous.append(added)
            player.addMediaItems(added.map { it.ref.toMediaItem() })
            publish(active = true, shortfall = null)
        }
    }

    /**
     * How a track the plan never chose is played, which is: as well as we can,
     * and audibly rather than not at all.
     *
     * Refusing a song the runner explicitly asked for would be worse than
     * playing it imperfectly, so a tempo needing more stretch than they allow is
     * clamped back to their limit — the same treatment [applyCadence] gives a
     * track the cadence has drifted away from — and one with no tempo at all
     * plays untouched. Both cases are visible to the UI, which derives them from
     * the bpm and the speed rather than from a flag stored here.
     */
    private fun entryFor(track: CuteTrack, settings: RunningSettings): Selected<CuteTrack> {
        val bpm = bpmOf(track)
        val folded = if (bpm == null) {
            UNMATCHED
        } else {
            val fold = fold(bpm, settings.targetCadence.toDouble())
            if (settings.tolerance.accepts(fold)) {
                fold
            } else {
                fold.copy(speed = settings.tolerance.clamp(fold.speed))
            }
        }

        return Selected(
            ref = track,
            fold = folded,
            stretchedDurationMs = (track.durationMs / folded.speed).roundToLong(),
        )
    }

    /**
     * Reorders what is coming up, keeping the plan and the player in step.
     *
     * Both indices are the same number in either, which is the invariant RunPlan
     * exists to hold, so the two edits are the same edit. A move the plan refuses
     * — onto a track already played, or one playing now — must not reach the
     * player either, hence the comparison rather than an unconditional call.
     */
    suspend fun moveInQueue(from: Int, to: Int) {
        if (!_state.value.active) return

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext

            val moved = previous.move(from, to)
            if (moved === previous) return@withContext

            plan = moved
            player.moveMediaItem(from, to)
            publish(active = true, shortfall = null)
        }
    }

    /** Drops a queued track from the run. Refused for one played or playing. */
    suspend fun removeFromQueue(mediaId: String) {
        if (!_state.value.active) return

        withContext(Dispatchers.Main.immediate) {
            val player = player ?: return@withContext
            val previous = plan ?: return@withContext

            val at = previous.indexOf { it.mediaId == mediaId }
            if (at < 0) return@withContext

            val removed = previous.removeAt(at)
            if (removed === previous) return@withContext

            plan = removed
            player.removeMediaItem(at)
            publish(active = true, shortfall = null)
        }
    }

    /**
     * The player moved on. Re-speeds for the new track and extends the window.
     *
     * A track that is not in the plan means something outside running mode took
     * the queue over without routing through [playNow]. Nothing in the UI should
     * be able to do that any more, so reaching this means plan and player have
     * lost sync, and carrying on would be worse than stopping.
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
                .materialiseAll()
            plan = materialised

            if (added.isNotEmpty()) player.addMediaItems(added.map { it.ref.toMediaItem() })

            publish(active = true, shortfall = null)
            materialised.current?.let { ramp(it.fold.speed.toFloat()) }
        }
    }

    /** Ends the run and hands the player back at its normal speed. */
    suspend fun endRun() {
        cadenceTracker.stop()
        librarySubscription?.cancel()
        librarySubscription = null
        plan = null

        val wasActive = _state.value.active

        // Reloaded rather than kept. A tracked run leaves a measured cadence in
        // the live state, and carrying that out of the run would turn one run's
        // measurement into the runner's saved preference by the back door.
        val stored = storedSettings()
        _state.update { it.copy(active = false, summary = null, current = null, settings = stored) }
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
            ?: storedSettings().also { settings ->
                _state.update { if (it.settings == null) it.copy(settings = settings) else it }
            }

    /** What is actually saved, which during a tracked run is not what is playing. */
    private suspend fun storedSettings(): RunningSettings =
        userPreferences.getRunningSettings().first()

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
