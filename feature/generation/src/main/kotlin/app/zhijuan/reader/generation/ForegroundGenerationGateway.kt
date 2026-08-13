package app.zhijuan.reader.generation

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.GenerationControlResult
import app.zhijuan.core.database.generation.GenerationContinuationPreparationRepository
import app.zhijuan.core.database.generation.GenerationContinuationPreparationResult
import app.zhijuan.core.database.generation.GenerationStartPersistenceFailure
import app.zhijuan.core.database.generation.GenerationStartPersistenceRepository
import app.zhijuan.core.database.generation.GenerationStartPersistenceResult
import app.zhijuan.core.database.generation.GenerationStateRepository
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import app.zhijuan.core.contract.GenerationController
import app.zhijuan.core.contract.GenerationStartFailure
import app.zhijuan.core.contract.GenerationStartRequest
import app.zhijuan.core.contract.GenerationStartResult
import app.zhijuan.core.contract.GenerationStarter
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.feature.generation.GenerationBoundRemoteExecutionProvider
import app.zhijuan.feature.generation.GenerationPersistentRunResult
import app.zhijuan.feature.generation.GenerationPersistentRunDisposition
import app.zhijuan.feature.generation.GenerationChapterRun
import app.zhijuan.feature.generation.GenerationChapterSequenceDisposition
import app.zhijuan.feature.generation.GenerationNextChapterPreparationResult
import app.zhijuan.feature.generation.GenerationPersistentChapterSequenceV1
import app.zhijuan.feature.generation.GenerationPersistentRuntimeFactoryV1
import app.zhijuan.feature.generation.GenerationTotalRunnerPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
internal class ForegroundGenerationGateway @Inject constructor(
    @ApplicationContext context: Context,
) : ForegroundGenerationControlPort, GenerationController, GenerationTotalRunnerPort, GenerationStarter {
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
    private val startRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GenerationStartPersistenceRepository(databaseHandle.database)
    }
    private val continuations by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GenerationContinuationPreparationRepository(databaseHandle.database)
    }
    private val runtime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GenerationPersistentRuntimeFactoryV1.create(
            database = databaseHandle.database,
            artifactStore = AndroidProtectedArtifactStore(applicationContext),
            remote = ProductionGenerationBoundRemoteExecutionProvider(
                applicationContext,
                databaseHandle.database,
            ),
        )
    }

    override suspend fun runJob(
        jobId: String,
        runnerOwnerId: String,
    ): GenerationPersistentRunResult {
        var currentJobId = jobId
        var executedStages = 0
        repeat(MAX_BOOTSTRAP_JOBS) { ordinal ->
            val result = runtime.runner.runJob(currentJobId, "$runnerOwnerId:$ordinal")
            executedStages += result.executedStageCount
            if (result.disposition != app.zhijuan.feature.generation.GenerationPersistentRunDisposition.COMPLETED) {
                return result.copy(executedStageCount = executedStages)
            }
            when (
                val continuation = continuations.prepareAfterCompleted(
                    currentJobId,
                    System.currentTimeMillis().coerceAtLeast(0L),
                )
            ) {
                is GenerationContinuationPreparationResult.Prepared -> {
                    val chapterIndex = continuation.chapterIndex
                    if (chapterIndex == null) {
                        currentJobId = continuation.jobId
                    } else {
                        val sequence = GenerationPersistentChapterSequenceV1(runtime.runner) { completed, expected ->
                            when (
                                val next = continuations.prepareAfterCompleted(
                                    completed.jobId,
                                    System.currentTimeMillis().coerceAtLeast(0L),
                                )
                            ) {
                                is GenerationContinuationPreparationResult.Prepared -> {
                                    val nextIndex = next.chapterIndex
                                    if (nextIndex == expected) {
                                        GenerationNextChapterPreparationResult.Prepared(
                                            GenerationChapterRun(next.bookId, next.jobId, nextIndex),
                                        )
                                    } else {
                                        GenerationNextChapterPreparationResult.NotReady
                                    }
                                }
                                GenerationContinuationPreparationResult.NotReady ->
                                    GenerationNextChapterPreparationResult.NotReady
                            }
                        }.run(
                            initialChapter = GenerationChapterRun(
                                continuation.bookId,
                                continuation.jobId,
                                chapterIndex,
                            ),
                            requestedChapterCount = MAX_CHAPTERS_PER_SEQUENCE,
                            runnerOwnerPrefix = "$runnerOwnerId:chapters",
                            alreadyCompletedChapterCount = chapterIndex - 1,
                        )
                        return GenerationPersistentRunResult(
                            disposition = when (sequence.disposition) {
                                GenerationChapterSequenceDisposition.TARGET_COMPLETED ->
                                    GenerationPersistentRunDisposition.COMPLETED
                                GenerationChapterSequenceDisposition.RUNNER_HALTED -> sequence.runnerDisposition
                                GenerationChapterSequenceDisposition.NEXT_CHAPTER_NOT_READY ->
                                    GenerationPersistentRunDisposition.NOT_READY
                                GenerationChapterSequenceDisposition.INVALID_NEXT_CHAPTER ->
                                    GenerationPersistentRunDisposition.RECOVERY_REQUIRED
                            },
                            executedStageCount = executedStages + sequence.executedStageCount,
                        )
                    }
                }
                GenerationContinuationPreparationResult.NotReady ->
                    return result.copy(executedStageCount = executedStages)
            }
        }
        return GenerationPersistentRunResult(
            app.zhijuan.feature.generation.GenerationPersistentRunDisposition.STAGE_LIMIT_REACHED,
            executedStages,
        )
    }

    override suspend fun start(request: GenerationStartRequest): GenerationStartResult = try {
        when (val persisted = startRepository.start(request)) {
            is GenerationStartPersistenceResult.Started -> {
                if (!GenerationForegroundService.requestStart(applicationContext, persisted.jobId)) {
                    GenerationStartResult.Failed(GenerationStartFailure.START_TEMPORARILY_UNAVAILABLE)
                } else {
                    GenerationStartResult.Started(
                        bookId = persisted.bookId,
                        jobId = persisted.jobId,
                        replayed = persisted.replayed,
                    )
                }
            }
            is GenerationStartPersistenceResult.Failed -> GenerationStartResult.Failed(
                when (persisted.reason) {
                    GenerationStartPersistenceFailure.BOOK_NOT_FOUND ->
                        GenerationStartFailure.BOOK_NOT_FOUND
                    GenerationStartPersistenceFailure.CONFIRMATION_CHANGED ->
                        GenerationStartFailure.CONFIRMATION_CHANGED
                    GenerationStartPersistenceFailure.CONNECTION_CHANGED ->
                        GenerationStartFailure.CONNECTION_CHANGED
                    GenerationStartPersistenceFailure.DESTINATION_CONFIRMATION_REQUIRED ->
                        GenerationStartFailure.DESTINATION_CONFIRMATION_REQUIRED
                    GenerationStartPersistenceFailure.BUDGET_CONFIRMATION_INVALID ->
                        GenerationStartFailure.BUDGET_CONFIRMATION_INVALID
                },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        GenerationStartResult.Failed(GenerationStartFailure.START_TEMPORARILY_UNAVAILABLE)
    }

    override suspend fun findJob(jobId: String): ForegroundGenerationSnapshot? {
        var current = stateRepository.findJob(jobId) ?: return null
        repeat(MAX_CHAIN_JOBS - 1) {
            if (current.status != GenerationJobStatus.COMPLETED) return ForegroundGenerationSnapshot(
                current.status,
                current.updatedAt,
            )
            val next = continuations.prepareAfterCompleted(
                current.jobId,
                maxOf(System.currentTimeMillis().coerceAtLeast(0L), current.updatedAt),
            ) as? GenerationContinuationPreparationResult.Prepared ?: return ForegroundGenerationSnapshot(
                current.status,
                current.updatedAt,
            )
            current = requireNotNull(stateRepository.findJob(next.jobId))
        }
        return ForegroundGenerationSnapshot(current.status, current.updatedAt)
    }

    override suspend fun requestUserPause(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult {
        val leafJobId = activeLeafJobId(jobId)
        return controlRepository.requestPause(
            jobId = leafJobId,
            requestedAt = monotonicControlTime(leafJobId, requestedAt),
        )
    }

    override suspend fun requestStop(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult {
        val leafJobId = activeLeafJobId(jobId)
        return controlRepository.requestStop(
            jobId = leafJobId,
            requestedAt = monotonicControlTime(leafJobId, requestedAt),
        )
    }

    override suspend fun requestSystemTimeoutPause(
        jobId: String,
        requestedAt: Long,
    ): GenerationControlResult {
        val leafJobId = activeLeafJobId(jobId)
        return controlRepository.requestSystemForegroundTimeoutPause(
            jobId = leafJobId,
            requestedAt = monotonicControlTime(leafJobId, requestedAt),
        )
    }

    override suspend fun findGenerationStatus(jobId: String): GenerationJobStatus? =
        findJob(jobId)?.status

    override suspend fun pauseGeneration(
        jobId: String,
        requestedAt: Long,
    ): GenerationJobStatus = requestUserPause(jobId, requestedAt).jobStatus

    override suspend fun resumeGeneration(
        jobId: String,
        requestedAt: Long,
    ): GenerationJobStatus {
        val leafJobId = activeLeafJobId(jobId)
        val status = controlRepository.resume(
            leafJobId,
            monotonicControlTime(leafJobId, requestedAt),
        ).jobStatus
        GenerationForegroundService.requestStart(applicationContext, jobId)
        return status
    }

    override suspend fun stopGeneration(
        jobId: String,
        requestedAt: Long,
    ): GenerationJobStatus = requestStop(jobId, requestedAt).jobStatus

    private suspend fun monotonicControlTime(jobId: String, requestedAt: Long): Long =
        maxOf(requestedAt, requireNotNull(stateRepository.findJob(jobId)).updatedAt)

    private suspend fun activeLeafJobId(rootJobId: String): String {
        var current = requireNotNull(stateRepository.findJob(rootJobId))
        repeat(MAX_CHAIN_JOBS - 1) {
            if (current.status != GenerationJobStatus.COMPLETED) return current.jobId
            val continuation = continuations.prepareAfterCompleted(
                current.jobId,
                maxOf(System.currentTimeMillis().coerceAtLeast(0L), current.updatedAt),
            ) as? GenerationContinuationPreparationResult.Prepared ?: return current.jobId
            current = requireNotNull(stateRepository.findJob(continuation.jobId))
        }
        return current.jobId
    }

    private companion object {
        const val MAX_BOOTSTRAP_JOBS = 3
        const val MAX_CHAPTERS_PER_SEQUENCE = 5
        const val MAX_CHAIN_JOBS = 7
    }
}
