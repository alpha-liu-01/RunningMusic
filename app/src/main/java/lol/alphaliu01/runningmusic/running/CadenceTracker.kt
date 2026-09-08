package lol.alphaliu01.runningmusic.running

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.sosauce.chocola.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lol.alphaliu01.runningmusic.cadence.steps.CadenceLoop
import lol.alphaliu01.runningmusic.cadence.steps.Motion
import lol.alphaliu01.runningmusic.cadence.steps.RecordingHeader
import lol.alphaliu01.runningmusic.cadence.steps.StepRecordingWriter
import lol.alphaliu01.runningmusic.cadence.steps.StepSample
import lol.alphaliu01.runningmusic.cadence.steps.TrackingMode
import lol.alphaliu01.runningmusic.steps.RECORDINGS_DIR
import lol.alphaliu01.runningmusic.steps.StepSensorCapabilities
import lol.alphaliu01.runningmusic.steps.StepSensors
import lol.alphaliu01.runningmusic.steps.hasStepPermission
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CadenceTracker"

/**
 * How often the loop is advanced when nothing is arriving.
 *
 * Only stall detection depends on this. A loop told about steps and nothing else
 * cannot notice their absence, and noticing their absence is how a traffic light
 * is told from a runner who never slowed down.
 */
private const val TICK_MS = 5_000L

/** Why the tracker is not driving the target, when it is not. */
enum class CadenceUnavailable {
    /** No step detector of either variant. Most emulators, some cheap phones. */
    NO_SENSOR,

    /** ACTIVITY_RECOGNITION was refused, or never asked for. */
    NO_PERMISSION,

    /** The sensor exists, is permitted, and `registerListener` still said no. */
    REFUSED,
}

data class CadenceTrackerState(
    val active: Boolean = false,
    val unavailable: CadenceUnavailable? = null,
    val sensorName: String? = null,
    val wakeUp: Boolean = false,
    val motion: Motion = Motion.STARTING,
    val measuredSpm: Double? = null,
    val locked: Boolean = false,
    val steps: Int = 0,
    val recordingPath: String? = null,
)

/**
 * The sensor half of cadence tracking: everything the control loop is not
 * allowed to know about.
 *
 * All it does is register the right sensor, collect timestamps, advance
 * [CadenceLoop] on a timer, and report when the loop's target moves. Every
 * decision worth arguing about lives in the loop, where it can be replayed.
 *
 * The wakeup detector is not an optimisation here, it is the feature. The
 * one-argument `getDefaultSensor(TYPE_STEP_DETECTOR)` returns the non-wakeup
 * variant, which stops delivering the moment the application processor
 * suspends, which on a run is roughly always; the sensor spike measured the
 * wakeup variant delivering through 61% CPU suspend under the same
 * `mediaPlayback` foreground service that PlaybackService already runs.
 *
 * No wakelock, deliberately. Holding one would make this work by keeping the
 * phone awake for the length of a run, which is a battery bill disguised as a
 * fix.
 */
