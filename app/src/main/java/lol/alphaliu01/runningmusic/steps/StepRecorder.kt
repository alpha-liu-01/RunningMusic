package lol.alphaliu01.runningmusic.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.alphaliu01.runningmusic.cadence.steps.AccelSample
import lol.alphaliu01.runningmusic.cadence.steps.RecordingHeader
import lol.alphaliu01.runningmusic.cadence.steps.StepRecordingWriter
import lol.alphaliu01.runningmusic.cadence.steps.StepSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "StepRecorder"
private const val FLUSH_INTERVAL_MS = 5_000L

/** Where recordings land, relative to `getExternalFilesDir`. */
const val RECORDINGS_DIR = "step-recordings"

const val FGS_TYPE_MEDIA_PLAYBACK = "mediaPlayback"
const val FGS_TYPE_HEALTH = "health"

data class RecorderConfig(
    val useWakeUpSensor: Boolean = true,
    val batchLatencyUs: Int = 0,
    val recordAccelerometer: Boolean = false,
    val fgsType: String = FGS_TYPE_MEDIA_PLAYBACK,
)

data class RecorderStatus(
    val isRecording: Boolean = false,
    val config: RecorderConfig? = null,
    val sensorName: String? = null,
    val actuallyWakeUp: Boolean = false,
    val outputPath: String? = null,
    val stepCount: Int = 0,
    val accelCount: Int = 0,
    val startedAtElapsedNs: Long = 0,
    val error: String? = null,
)

/**
 * Records every step the hardware reports, with both clocks, to a file that
 * [lol.alphaliu01.runningmusic.cadence.steps.parseStepRecording] can read back.
 *
 * The point of the whole exercise is a fixture: one real run outdoors produces a
 * file the control loop can then be developed against indoors, forever.
 *
 * Two implementation choices are load-bearing:
 *
 * Sensor events are delivered onto a dedicated [HandlerThread] and written from
 * there. Delivery would otherwise land on the main thread, where a flushed batch
 * of a few thousand buffered events would mean a few thousand file appends
 * during a frame.
 *
 * Output goes under `getExternalFilesDir` rather than internal storage. That is
 * not a preference: `adb shell run-as` is broken on the target device (SELinux
 * has no `seapp_context` for `targetSdkVersion=37` on an API 36 build), so
 * internal app storage cannot be read over adb at all, and a recording we cannot
 * pull is not a fixture.
 */
