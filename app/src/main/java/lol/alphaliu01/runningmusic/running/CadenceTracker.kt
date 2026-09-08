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
import lol.alphaliu01.runningmusic.cadence.steps.CounterReading
import lol.alphaliu01.runningmusic.cadence.steps.CounterSample
import lol.alphaliu01.runningmusic.cadence.steps.LoopConfig
import lol.alphaliu01.runningmusic.cadence.steps.Motion
import lol.alphaliu01.runningmusic.cadence.steps.RecordingHeader
import lol.alphaliu01.runningmusic.cadence.steps.StepRecordingWriter
import lol.alphaliu01.runningmusic.cadence.steps.StepSample
import lol.alphaliu01.runningmusic.cadence.steps.StepSource
import lol.alphaliu01.runningmusic.cadence.steps.stepTimestampsFrom
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

    /**
     * Whether cadence is being counted from `TYPE_STEP_COUNTER` rather than
     * timed from detector events. False means this phone has no counter and the
     * detector had better be honest about firing once per step.
     */
    val counting: Boolean = false,
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
 * Cadence is *counted*, not timed, wherever a step counter exists. Timing the
 * gaps between detector events is the obvious approach, and it is only ever as
 * good as the detector: a OnePlus PKX110 declares its detector SPECIAL_TRIGGER,
 * meaning one event per step, then fires it on a 994.3 ms hardware tick no
 * matter how fast its owner is moving. Every walk and every run on that phone
 * measured 60.3 spm. See [stepTimestampsFrom].
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

    /**
     * The last counter reading, and whether the counter is the cadence source.
     *
     * Guarded by [pendingLock] because the reading is only ever touched from the
     * sensor thread alongside [pending], and the two have to move together: a
     * reading consumed without its steps being queued loses them.
     */
    private var lastCount: CounterReading? = null
    private var counting = false

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

        synchronized(pendingLock) {
            pending.clear()
            lastCount = null
            counting = false
        }
        nudge = Channel(Channel.CONFLATED)

        // Opened before anything is registered, so that the counter's opening
        // value is in the file. A counter reports on change and reports once on
        // registration, and that first reading is the baseline every later delta
        // is measured against; a recording missing it starts a reading late.
        val recordingFile = startRecording(sensor)

        val registered = stepSensors.register(
            listener = this,
            sensor = sensor,
            batchLatencyUs = 0,
            handler = Handler(thread.looper),
        )

        if (!registered) {
            stopRecording()
            thread.quitSafely()
            _state.value = CadenceTrackerState(unavailable = CadenceUnavailable.REFUSED)
            return false
        }

        // The counter is the measurement and the detector is the heartbeat.
        // Registering both costs one more listener and buys a cadence that does
        // not depend on the detector firing once per step, which is a promise
        // real hardware does not always keep.
        val counter = stepSensors.counter(wakeUp = true) ?: stepSensors.counter(wakeUp = false)
        val countingNow = counter != null && stepSensors.register(
            listener = this,
            sensor = counter,
            batchLatencyUs = 0,
            handler = Handler(thread.looper),
        )

        sensorThread = thread
        synchronized(pendingLock) {
            counting = countingNow
            // The handful of detector events from the moment between the two
            // registrations are the wrong kind of step to hand a counting loop.
            if (countingNow) pending.clear()
        }

        _state.value = CadenceTrackerState(
            active = true,
            sensorName = sensor.name,
            wakeUp = sensor.isWakeUpSensor,
            recordingPath = recordingFile?.absolutePath,
            counting = countingNow,
        )

        Log.i(
            TAG,
            "Tracking ${sensor.name} (wakeUp=${sensor.isWakeUpSensor}), mode=$mode, " +
                if (countingNow) "counting from ${counter?.name}" else "timing detector events",
        )
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
        synchronized(pendingLock) {
            pending.clear()
            lastCount = null
            counting = false
        }
        _state.value = CadenceTrackerState()
    }

    private suspend fun run(mode: TrackingMode, initialTarget: Int, onTarget: (Int) -> Unit) {
        val source = synchronized(pendingLock) {
            if (counting) StepSource.COUNTED else StepSource.TIMED
        }
        var loop = CadenceLoop(
            target = initialTarget,
            mode = mode,
            config = LoopConfig(source = source),
        )

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
        // The same run that drives the music writes the fixture that explains it
        // afterwards. Every run taken without this is a fixture not captured.
        val received = SystemClock.elapsedRealtimeNanos()

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // Queued for the loop only when there is no counter to do it
                // better. Recorded either way: a detector that disagrees with the
                // counter is the most useful thing a recording can contain.
                synchronized(pendingLock) {
                    if (!counting) pending += event.timestamp
                }

                synchronized(writeLock) {
                    recording?.writeStep(
                        StepSample(
                            sensorTimestampNs = event.timestamp,
                            receivedElapsedRealtimeNs = received,
                            receivedUptimeNs = uptimeNanos(),
                            reportedSteps = event.values.firstOrNull(),
                        )
                    )
                }
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val value = event.values.firstOrNull() ?: return
                val reading = CounterReading(event.timestamp, value)

                synchronized(pendingLock) {
                    pending += stepTimestampsFrom(lastCount, reading)
                    lastCount = reading
                }

                synchronized(writeLock) {
                    recording?.writeCounter(CounterSample(event.timestamp, received, value))
                }
            }

            else -> return
        }

        nudge.trySend(Unit)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Off in a shipped release: an APK has no business writing files on a run
     * unless it was built to. On in debug, and in a release built with
     * `-Prunningmusic.recordRuns=true` for a test run that has to be
     * diagnosable afterwards.
     */
    private fun startRecording(sensor: Sensor): File? {
        if (!BuildConfig.RECORD_RUNS) return null

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
