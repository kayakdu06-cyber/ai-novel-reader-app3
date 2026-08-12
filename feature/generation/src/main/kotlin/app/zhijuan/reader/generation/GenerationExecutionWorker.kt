package app.zhijuan.reader.generation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zhijuan.feature.generation.GenerationPersistentRunDisposition
import app.zhijuan.feature.generation.GenerationTotalRunnerPort
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** WorkManager and the foreground service both enter the same singleton total-runner gateway. */
internal class GenerationExecutionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?.takeIf(JOB_ID::matches)
            ?: return Result.failure()
        val runner = EntryPointAccessors.fromApplication(
            applicationContext,
            GenerationExecutionWorkerEntryPoint::class.java,
        ).runner()
        return try {
            val result = GenerationExecutionEntryPointV1(runner, "work").run(jobId, id.toString())
            val output = Data.Builder()
                .putString(KEY_DISPOSITION, result.disposition.name)
                .putInt(KEY_EXECUTED_STAGE_COUNT, result.executedStageCount)
                .build()
            when (result.disposition) {
                GenerationPersistentRunDisposition.RECOVERY_REQUIRED -> Result.failure(output)
                GenerationPersistentRunDisposition.STAGE_LIMIT_REACHED -> Result.retry()
                else -> Result.success(output)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < MAX_TRANSIENT_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        internal const val KEY_JOB_ID = "job_id"
        internal const val KEY_DISPOSITION = "disposition"
        internal const val KEY_EXECUTED_STAGE_COUNT = "executed_stage_count"
        private const val MAX_TRANSIENT_RETRIES = 2
        private val JOB_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GenerationExecutionWorkerEntryPoint {
    fun runner(): GenerationTotalRunnerPort
}

object GenerationExecutionScheduler {
    private val JOB_ID = Regex("[A-Za-z0-9._:-]{1,128}")

    fun enqueue(context: Context, jobId: String): Boolean {
        if (!JOB_ID.matches(jobId)) return false
        val request = OneTimeWorkRequest.Builder(GenerationExecutionWorker::class.java)
            .setInputData(Data.Builder().putString(GenerationExecutionWorker.KEY_JOB_ID, jobId).build())
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$UNIQUE_WORK_PREFIX:$jobId",
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }

    private const val UNIQUE_WORK_PREFIX = "zhijuan-generation-execution-v1"
    private const val WORK_TAG = "zhijuan-generation-execution"
}
