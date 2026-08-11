package app.zhijuan.reader.generation

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.generation.GenerationControlRepository
import app.zhijuan.core.database.generation.GenerationMaintenanceCandidate
import app.zhijuan.core.database.generation.GenerationMaintenanceRepository
import app.zhijuan.core.database.generation.GenerationMaintenanceScan
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.database.generation.StaleGenerationStateException
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.task.GenerationMaintenanceAction
import app.zhijuan.core.task.GenerationMaintenanceContext
import app.zhijuan.core.task.GenerationMaintenancePolicy
import app.zhijuan.core.task.ProviderRecoveryEvidence
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import kotlinx.coroutines.CancellationException

internal data class GenerationMaintenanceReport(
    val scanned: Int,
    val requeuedBeforeRequest: Int,
    val auditedWithoutProvider: Int,
    val settledControls: Int,
    val deferred: Int,
    val stale: Int,
    val failed: Int,
    val deletedDrafts: Int,
    val skippedDraftCleanup: Int,
    val hasMore: Boolean,
) {
    init {
        require(
            listOf(
                scanned,
                requeuedBeforeRequest,
                auditedWithoutProvider,
                settledControls,
                deferred,
                stale,
                failed,
                deletedDrafts,
                skippedDraftCleanup,
            ).all { it >= 0 },
        ) { "Maintenance counters cannot be negative." }
    }

    override fun toString(): String =
        "GenerationMaintenanceReport(scanned=$scanned, requeued=$requeuedBeforeRequest, " +
            "audited=$auditedWithoutProvider, controls=$settledControls, deferred=$deferred, " +
            "stale=$stale, failed=$failed, cleanup=$deletedDrafts, hasMore=$hasMore)"
}

internal interface GenerationMaintenanceOperations {
    suspend fun scan(observedAt: Long, limit: Int): GenerationMaintenanceScan

    suspend fun requeueBeforeRequest(candidate: GenerationMaintenanceCandidate, observedAt: Long)

    suspend fun auditWithoutProvider(candidate: GenerationMaintenanceCandidate, observedAt: Long)

    suspend fun settleExpiredControl(candidate: GenerationMaintenanceCandidate, observedAt: Long)

    suspend fun cleanupExpiredDrafts(observedAt: Long): Pair<Int, Int>
}

internal class GenerationRecoveryMaintenanceCoordinator(
    private val operations: GenerationMaintenanceOperations,
) {
    suspend fun runBatch(
        observedAt: Long,
        limit: Int = GenerationMaintenanceRepository.DEFAULT_BATCH_LIMIT,
    ): GenerationMaintenanceReport {
        require(observedAt >= 0L) { "Maintenance time is invalid." }
        val scan = operations.scan(observedAt, limit)
        var requeued = 0
        var audited = 0
        var controls = 0
        var deferred = 0
        var stale = 0
        var failed = 0

        scan.candidates.forEach { candidate ->
            val action = GenerationMaintenancePolicy.decide(
                GenerationMaintenanceContext(
                    jobStatus = candidate.jobStatus,
                    stageStatus = candidate.stageStatus,
                    hasLatestAttempt = candidate.latestAttemptId != null,
                ),
            )
            try {
                when (action) {
                    GenerationMaintenanceAction.REQUEUE_BEFORE_REQUEST -> {
                        operations.requeueBeforeRequest(candidate, observedAt)
                        requeued += 1
                    }
                    GenerationMaintenanceAction.AUDIT_WITHOUT_PROVIDER -> {
                        operations.auditWithoutProvider(candidate, observedAt)
                        audited += 1
                    }
                    GenerationMaintenanceAction.SETTLE_EXPIRED_NETWORK_CONTROL -> {
                        operations.settleExpiredControl(candidate, observedAt)
                        controls += 1
                    }
                    GenerationMaintenanceAction.DEFER_UNSAFE_OR_INCOMPLETE -> deferred += 1
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleGenerationStateException) {
                stale += 1
            } catch (_: Exception) {
                failed += 1
            }
        }

        val cleanup = try {
            operations.cleanupExpiredDrafts(observedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed += 1
            0 to 0
        }
        return GenerationMaintenanceReport(
            scanned = scan.candidates.size,
            requeuedBeforeRequest = requeued,
            auditedWithoutProvider = audited,
            settledControls = controls,
            deferred = deferred,
            stale = stale,
            failed = failed,
            deletedDrafts = cleanup.first,
            skippedDraftCleanup = cleanup.second,
            hasMore = scan.hasMore,
        )
    }
}

internal class ProductionGenerationMaintenanceRunner(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    suspend fun runBatch(observedAt: Long): GenerationMaintenanceReport =
        EncryptedZhijuanDatabaseFactory(applicationContext).open(ZHIJUAN_DATABASE_NAME).use { handle ->
            val artifacts = AndroidProtectedArtifactStore(applicationContext)
            val maintenance = GenerationMaintenanceRepository(handle.database)
            val controls = GenerationControlRepository(handle.database)
            val drafts = GenerationStreamingDraftRepository(handle.database, artifacts)
            GenerationRecoveryMaintenanceCoordinator(
                object : GenerationMaintenanceOperations {
                    override suspend fun scan(
                        observedAt: Long,
                        limit: Int,
                    ): GenerationMaintenanceScan =
                        maintenance.scanExpiredExecutionLeases(observedAt, limit)

                    override suspend fun requeueBeforeRequest(
                        candidate: GenerationMaintenanceCandidate,
                        observedAt: Long,
                    ) {
                        maintenance.requeueExpiredPreRequestExecution(candidate, observedAt)
                    }

                    override suspend fun auditWithoutProvider(
                        candidate: GenerationMaintenanceCandidate,
                        observedAt: Long,
                    ) {
                        drafts.auditExpiredAttempt(
                            attemptId = requireNotNull(candidate.latestAttemptId),
                            observedLease = candidate.observedLease,
                            providerEvidence = ProviderRecoveryEvidence.NOT_AVAILABLE,
                            auditedAt = observedAt,
                        )
                    }

                    override suspend fun settleExpiredControl(
                        candidate: GenerationMaintenanceCandidate,
                        observedAt: Long,
                    ) {
                        controls.settleExpiredControl(
                            attemptId = requireNotNull(candidate.latestAttemptId),
                            observedLease = candidate.observedLease,
                            now = observedAt,
                        )
                    }

                    override suspend fun cleanupExpiredDrafts(observedAt: Long): Pair<Int, Int> =
                        drafts.cleanupExpired(observedAt).let { result ->
                            result.deletedArtifacts to result.skippedAfterRecheck
                        }
                },
            ).runBatch(observedAt)
        }
}
