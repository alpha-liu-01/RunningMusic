package lol.alphaliu01.runningmusic.running.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.datastore.DEFAULT_RUN_LENGTH_MINUTES
import com.sosauce.chocola.data.datastore.DEFAULT_TARGET_CADENCE
import com.sosauce.chocola.data.datastore.RunningSettings
import com.sosauce.chocola.data.models.CuteTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import lol.alphaliu01.runningmusic.cadence.CadenceSuggestion
import lol.alphaliu01.runningmusic.cadence.Coverage
import lol.alphaliu01.runningmusic.cadence.RunQueue
import lol.alphaliu01.runningmusic.cadence.ToleranceBand
import lol.alphaliu01.runningmusic.cadence.coverageAt
import lol.alphaliu01.runningmusic.cadence.selectForRun
import lol.alphaliu01.runningmusic.cadence.steps.LoopConfig
import lol.alphaliu01.runningmusic.cadence.steps.TrackingMode
import lol.alphaliu01.runningmusic.cadence.suggestCadence
import lol.alphaliu01.runningmusic.running.CadenceTrackerState
import lol.alphaliu01.runningmusic.running.RunningModeManager
import lol.alphaliu01.runningmusic.running.RunningState

/**
 * The cadences the app will match, from a stroll to a sprint.
 *
 * Taken from the control loop rather than restated. These two had already
 * drifted into separate copies of 140..200, which is how the slider could offer
 * a cadence the loop would refuse to measure.
 */
val CADENCE_RANGE = LoopConfig().cadenceRange

/** Long enough to be worth planning, short of an ultramarathon. */
val RUN_LENGTH_RANGE = 5..180

/**
 * How far each half of the stretch tolerance may be pushed.
 *
 * The top is [ToleranceBand.EVERYTHING], where the octave windows meet and every
 * track in the library is already accepted. Offering more would be offering a
 * number that cannot change the answer.
 */
val STRETCH_RANGE = 1.0..ToleranceBand.EVERYTHING.widest

/** The same limit as whole percent, which is how the screen puts it. */
val STRETCH_PERCENT_RANGE = 0..((ToleranceBand.EVERYTHING.widest - 1.0) * 100).toInt()

/** A stretch ratio as the percentage on the slider: 1.15 becomes 15. */
fun stretchPercent(ratio: Double): Int = ((ratio - 1.0) * 100).roundToInt()

/** The inverse, for a slider handing back whole percent. */
fun stretchRatio(percent: Int): Double = 1.0 + percent / 100.0

data class RunningUi(
    val settings: RunningSettings = RunningSettings(
        targetCadence = DEFAULT_TARGET_CADENCE,
        runLengthMinutes = DEFAULT_RUN_LENGTH_MINUTES,
    ),
    val coverage: Coverage = EMPTY_COVERAGE,
    val suggestion: CadenceSuggestion? = null,
    /**
     * The run these settings would produce, planned but not started.
     *
     * Worth computing up front rather than describing the tolerance ceiling,
     * because the ceiling is the worst case the app would ever allow and the
     * actual answer is usually far better. Selecting from a few hundred tracks
     * is cheap enough to redo on every drag of the slider.
     */
    val plan: RunQueue<CuteTrack> = EMPTY_PLAN,
    val run: RunningState = RunningState(),
    /** Tracks in the library with no tempo, which no cadence can reach. */
    val withoutTempo: Int = 0,
    /** What the step detector is doing, if it is doing anything. */
    val tracking: CadenceTrackerState = CadenceTrackerState(),
    /** False on hardware with no step detector, where the slider is the only source. */
    val hasStepDetector: Boolean = true,
)

/**
 * The run screen's state.
 *
 * The screen is a control panel, not the feature: everything it starts is owned
 * by [RunningModeManager], which keeps going once this view model is gone. What
 * lives here is the slider position and the answer to "what would this cadence
 * give me", which only matters while somebody is looking.
 *
 * The cadence being previewed is deliberately separate from the one a run is
 * using. Dragging the slider before a run has started must not write a setting
 * on every frame, so [preview] leads and [commit] follows when the drag ends.
 */