class CadenceTracker(
    private val context: Context,
    private val stepSensors: StepSensors,
    private val scope: CoroutineScope,
) : SensorEventListener {

    private val _state = MutableStateFlow(CadenceTrackerState())
    val state: StateFlow<CadenceTrackerState> = _state.asStateFlow()

    private val pendingLock = Any()
    private val pending = mutableListOf<Long>()

    private var sensorThread: HandlerThread? = null
    private var job: Job? = null
    private var nudge = Channel<Unit>(Channel.CONFLATED)

    private val writeLock = Any()
    private var writer: BufferedWriter? = null
    private var recording: StepRecordingWriter? = null

    fun capabilities(): StepSensorCapabilities = stepSensors.capabilities()

    /**
     * Starts driving [onTarget] from the step detector.
     *
     * @param initialTarget where the slider is, which is what the music plays at
     * until the opening measurement completes. A run cannot wait in silence for
     * a minute while the loop makes up its mind.
     * @param onTarget the loop's raw measured target. Deliberately not snapped
     * to anything the library covers: that needs the library, and pulling the
     * library in here would put the whole of running mode behind the sensor.
     *
     * @return false if the target is going to have to stay where the runner put
     * it, with [CadenceTrackerState.unavailable] saying why.
     */
    fun start(mode: TrackingMode, initialTarget: Int, onTarget: (Int) -> Unit): Boolean {
        stop()

        if (mode == TrackingMode.MANUAL) return false

        if (!context.hasStepPermission()) {
            _state.value = CadenceTrackerState(unavailable = CadenceUnavailable.NO_PERMISSION)
            return false
        }

        // Falls back rather than refusing. A non-wakeup detector will miss steps
        // with the screen off, but a run that tracks badly beats one that does
        // not track at all, and the fallback is visible in the state.
        val sensor = stepSensors.detector(wakeUp = true) ?: stepSensors.detector(wakeUp = false)
        if (sensor == null) {
            _state.value = CadenceTrackerState(unavailable = CadenceUnavailable.NO_SENSOR)
            return false
        }

        // Events land here rather than on the main thread. A wakeup sensor
        // rousing a suspended CPU hands over about a second of steps at once, and
        // that should not be a frame's worth of work on the UI thread.
        val thread = HandlerThread("cadence-tracker").apply { start() }
        val registered = stepSensors.register(
            listener = this,
            sensor = sensor,
            batchLatencyUs = 0,
            handler = Handler(thread.looper),
        )

        if (!registered) {
            thread.quitSafely()
            _state.value = CadenceTrackerState(unavailable = CadenceUnavailable.REFUSED)
            return false
        }

        sensorThread = thread
        synchronized(pendingLock) { pending.clear() }
        nudge = Channel(Channel.CONFLATED)

        val recordingFile = startRecording(sensor)

        _state.value = CadenceTrackerState(
            active = true,
            sensorName = sensor.name,
            wakeUp = sensor.isWakeUpSensor,
            recordingPath = recordingFile?.absolutePath,
        )

        Log.i(TAG, "Tracking ${sensor.name} (wakeUp=${sensor.isWakeUpSensor}), mode=$mode")
        job = scope.launch { run(mode, initialTarget, onTarget) }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null

        if (sensorThread != null) {
            // Drains whatever the FIFO is still holding. Without it the last few
            // seconds of a run are simply thrown away, which matters for the
            // recording even though it no longer matters for the target.
            stepSensors.flush(this)
            stepSensors.unregister(this)
            sensorThread?.quitSafely()
            sensorThread = null
        }

        stopRecording()
        synchronized(pendingLock) { pending.clear() }
        _state.value = CadenceTrackerState()
    }

    private suspend fun run(mode: TrackingMode, initialTarget: Int, onTarget: (Int) -> Unit) {
        var loop = CadenceLoop(target = initialTarget, mode = mode)

        while (currentCoroutineContext().isActive) {
            // Woken by delivery or by the timer, whichever comes first, so a
            // stop is noticed within a tick and a step is acted on at once.
            withTimeoutOrNull(TICK_MS) { nudge.receive() }

            val steps = synchronized(pendingLock) {
                if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
            }

            val previous = loop
            loop = loop.advance(SystemClock.elapsedRealtimeNanos(), steps)

            _state.update {
                it.copy(
                    motion = loop.motion,
                    measuredSpm = loop.measuredSpm,
                    locked = loop.locked,
                    steps = it.steps + steps.size,
                )
            }

            if (loop.target != previous.target) {
                Log.i(
                    TAG,
                    "Target ${previous.target} -> ${loop.target} spm " +
                        "(measured ${loop.measuredSpm?.let { spm -> "%.1f".format(spm) }})",
                )
                onTarget(loop.target)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return

        synchronized(pendingLock) { pending += event.timestamp }
        nudge.trySend(Unit)

        // The same run that drives the music writes the fixture that explains it
        // afterwards. Every run taken without this is a fixture not captured.
        val received = SystemClock.elapsedRealtimeNanos()
        synchronized(writeLock) {
            recording?.writeStep(StepSample(event.timestamp, received, uptimeNanos()))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Debug builds only: a release APK has no business writing files on a run. */
    private fun startRecording(sensor: Sensor): File? {
        if (!BuildConfig.DEBUG) return null

        return runCatching {
            val dir = File(context.getExternalFilesDir(null), RECORDINGS_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val variant = if (sensor.isWakeUpSensor) "wake" else "nowake"
            val file = File(dir, "$stamp-$variant-run.txt")

            val buffered = BufferedWriter(FileWriter(file))
            val out = StepRecordingWriter(buffered)
            out.writeHeader(
                RecordingHeader(
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidRelease = Build.VERSION.RELEASE,
                    sdkInt = Build.VERSION.SDK_INT,
                    sensorName = sensor.name,
                    sensorVendor = sensor.vendor,
                    isWakeUp = sensor.isWakeUpSensor,
                    fifoMaxEvents = sensor.fifoMaxEventCount,
                    fgsType = "mediaPlayback",
                    batchLatencyUs = 0,
                    startWallClockMs = System.currentTimeMillis(),
                    startElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                )
            )
            buffered.flush()

            synchronized(writeLock) {
                writer = buffered
                recording = out
            }
            file
        }.onFailure { Log.w(TAG, "Could not open a recording; tracking anyway", it) }
            .getOrNull()
    }

    private fun stopRecording() {
        synchronized(writeLock) {
            runCatching {
                writer?.flush()
                writer?.close()
            }.onFailure { Log.w(TAG, "Closing the recording failed", it) }
            writer = null
            recording = null
        }
    }
}

/** The clock that stops when the CPU does, for the recording's suspend column. */
private fun uptimeNanos(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SystemClock.uptimeNanos()
    } else {
        SystemClock.uptimeMillis() * 1_000_000
    }
