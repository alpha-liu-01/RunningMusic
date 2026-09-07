package lol.alphaliu01.runningmusic.analysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sosauce.chocola.R
import com.sosauce.chocola.data.AbstractTracksScanner
import com.sosauce.chocola.data.models.CuteTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import lol.alphaliu01.runningmusic.library.TrackMetadataRepository
import lol.alphaliu01.runningmusic.library.trackKey
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Works through the library establishing tempos.
 *
 * A foreground worker because the user pressed a button and expects the pass to
 * finish while they get on with something else, and a library of any size takes
 * longer than the app will be in front of them.
 *
 * Every result is committed as it lands rather than at the end, which makes the
 * job resumable for nothing: interrupted, cancelled, or cut short by the
 * platform, the next run simply finds fewer tracks left to do. That matters more
 * than it sounds, because Android 15 caps a dataSync foreground service at six
 * hours a day and a large library on a slow phone can reach it.
 *
 * A [KoinComponent] rather than a Koin WorkerFactory: the only thing a factory
 * would buy is constructor injection, and property injection costs one line each
 * with nothing to configure.
 */
class BpmAnalysisWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters), KoinComponent {

    private val scanner: AbstractTracksScanner by inject()
    private val repository: TrackMetadataRepository by inject()
    private val analyser: BpmAnalyser by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val force = inputData.getBoolean(KEY_FORCE, false)

        if (force) repository.clearAnalysed()

        val stored = repository.storedByTrackKey()

        // A track with no durable key -- the ad-hoc CuteTrack QuickPlay builds
        // out of player metadata -- has nowhere for a result to go, so analysing
        // it would be work thrown away.
        val pending = scanner.latestTracks.first().filter { track ->
            val key = track.trackKey ?: return@filter false

            needsAnalysis(stored[key], force)
        }

        if (pending.isEmpty()) return@withContext Result.success(finished(0, 0))

        setForeground(foregroundInfo(0, pending.size, null))

        var known = 0

        pending.forEachIndexed { index, track ->
            // Cancellation is checked between tracks rather than inside them: a
            // single track takes a second or two, and unwinding a decoder
            // mid-buffer would gain nothing but complexity.
            currentCoroutineContext().ensureActive()

            setProgress(progress(index, pending.size, track.title))
            setForeground(foregroundInfo(index, pending.size, track.title))

            if (record(track)) known++
        }

        Result.success(finished(pending.size, known))
    }

    /** @return whether a tempo was established, as opposed to recorded as unknown. */
    private suspend fun record(track: CuteTrack): Boolean =
        when (val result = runCatching { analyser.analyse(track) }.getOrNull()) {
            is BpmAnalyser.Result.FromTag -> {
                repository.setTaggedBpm(track, result.bpm)
                true
            }

            is BpmAnalyser.Result.FromAnalysis -> when (val verdict = result.verdict) {
                is TempoVerdict.Known -> {
                    repository.setAnalysedBpm(track, verdict.bpm, verdict.confidence)
                    true
                }

                is TempoVerdict.Unknown -> {
                    // Stored, not skipped. This is the record that stops the next
                    // run decoding the same unreadable track all over again.
                    repository.setAnalysedBpm(track, bpm = null, confidence = null)
                    false
                }
            }

            // An exception from below is the same outcome as a refusal from
            // below, and treating it differently would only mean retrying
            // forever on a file that will never decode.
            null -> {
                runCatching { repository.setAnalysedBpm(track, bpm = null, confidence = null) }
                false
            }
        }

    private fun progress(done: Int, total: Int, title: String?) = workDataOf(
        KEY_DONE to done,
        KEY_TOTAL to total,
        KEY_CURRENT to title
    )

    private fun finished(examined: Int, known: Int) = workDataOf(
        KEY_EXAMINED to examined,
        KEY_KNOWN to known
    )

    private fun foregroundInfo(done: Int, total: Int, title: String?): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tempo_analysis),
                    // Low, because this is a progress bar for something the user
                    // started on purpose, not news.
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.speed_rounded)
            .setContentTitle(context.getString(R.string.tempo_analysis))
            .setContentText(title ?: context.getString(R.string.tempo_analysis_starting))
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(android.R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id)
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "bpm-analysis"

        const val KEY_FORCE = "force"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"
        const val KEY_EXAMINED = "examined"
        const val KEY_KNOWN = "known"

        private const val CHANNEL_ID = "tempo-analysis"
        private const val NOTIFICATION_ID = 4711
    }
}
