package lol.alphaliu01.runningmusic.steps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sosauce.chocola.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val CHANNEL_ID = "step-recorder"
private const val NOTIFICATION_ID = 4201

private const val EXTRA_WAKE_UP = "wakeUp"
private const val EXTRA_BATCH_LATENCY_US = "batchLatencyUs"
private const val EXTRA_ACCELEROMETER = "accelerometer"
private const val EXTRA_FGS_TYPE = "fgsType"

/**
 * Runs [StepRecorder] as a foreground service for the duration of a test run.
 *
 * Separate from `PlaybackService` on purpose. The spike's question is whether a
 * backgrounded, screen-off app keeps receiving step events, and answering it
 * needs the foreground-service type to be a variable: `mediaPlayback` is what
 * the real app will eventually use, `health` is the fallback if that turns out
 * not to be enough. Both come from intent extras so the four-configuration
 * experiment runs without a rebuild in between.
 *
 * **It deliberately holds no wakelock.** Holding one would keep the CPU awake
 * and every configuration would pass, which would tell us nothing about what
 * happens in the field.
 */
class StepRecorderService : Service(), KoinComponent {

    private val recorder by inject<StepRecorder>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = intent.toRecorderConfig()
        createChannel()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(config),
            foregroundTypeOf(config.fgsType),
        )

        if (recorder.start(config) == null) stopSelf()

        // Not sticky: a restart by the system would begin a second recording with
        // no memory of the first, silently splitting one run across two files.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Step recorder (debug)",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Deliberately static text.
     *
     * A live step count here would be nicer to look at and would also wake the
     * application processor on every update, which is precisely the thing being
     * measured. The dev screen shows the running count while the app is open; the
     * notification only has to prove the service is alive and say how it was set up.
     */
    private fun buildNotification(config: RecorderConfig) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.speed_rounded)
            .setContentTitle("Recording steps")
            .setContentText(config.describe())
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun foregroundTypeOf(fgsType: String): Int = when {
        fgsType == FGS_TYPE_HEALTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        // Below API 29 the concept does not exist and the type is ignored.
        else -> 0
    }

    companion object {
        fun start(context: Context, config: RecorderConfig) {
            val intent = Intent(context, StepRecorderService::class.java)
                .putExtra(EXTRA_WAKE_UP, config.useWakeUpSensor)
                .putExtra(EXTRA_BATCH_LATENCY_US, config.batchLatencyUs)
                .putExtra(EXTRA_ACCELEROMETER, config.recordAccelerometer)
                .putExtra(EXTRA_FGS_TYPE, config.fgsType)

            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepRecorderService::class.java))
        }
    }
}

fun RecorderConfig.describe(): String = buildString {
    append(if (useWakeUpSensor) "wakeup" else "non-wakeup")
    append(" · ").append(fgsType)
    append(" · ")
    append(if (batchLatencyUs > 0) "batched ${batchLatencyUs / 1_000_000}s" else "unbatched")
    if (recordAccelerometer) append(" · accel")
}

private fun Intent.toRecorderConfig() = RecorderConfig(
    useWakeUpSensor = getBooleanExtra(EXTRA_WAKE_UP, true),
    batchLatencyUs = getIntExtra(EXTRA_BATCH_LATENCY_US, 0),
    recordAccelerometer = getBooleanExtra(EXTRA_ACCELEROMETER, false),
    fgsType = getStringExtra(EXTRA_FGS_TYPE) ?: FGS_TYPE_MEDIA_PLAYBACK,
)