class RunningViewModel(
    private val manager: RunningModeManager,
    scanner: AbstractTracksScanner,
) : ViewModel() {

    private val preview = MutableStateFlow<RunningSettings?>(null)

    private val hasStepDetector = manager.hasStepDetector()

    val ui: StateFlow<RunningUi> = combine(
        manager.state,
        manager.library,
        scanner.latestTracks,
        preview,
        manager.tracking,
    ) { run, library, allTracks, previewed, tracking ->
        val settings = previewed
            ?: run.settings
            ?: RunningSettings(DEFAULT_TARGET_CADENCE, DEFAULT_RUN_LENGTH_MINUTES)
        val cadence = settings.targetCadence.toDouble()

        RunningUi(
            settings = settings,
            coverage = coverageAt(library, cadence, settings.tolerance),
            suggestion = suggestCadence(library, cadence, band = settings.tolerance),
            plan = selectForRun(
                library = library,
                targetCadence = cadence,
                runLengthMs = settings.runLengthMinutes * 60_000L,
                band = settings.tolerance,
            ),
            run = run,
            withoutTempo = (allTracks.size - library.size).coerceAtLeast(0),
            tracking = tracking,
            hasStepDetector = hasStepDetector,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunningUi())

    init {
        viewModelScope.launch { preview.value = manager.currentSettings() }

        // A tracked run moves the target without anyone touching the slider, and
        // a slider that did not follow would be reporting the cadence the run
        // started at for the rest of it.
        viewModelScope.launch {
            manager.state
                .map { if (it.active) it.settings?.targetCadence else null }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { cadence -> preview.value = current().copy(targetCadence = cadence) }
        }
    }

    /** Follows the slider. Cheap, and writes nothing. */
    fun previewCadence(targetCadence: Int) {
        preview.value = current().copy(targetCadence = targetCadence.coerceIn(CADENCE_RANGE))
    }

    fun previewRunLength(minutes: Int) {
        preview.value = current().copy(runLengthMinutes = minutes.coerceIn(RUN_LENGTH_RANGE))
    }

    /**
     * The two halves of the stretch tolerance move independently, so each one
     * updates its own side and leaves the other where the listener put it.
     */
    fun previewMaxSpeedUp(ratio: Double) {
        val tolerance = current().tolerance.copy(maxSpeedUp = ratio.coerceIn(STRETCH_RANGE))
        preview.value = current().copy(tolerance = tolerance)
    }

    fun previewMaxSlowDown(ratio: Double) {
        val tolerance = current().tolerance.copy(maxSlowDown = ratio.coerceIn(STRETCH_RANGE))
        preview.value = current().copy(tolerance = tolerance)
    }

    fun commitTolerance() {
        val tolerance = current().tolerance
        viewModelScope.launch { manager.setTolerance(tolerance) }
    }

    /**
     * The slider was let go. Persists the setting, and re-speeds a run already
     * in progress.
     */
    fun commitCadence() {
        val cadence = current().targetCadence
        viewModelScope.launch { manager.setCadence(cadence) }
    }

    fun commitRunLength() {
        val minutes = current().runLengthMinutes
        viewModelScope.launch { manager.setRunLength(minutes) }
    }

    /** Applied at once rather than on a commit: it is a tap, not a drag. */
    fun setTrackingMode(mode: TrackingMode) {
        preview.value = current().copy(trackingMode = mode)
        viewModelScope.launch { manager.setTrackingMode(mode) }
    }

    fun useSuggestedCadence() {
        val suggested = ui.value.suggestion?.best?.cadence?.toInt() ?: return
        previewCadence(suggested)
        commitCadence()
    }

    fun start() {
        val settings = current()
        viewModelScope.launch { manager.startRun(settings) }
    }

    fun stop() {
        viewModelScope.launch { manager.endRun() }
    }

    private fun current(): RunningSettings = preview.value
        ?: manager.state.value.settings
        ?: RunningSettings(DEFAULT_TARGET_CADENCE, DEFAULT_RUN_LENGTH_MINUTES)
}

private val EMPTY_COVERAGE = Coverage(accepted = 0, total = 0, stretchedMs = 0, rawMs = 0)
private val EMPTY_PLAN = RunQueue<CuteTrack>(
    tracks = emptyList(),
    filledMs = 0,
    shortfallMs = 0,
    worstBand = null,
)
