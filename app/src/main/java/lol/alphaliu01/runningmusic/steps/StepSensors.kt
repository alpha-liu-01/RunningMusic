package lol.alphaliu01.runningmusic.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler

/**
 * What one physical step-detector sensor can do.
 *
 * Mirrors the fields `dumpsys sensorservice` prints, so the dev screen can show
 * the same facts the shell would and we stop having to trust a shell command
 * taken on one device at one moment.
 */
data class StepSensorInfo(
    val name: String,
    val vendor: String,
    val isWakeUp: Boolean,
    val fifoMaxEventCount: Int,
    val fifoReservedEventCount: Int,
    val reportingMode: String,
) {
    /** A non-zero FIFO is what makes batching, and therefore CPU suspend, possible. */
    val supportsBatching: Boolean get() = fifoMaxEventCount > 0
}

data class StepSensorCapabilities(
    val nonWakeUp: StepSensorInfo?,
    val wakeUp: StepSensorInfo?,
    val hasStepCounter: Boolean,
) {
    val hasAnyDetector: Boolean get() = nonWakeUp != null || wakeUp != null
}

/**
 * Access to the step detector, in both of its variants.
 *
 * The distinction matters more than it looks. `getDefaultSensor(TYPE_STEP_DETECTOR)`
 * returns the *non-wakeup* sensor, which cannot deliver while the application
 * processor is suspended; the wakeup variant, reachable only through the
 * two-argument overload, can. An app that never passes the flag silently gets
 * the one that stops counting when the screen goes off.
 */
class StepSensors(context: Context) {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    fun capabilities(): StepSensorCapabilities = StepSensorCapabilities(
        nonWakeUp = detector(wakeUp = false)?.toInfo(),
        wakeUp = detector(wakeUp = true)?.toInfo(),
        hasStepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
    )

    fun detector(wakeUp: Boolean): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, wakeUp)

    /**
     * The cumulative step counter, which reports a total rather than an event
     * per step.
     *
     * Worth having even where a detector exists, because a total survives what
     * event timing does not: a detector that fires on a fixed hardware tick
     * makes every cadence read the tick, while the same steps counted over the
     * same window come out right.
     */
    fun counter(wakeUp: Boolean): Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, wakeUp)

    fun accelerometer(): Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * @param batchLatencyUs how long the sensor may hold events in its FIFO
     * before waking us. Zero means deliver immediately, which is also the most
     * expensive thing to ask for: it is an interrupt per event whether or not
     * the caller has any use for one that soon.
     *
     * The sampling period is [SensorManager.SENSOR_DELAY_FASTEST] but is ignored
     * for the step detector, which is a special-trigger sensor: it reports when a
     * step happens, not on a schedule.
     */
    fun register(
        listener: SensorEventListener,
        sensor: Sensor,
        batchLatencyUs: Int = 0,
        samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_FASTEST,
        handler: Handler? = null,
    ): Boolean = sensorManager?.registerListener(
        listener,
        sensor,
        samplingPeriodUs,
        batchLatencyUs,
        handler,
    ) == true

    fun unregister(listener: SensorEventListener) {
        sensorManager?.unregisterListener(listener)
    }

    /** Pushes anything sitting in the sensor FIFO through to us now. */
    fun flush(listener: SensorEventListener) {
        sensorManager?.flush(listener)
    }
}

private fun Sensor.toInfo() = StepSensorInfo(
    name = name,
    vendor = vendor,
    isWakeUp = isWakeUpSensor,
    fifoMaxEventCount = fifoMaxEventCount,
    fifoReservedEventCount = fifoReservedEventCount,
    reportingMode = when (reportingMode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special-trigger"
        else -> "unknown($reportingMode)"
    },
)
