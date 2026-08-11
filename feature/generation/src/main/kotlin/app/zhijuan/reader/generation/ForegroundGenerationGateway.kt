package app.zhijuan.reader.generation

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.GenerationControlResult
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import app.zhijuan.core.contract.GenerationController
import app.zhijuan.core.model.GenerationJobStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ForegroundGenerationGateway @Inject constructor(
    @ApplicationContext context: Context,
) : ForegroundGenerationControlPort, GenerationController {
    private val applicationContext = context.applicationContext
    private val databaseHandle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedZhijuanDatabaseFactory(applicationContext).open(ZHIJUAN_DATABASE_NAME)
    }
    private val stateRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GenerationStateRepository(databaseHandle.database)
    }
    private val controlRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GenerationControlRepository(databaseHandle.database)
    }

    override suspend fun findJob(jobId: String): ForegroundGenerationSnapshot? =
        stateRepository.findJob(jobId)?.let { job ->
            ForegroundGenerationSnapshot(job.status, job.updatedAt)
        }

    override suspend fun requestUserPause(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult = controlRepository.requestPause(
        jobId = jobId,
        requestedAt = monotonicControlTime(jobId, requestedAt),
    )

    override suspend fun requestStop(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult = controlRepository.requestStop(
        jobId = jobId,
        requestedAt = monotonicControlTime(jobId, requestedAt),
    )

    override suspend fun requestSystemTimeoutPause(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult = controlRepository.requestSystemForegroundTimeoutPause(
        jobId = jobId,
        requestedAt = monotonicControlTime(jobId, requestedAt),
    )

    override suspend fun findGenerationStatus(jobId: String): GenerationJobStatus? =
        findJob(jobId)?.status

    override suspend fun pauseGeneration(
        jobId: String,
        requestedAt: Long,
    ): GenerationJobStatus = requestUserPause(jobId, requestedAt).jobStatus

    override suspend fun stopGeneration(
        jobId: String,
        requestedAt: Long,
    ): GenerationJobStatus = requestStop(jobId, requestedAt).jobStatus

    private suspend fun monotonicControlTime(jobId: String, requestedAt: Long): Long =
        maxOf(requestedAt, requireNotNull(stateRepository.findJob(jobId)).updatedAt)
}
