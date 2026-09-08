package lol.alphaliu01.runningmusic.steps.dev

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.alphaliu01.runningmusic.cadence.steps.StepSample
import lol.alphaliu01.runningmusic.cadence.steps.cadenceSpm
import lol.alphaliu01.runningmusic.steps.RecorderConfig
import lol.alphaliu01.runningmusic.steps.RecorderStatus
import lol.alphaliu01.runningmusic.steps.StepRecorder
import lol.alphaliu01.runningmusic.steps.StepRecorderService
import lol.alphaliu01.runningmusic.steps.StepSensorCapabilities
import lol.alphaliu01.runningmusic.steps.hasStepPermission
import java.io.File

private const val RECENT_EVENTS = 12

data class StepRecorderUiState(
    val hasPermission: Boolean = false,
    val recent: List<StepSample> = emptyList(),
    val instantaneousSpm: Double = 0.0,
    val averageSpm: Double = 0.0,
    val outputBytes: Long = 0,
    val existingRecordings: List<File> = emptyList(),
)

class StepRecorderViewModel(
    private val context: Context,
    private val recorder: StepRecorder,
) : ViewModel() {

    /** Queried once; sensor hardware does not change under a running process. */
    val capabilities: StepSensorCapabilities = recorder.capabilities()

    val status = recorder.status

    private val _ui = MutableStateFlow(
        StepRecorderUiState(
            hasPermission = context.hasStepPermission(),
            existingRecordings = recorder.recordings(),
        )
    )
    val ui = _ui.asStateFlow()

    /** What the user asked for; the service is started from a snapshot of it. */
    private val _config = MutableStateFlow(RecorderConfig())
    val config = _config.asStateFlow()

    init {
        viewModelScope.launch {
            recorder.steps.collect { sample ->
                _ui.update {
                    val recent = (it.recent + sample).takeLast(RECENT_EVENTS)
                    it.copy(
                        recent = recent,
                        instantaneousSpm = recent.instantaneousSpm(),
                    )
                }
            }
        }

        // The file grows on a background thread and the average is a function of
        // wall time, so neither can be pushed from an event.
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (status.value.isRecording) refresh()
            }
        }
    }

    fun setConfig(transform: (RecorderConfig) -> RecorderConfig) = _config.update(transform)

    fun onPermissionResult() {
        _ui.update { it.copy(hasPermission = context.hasStepPermission()) }
    }

    fun start() {
        _ui.update { it.copy(recent = emptyList(), instantaneousSpm = 0.0, averageSpm = 0.0) }
        StepRecorderService.start(context, _config.value)
    }

    fun stop() {
        StepRecorderService.stop(context)
        // The service tears down asynchronously; give the final flush a moment
        // before listing files, or the just-finished run shows a stale size.
        viewModelScope.launch {
            delay(500)
            _ui.update { it.copy(existingRecordings = recorder.recordings()) }
            refresh()
        }
    }

    private fun refresh() {
        val current: RecorderStatus = status.value
        val bytes = current.outputPath?.let { File(it).length() } ?: 0
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - current.startedAtElapsedNs
        val average = if (current.startedAtElapsedNs > 0 && elapsedNs > 0) {
            current.stepCount * 60_000_000_000.0 / elapsedNs
        } else {
            0.0
        }

        _ui.update { it.copy(outputBytes = bytes, averageSpm = average) }
    }
}

/** From the most recent interval only. Smoothing is the control loop's job, not this screen's. */
private fun List<StepSample>.instantaneousSpm(): Double {
    if (size < 2) return 0.0
    return cadenceSpm(this[size - 1].sensorTimestampNs - this[size - 2].sensorTimestampNs)
}
