package app.zhijuan.reader.generation

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.GenerationControlResult
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import app.zhijuan.core.contract.GenerationController
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.feature.generation.GenerationBoundRemoteExecutionProvider
import app.zhijuan.feature.generation.GenerationPersistentRunResult
import app.zhijuan.feature.generation.GenerationPersistentRuntimeFactoryV1
import app.zhijuan.feature.generation.GenerationTotalRunnerPort
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ForegroundGenerationGateway @Inject constructor(
    @ApplicationContext context: Context,
    private val remote: Optional<GenerationBoundRemoteExecutionProvider>,
) : ForegroundGenerationControlPort, GenerationController, GenerationTotalRunnerPort {
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
    private val runtime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val provider = remote.orElseThrow {
            GenerationRuntimeUnavailableException()
        }
        GenerationPersistentRuntimeFactoryV1.create(
            database = databaseHandle.database,
            artifactStore = AndroidProtectedArtifactStore(applicationContext),
            remote = provider,
        )
    }

    override suspend fun runJob(
        jobId: String,
        runnerOwnerId: String,
    ): GenerationPersistentRunResult = runtime.runner.runJob(jobId, runnerOwnerId)

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

internal class GenerationRuntimeUnavailableException :
    IllegalStateException("Generation remote execution is not installed yet.")