class StepRecorder(
    private val context: Context,
    private val stepSensors: StepSensors,
    private val ioScope: CoroutineScope,
) : SensorEventListener {

    private val _steps = MutableSharedFlow<StepSample>(extraBufferCapacity = 256)

    /** Live feed for the dev screen. Replay-free; the file is the record of truth. */
    val steps: SharedFlow<StepSample> = _steps.asSharedFlow()

    private val _status = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = _status.asStateFlow()

    private val writeLock = Any()
    private var writer: BufferedWriter? = null
    private var recordingWriter: StepRecordingWriter? = null
    private var outputFile: File? = null
    private var sensorThread: HandlerThread? = null
    private var flushJob: Job? = null
    private var stepCount = 0
    private var accelCount = 0

    fun capabilities(): StepSensorCapabilities = stepSensors.capabilities()

    /** @return the file being written, or null with [RecorderStatus.error] set. */
    fun start(config: RecorderConfig): File? {
        if (_status.value.isRecording) return outputFile

        if (!context.hasStepPermission()) {
            _status.value = RecorderStatus(error = "ACTIVITY_RECOGNITION not granted")
            return null
        }

        // Falls back to whatever variant exists rather than refusing, and the
        // header records which one we actually got, so a recording is never
        // ambiguous about what produced it.
        val sensor = stepSensors.detector(config.useWakeUpSensor)
            ?: stepSensors.detector(!config.useWakeUpSensor)
        if (sensor == null) {
            _status.value = RecorderStatus(error = "No step detector on this device")
            return null
        }

        val file = newOutputFile(config)
        val startedAt = SystemClock.elapsedRealtimeNanos()

        synchronized(writeLock) {
            val buffered = BufferedWriter(FileWriter(file))
            val recording = StepRecordingWriter(buffered)
            recording.writeHeader(
                RecordingHeader(
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidRelease = Build.VERSION.RELEASE,
                    sdkInt = Build.VERSION.SDK_INT,
                    sensorName = sensor.name,
                    sensorVendor = sensor.vendor,
                    isWakeUp = sensor.isWakeUpSensor,
                    fifoMaxEvents = sensor.fifoMaxEventCount,
                    fgsType = config.fgsType,
                    batchLatencyUs = config.batchLatencyUs,
                    startWallClockMs = System.currentTimeMillis(),
                    startElapsedRealtimeNs = startedAt,
                )
            )
            buffered.flush()
            writer = buffered
            recordingWriter = recording
            outputFile = file
            stepCount = 0
            accelCount = 0
        }

        val thread = HandlerThread("step-recorder").apply { start() }
        val handler = Handler(thread.looper)
        sensorThread = thread

        val registered = stepSensors.register(
            listener = this,
            sensor = sensor,
            batchLatencyUs = config.batchLatencyUs,
            handler = handler,
        )
        if (!registered) {
            closeQuietly()
            thread.quitSafely()
            sensorThread = null
            _status.value = RecorderStatus(error = "registerListener refused ${sensor.name}")
            return null
        }

        if (config.recordAccelerometer) {
            stepSensors.accelerometer()?.let {
                stepSensors.register(
                    listener = this,
                    sensor = it,
                    batchLatencyUs = config.batchLatencyUs,
                    samplingPeriodUs = ACCEL_PERIOD_US,
                    handler = handler,
                )
            }
        }

        // A recording is only useful if it survives the process being killed,
        // which for a background service on a run is a likely ending.
        flushJob = ioScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                synchronized(writeLock) { writer?.flush() }
            }
        }

        _status.value = RecorderStatus(
            isRecording = true,
            config = config,
            sensorName = sensor.name,
            actuallyWakeUp = sensor.isWakeUpSensor,
            outputPath = file.absolutePath,
            startedAtElapsedNs = startedAt,
        )
        Log.i(TAG, "Recording ${sensor.name} (wakeUp=${sensor.isWakeUpSensor}) to $file")
        return file
    }

    fun stop() {
        if (!_status.value.isRecording) return

        // Drain the FIFO before unregistering, or a batched run throws away
        // everything the sensor was still holding.
        stepSensors.flush(this)
        stepSensors.unregister(this)

        flushJob?.cancel()
        flushJob = null
        sensorThread?.quitSafely()
        sensorThread = null
        closeQuietly()

        _status.value = _status.value.copy(isRecording = false)
        Log.i(TAG, "Stopped after $stepCount steps, $accelCount accel samples")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val receivedNs = SystemClock.elapsedRealtimeNanos()
        val uptimeNs = uptimeNanos()

        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                val sample = StepSample(event.timestamp, receivedNs, uptimeNs)
                synchronized(writeLock) {
                    recordingWriter?.writeStep(sample) ?: return
                    stepCount++
                }
                _steps.tryEmit(sample)
                _status.value = _status.value.copy(stepCount = stepCount)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(writeLock) {
                    recordingWriter?.writeAccel(
                        AccelSample(
                            event.timestamp,
                            event.values[0],
                            event.values[1],
                            event.values[2],
                        )
                    ) ?: return
                    accelCount++
                }
                // Not mirrored into status on every sample; at 50 Hz that would
                // be 50 recompositions a second for a number nobody reads.
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun recordings(): List<File> =
        recordingsDir().listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun recordingsDir(): File =
        File(context.getExternalFilesDir(null), RECORDINGS_DIR).apply { mkdirs() }

    private fun newOutputFile(config: RecorderConfig): File {
        // The configuration is in the filename because the experiment runs four
        // of these back to back and they are pulled as a batch.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val variant = if (config.useWakeUpSensor) "wake" else "nowake"
        val batch = if (config.batchLatencyUs > 0) "batch${config.batchLatencyUs / 1_000_000}s" else "nobatch"
        return File(recordingsDir(), "$stamp-$variant-$batch-${config.fgsType}.txt")
    }

    private fun closeQuietly() {
        synchronized(writeLock) {
            runCatching { writer?.flush(); writer?.close() }
                .onFailure { Log.w(TAG, "Closing recording failed", it) }
            writer = null
            recordingWriter = null
        }
    }

    private companion object {
        /** 50 Hz, enough to see the shape of a footfall without flooding the file. */
        const val ACCEL_PERIOD_US = 20_000
    }
}

/**
 * The clock that stops when the CPU does.
 *
 * Paired with `elapsedRealtimeNanos`, which keeps running through suspend, this
 * is what makes "was the phone actually asleep" a measurement rather than an
 * inference from delivery timing.
 */
private fun uptimeNanos(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SystemClock.uptimeNanos()
    } else {
        SystemClock.uptimeMillis() * 1_000_000
    }
