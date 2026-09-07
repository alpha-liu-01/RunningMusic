package lol.alphaliu01.runningmusic.analysis

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the analysis job is doing, in terms the UI can render. */
data class AnalysisProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val current: String?
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total

    companion object {
        val IDLE = AnalysisProgress(running = false, done = 0, total = 0, current = null)
    }
}

/**
 * Starts, stops and reports on the library-wide tempo pass.
 *
 * The only thing that knows WorkManager is involved, so the screen above it deals
 * in "start" and "cancel" rather than in requests and policies.
 */
class BpmAnalysisManager(context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * @param force re-analyse everything, discarding tempos this app worked out
     *   before. Hand-entered and tagged ones survive.
     * @param requiresCharging defer until the phone is plugged in.
     */
    fun start(force: Boolean = false, requiresCharging: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BpmAnalysisWorker>()
            .setInputData(workDataOf(BpmAnalysisWorker.KEY_FORCE to force))
            .setConstraints(
                Constraints.Builder()
                    // Always, because decoding a library flat is a real amount of
                    // work to do on a phone that is nearly empty.
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(requiresCharging)
                    .build()
            )
            .build()

        // KEEP rather than REPLACE: a second tap while a pass is running should
        // let it carry on, not restart it from the beginning.
        workManager.enqueueUniqueWork(
            BpmAnalysisWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() = workManager.cancelUniqueWork(BpmAnalysisWorker.WORK_NAME)

    fun progress(): Flow<AnalysisProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(BpmAnalysisWorker.WORK_NAME)
            .map { infos ->
                val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?: return@map AnalysisProgress.IDLE

                AnalysisProgress(
                    running = true,
                    done = info.progress.getInt(BpmAnalysisWorker.KEY_DONE, 0),
                    total = info.progress.getInt(BpmAnalysisWorker.KEY_TOTAL, 0),
                    current = info.progress.getString(BpmAnalysisWorker.KEY_CURRENT)
                )
            }
}
