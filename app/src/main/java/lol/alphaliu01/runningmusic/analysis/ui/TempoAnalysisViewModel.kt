package lol.alphaliu01.runningmusic.analysis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.datastore.UserPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lol.alphaliu01.runningmusic.analysis.AnalysisProgress
import lol.alphaliu01.runningmusic.analysis.BpmAnalysisManager
import lol.alphaliu01.runningmusic.library.TrackMetadataRepository
import lol.alphaliu01.runningmusic.library.trackKey

/** How much of the library has a usable tempo, and how much never will. */
data class TempoCoverage(
    val total: Int,
    val known: Int,
    /** Examined, and the answer was that we cannot tell. */
    val unreadable: Int
) {
    val pending: Int get() = (total - known - unreadable).coerceAtLeast(0)

    companion object {
        val EMPTY = TempoCoverage(total = 0, known = 0, unreadable = 0)
    }
}

data class TempoAnalysisUi(
    val coverage: TempoCoverage = TempoCoverage.EMPTY,
    val progress: AnalysisProgress = AnalysisProgress.IDLE,
    val onlyWhileCharging: Boolean = false
)

class TempoAnalysisViewModel(
    private val manager: BpmAnalysisManager,
    private val userPreferences: UserPreferences,
    repository: TrackMetadataRepository,
    scanner: AbstractTracksScanner
) : ViewModel() {

    val ui: StateFlow<TempoAnalysisUi> = combine(
        scanner.latestTracks,
        repository.observeByMediaId(),
        manager.progress(),
        userPreferences.getAnalyseOnlyWhileCharging()
    ) { tracks, byMediaId, progress, onlyWhileCharging ->
        // Counted over the tracks that could hold a result at all. A track with
        // no durable key is not pending analysis, it is out of scope, and
        // reporting it as forever-unanalysed would be a permanent false alarm.
        val analysable = tracks.filter { it.trackKey != null }
        val rows = analysable.mapNotNull { byMediaId[it.mediaId] }

        TempoAnalysisUi(
            coverage = TempoCoverage(
                total = analysable.size,
                known = rows.count { it.bpm != null },
                unreadable = rows.count { it.bpm == null && it.analysedAt != null }
            ),
            progress = progress,
            onlyWhileCharging = onlyWhileCharging
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempoAnalysisUi())

    fun start() = enqueue(force = false)

    fun reanalyse() = enqueue(force = true)

    fun cancel() {
        manager.cancel()
    }

    fun setOnlyWhileCharging(value: Boolean) {
        viewModelScope.launch { userPreferences.setAnalyseOnlyWhileCharging(value) }
    }

    /**
     * Reads the preference at the moment of enqueuing rather than holding a copy,
     * so a constraint is never applied from a value the user has since changed.
     */
    private fun enqueue(force: Boolean) {
        viewModelScope.launch {
            manager.start(
                force = force,
                requiresCharging = userPreferences.getAnalyseOnlyWhileCharging().first()
            )
        }
    }
}
