package app.zhijuan.reader.generation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal class GenerationRecoveryMaintenanceWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = try {
        val report = ProductionGenerationMaintenanceRunner(applicationContext)
            .runBatch(System.currentTimeMillis().coerceAtLeast(1L))
        val output = Data.Builder()
            .putInt(KEY_SCANNED, report.scanned)
            .putInt(KEY_IDLE_REQUEUED, report.requeuedIdleJobs)
            .putInt(KEY_REQUEUED, report.requeuedBeforeRequest)
            .putInt(KEY_AUDITED, report.auditedWithoutProvider)
            .putInt(KEY_CONTROLS, report.settledControls)
            .putInt(KEY_DEFERRED, report.deferred)
            .putInt(KEY_STALE, report.stale)
            .putInt(KEY_FAILED, report.failed)
            .putInt(KEY_DELETED_DRAFTS, report.deletedDrafts)
            .putBoolean(KEY_HAS_MORE, report.hasMore)
            .build()
        if (report.failed == 0) {
            Result.success(output)
        } else if (runAttemptCount < MAX_TRANSIENT_RETRIES) {
            Result.retry()
        } else {
            Result.failure(output)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < MAX_TRANSIENT_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        internal const val KEY_SCANNED = "scanned"
        internal const val KEY_IDLE_REQUEUED = "idle_requeued"
        internal const val KEY_REQUEUED = "requeued"
        internal const val KEY_AUDITED = "audited"
        internal const val KEY_CONTROLS = "controls"
        internal const val KEY_DEFERRED = "deferred"
        internal const val KEY_STALE = "stale"
        internal const val KEY_FAILED = "failed"
        internal const val KEY_DELETED_DRAFTS = "deleted_drafts"
        internal const val KEY_HAS_MORE = "has_more"
        private const val MAX_TRANSIENT_RETRIES = 2
    }
}

object GenerationMaintenanceScheduler {
    internal const val STARTUP_WORK_NAME = "zhijuan-generation-recovery-startup-v1"
    internal const val PERIODIC_WORK_NAME = "zhijuan-generation-maintenance-periodic-v1"
    private const val WORK_TAG = "zhijuan-generation-maintenance"

    fun ensureScheduled(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val startup = OneTimeWorkRequest.Builder(GenerationRecoveryMaintenanceWorker::class.java)
            .setInitialDelay(15L, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        manager.enqueueUniqueWork(STARTUP_WORK_NAME, ExistingWorkPolicy.KEEP, startup)

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val periodic = PeriodicWorkRequest.Builder(
            GenerationRecoveryMaintenanceWorker::class.java,
            24L,
            TimeUnit.HOURS,
            6L,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }
}
