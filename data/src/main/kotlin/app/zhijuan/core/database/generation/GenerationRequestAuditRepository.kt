package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetReservationStatus
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.ExternalDataDestinationBindingV1
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.ProviderOpenDestinationEvidence
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

class RequestIntentDraft(
    val attemptId: String,
    val usageLedgerId: String,
    val stageId: String,
    val retryParentAttemptId: String?,
    val connectionSnapshotJson: String,
    val modelSnapshotJson: String,
    val protocolSnapshotJson: String,
    val inputHash: String,
    val streamDraftRef: String?,
    val createdAt: Long,
) {
    override fun toString(): String =
        "RequestIntentDraft(stageId=$stageId, snapshots=redacted, inputHash=redacted)"
}

class PersistedRequestSendPermit internal constructor(
    val attemptId: String,
    val stageId: String,
    val attemptNo: Int,
    val inputHash: String,
    val leaseToken: GenerationLeaseToken,
    val intentRecordedAt: Long,
    internal val reservationId: String,
) {
    private val claimed = AtomicBoolean(false)

    internal fun claimAfterPersistedLeaseValidation(
        validatedAt: Long,
        destination: ProviderOpenDestinationEvidence,
    ): ClaimedRequestSend {
        check(claimed.compareAndSet(false, true)) {
            "A persisted request send permit can be claimed only once."
        }
        return ClaimedRequestSend(
            attemptId = attemptId,
            stageId = stageId,
            attemptNo = attemptNo,
            inputHash = inputHash,
            leaseToken = leaseToken,
            intentRecordedAt = intentRecordedAt,
            reservationId = reservationId,
            leaseValidatedAt = validatedAt,
            destination = destination,
        )
    }

    override fun toString(): String = "PersistedRequestSendPermit(claimed=${claimed.get()})"
}

class ClaimedRequestSend internal constructor(
    val attemptId: String,
    val stageId: String,
    val attemptNo: Int,
    val inputHash: String,
    val leaseToken: GenerationLeaseToken,
    val intentRecordedAt: Long,
    internal val reservationId: String,
    val leaseValidatedAt: Long,
    internal val destination: ProviderOpenDestinationEvidence,
) {
    override fun toString(): String = "ClaimedRequestSend(audit=redacted)"
}

data class StoredRequestAttemptAudit(
    val attemptId: String,
    val jobId: String,
    val stageId: String,
    val attemptNo: Int,
    val status: RequestAttemptStatus,
    val requestIntentAt: Long,
    val sentAt: Long?,
    val finishedAt: Long?,
    val retryParentAttemptId: String?,
    val updatedAt: Long,
)

data class StoredUsageLedgerAudit(
    val usageLedgerId: String,
    val attemptId: String,
    val bookId: String,
    val source: UsageSource,
    val status: UsageLedgerStatus,
    val totalTokens: Long?,
    val estimatedCostMicros: Long?,
    val finalizedAt: Long?,
    val updatedAt: Long,
)

data class PersistedRequestAudit(
    val permit: PersistedRequestSendPermit,
    val attempt: StoredRequestAttemptAudit,
    val usage: StoredUsageLedgerAudit,
)

/**
 * Business signal that the daily budget period expired before the request was
 * sent: the unsent v1 attempt and its reservation were released by the
 * dedicated rollover transaction, and the persistent runner must re-prepare
 * the request. Deliberately redacted: only a limited retry flag is carried;
 * no ids, dates, zones, amounts, tokens, destinations, or snapshots.
 */
class DailyBudgetPeriodRolloverRequiredException(
    val retryAllowed: Boolean,
) : Exception("Daily budget period expired before send; the persistent runner must re-prepare the request.") {
    override fun toString(): String = "DailyBudgetPeriodRolloverRequiredException(retryAllowed=$retryAllowed)"
}

enum class ProviderOpenDestinationMismatchReason {
    CONNECTION_ID,
    DESTINATION_ORIGIN,
    PROTOCOL,
    DISCLOSURE_VERSION,
    DISCLOSURE_BINDING,
    DISCLOSURE_ACCEPTED_AT,
    DISCLOSURE_UNAVAILABLE,
}

/**
 * Finite, redacted provider-open failure. A mismatch does not consume the
 * in-memory permit and does not move Attempt, Usage, reservation, Stage, or Job.
 */
class ProviderOpenDestinationMismatchException(
    val reason: ProviderOpenDestinationMismatchReason,
) : IllegalStateException("Provider-open destination validation failed.") {
    override fun toString(): String =
        "ProviderOpenDestinationMismatchException(reason=$reason)"
}

class GenerationRequestAuditRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    internal suspend fun persistBeforeSend(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        leaseToken: GenerationLeaseToken,
    ): PersistedRequestAudit = database.withTransaction {
        requireUnboundPreparationAllowed(draft.stageId)
        persistBeforeSendInternal(
            draft = draft,
            budget = budget,
            leaseToken = leaseToken,
            rolloverParentAttemptId = null,
            rolloverSourceArtifactRefId = null,
            executionLease = null,
        )
    }

    internal suspend fun persistBoundChapterPlanBeforeSend(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
    ): PersistedRequestAudit = database.withTransaction {
        requireBoundRemoteExecution(draft, snapshot, CHAPTER_PLAN_ROUTES, "chapter-plan")
        persistBeforeSendInternal(
            draft = draft,
            budget = budget,
            leaseToken = snapshot.executionLease.stageLeaseToken,
            rolloverParentAttemptId = null,
            rolloverSourceArtifactRefId = null,
            executionLease = null,
        )
    }

    internal suspend fun persistBoundInitialChapterDraftBeforeSend(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
    ): PersistedRequestAudit = database.withTransaction {
        requireBoundRemoteExecution(draft, snapshot, INITIAL_CHAPTER_DRAFT_ROUTES, "initial chapter draft")
        persistBeforeSendInternal(
            draft = draft,
            budget = budget,
            leaseToken = snapshot.executionLease.stageLeaseToken,
            rolloverParentAttemptId = null,
            rolloverSourceArtifactRefId = null,
            executionLease = null,
        )
    }

    internal suspend fun persistDailyRolloverReplacementBeforeSend(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        executionLease: GenerationRunnerExecutionLeaseSnapshot,
        parentAttemptId: String,
        sourceArtifactRefId: String,
    ): PersistedRequestAudit = database.withTransaction {
        requireUnboundPreparationAllowed(draft.stageId)
        persistBeforeSendInternal(
            draft = draft,
            budget = budget,
            leaseToken = executionLease.stageLeaseToken,
            rolloverParentAttemptId = parentAttemptId,
            rolloverSourceArtifactRefId = sourceArtifactRefId,
            executionLease = executionLease,
        )
    }

    internal suspend fun persistBoundChapterPlanDailyRolloverReplacementBeforeSend(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        parentAttemptId: String,
        sourceArtifactRefId: String,
    ): PersistedRequestAudit = database.withTransaction {
        requireBoundRemoteExecution(draft, snapshot, CHAPTER_PLAN_ROUTES, "chapter-plan")
        persistBeforeSendInternal(
            draft = draft,
            budget = budget,
            leaseToken = snapshot.executionLease.stageLeaseToken,
            rolloverParentAttemptId = parentAttemptId,
            rolloverSourceArtifactRefId = sourceArtifactRefId,
            executionLease = snapshot.executionLease,
        )
    }

    internal suspend fun persistBoundInitialChapterDraftDailyRolloverReplacementBeforeSend(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        parentAttemptId: String,
        sourceArtifactRefId: String,
    ): PersistedRequestAudit = database.withTransaction {
        requireBoundRemoteExecution(draft, snapshot, INITIAL_CHAPTER_DRAFT_ROUTES, "initial chapter draft")
        persistBeforeSendInternal(
            draft = draft,
            budget = budget,
            leaseToken = snapshot.executionLease.stageLeaseToken,
            rolloverParentAttemptId = parentAttemptId,
            rolloverSourceArtifactRefId = sourceArtifactRefId,
            executionLease = snapshot.executionLease,
        )
    }

    private suspend fun persistBeforeSendInternal(
        draft: RequestIntentDraft,
        budget: RequestBudgetReservationDraft,
        leaseToken: GenerationLeaseToken,
        rolloverParentAttemptId: String?,
        rolloverSourceArtifactRefId: String?,
        executionLease: GenerationRunnerExecutionLeaseSnapshot?,
    ): PersistedRequestAudit {
        RequestIntentDraftPolicy.validate(draft)
        val dao = database.generationDao()
        val reservationRepository = PersistentBudgetReservationRepository(database, leasePolicy)
        val result = if (executionLease == null) {
            check(rolloverParentAttemptId == null && rolloverSourceArtifactRefId == null) {
                "Daily rollover replacement evidence is incomplete."
            }
            reservationRepository.recordBudgetedRequestIntent(draft.toInternal(), budget, leaseToken)
        } else {
            val parentAttemptId = requireNotNull(rolloverParentAttemptId) {
                "Daily rollover replacement parent is missing."
            }
            val sourceArtifactRefId = requireNotNull(rolloverSourceArtifactRefId) {
                "Daily rollover replacement source artifact is missing."
            }
            require(draft.retryParentAttemptId == parentAttemptId) {
                "Daily rollover replacement must name its released parent."
            }
            reservationRepository.recordDailyRolloverReplacementRequestIntent(
                intent = draft.toInternal(),
                budget = budget,
                executionLease = executionLease,
                parentAttemptId = parentAttemptId,
                sourceArtifactRefId = sourceArtifactRefId,
            )
        }
        val attempt = requireNotNull(dao.findAttempt(result.attemptId)) {
            "A budgeted request intent must own a persisted attempt before sending."
        }
        val usage = requireNotNull(dao.findUsageForAttempt(attempt.attemptId)) {
            "A persisted request intent must own a usage ledger before sending."
        }
        check(attempt.status == RequestAttemptStatus.INTENT_RECORDED)
        check(usage.source == UsageSource.UNKNOWN && usage.status == UsageLedgerStatus.PROVISIONAL)
        check(usage.totalTokens == null && usage.estimatedCostMicros == null)
        return PersistedRequestAudit(
            permit = PersistedRequestSendPermit(
                attemptId = attempt.attemptId,
                stageId = attempt.stageId,
                attemptNo = attempt.attemptNo,
                inputHash = attempt.inputHash,
                leaseToken = leaseToken,
                intentRecordedAt = attempt.requestIntentAt,
                reservationId = result.reservationId,
            ),
            attempt = attempt.toStoredAudit(),
            usage = usage.toStoredAudit(),
        )
    }

    internal suspend fun claimForProviderOpen(
        permit: PersistedRequestSendPermit,
        validatedAt: Long,
        destination: ProviderOpenDestinationEvidence,
    ): ClaimedRequestSend {
        val dao = database.generationDao()
        var rolloverRequired = false
        var rolloverRetryAllowed = false
        database.withTransaction {
            val attempt = validatePermitEvidence(
                attemptId = permit.attemptId,
                stageId = permit.stageId,
                attemptNo = permit.attemptNo,
                inputHash = permit.inputHash,
                intentRecordedAt = permit.intentRecordedAt,
                reservationId = permit.reservationId,
                allowedAttemptStatuses = setOf(RequestAttemptStatus.INTENT_RECORDED),
            )
            val reservation = requireNotNull(dao.findBudgetReservationByAttempt(attempt.attemptId)) {
                "A v1 provider open requires its budget reservation."
            }
            requireProviderOpenDestination(
                destination = destination,
                reservation = reservation,
            )
            val currentPeriodKey = dao.currentDailyBudgetPeriodKey(validatedAt)
            if (currentPeriodKey == reservation.dailyPeriodKey) {
                requireJobAllowsProviderOpen(attempt, validatedAt)
                dao.heartbeatStageLease(
                    stageId = permit.stageId,
                    leaseToken = permit.leaseToken,
                    now = validatedAt,
                    policy = leasePolicy,
                )
            } else {
                val disposition = dao.releaseUnsentAttemptAfterDailyRollover(
                    attemptId = permit.attemptId,
                    reservationId = permit.reservationId,
                    leaseToken = permit.leaseToken,
                    validatedAt = validatedAt,
                )
                rolloverRequired = true
                rolloverRetryAllowed = disposition == DailyRolloverDisposition.REQUEUED
            }
        }
        if (rolloverRequired) {
            // The rollover committed above; the business signal must be thrown
            // after the transaction so the release is never rolled back.
            throw DailyBudgetPeriodRolloverRequiredException(retryAllowed = rolloverRetryAllowed)
        }
        return permit.claimAfterPersistedLeaseValidation(validatedAt, destination)
    }

    internal suspend fun markRequestSent(
        claimedSend: ClaimedRequestSend,
        providerRequestId: String?,
        sentAt: Long,
    ): StoredRequestAttemptAudit {
        require(sentAt >= claimedSend.leaseValidatedAt) {
            "Request send time cannot precede send authorization."
        }
        require(providerRequestId == null || (providerRequestId.isNotBlank() && providerRequestId.length <= 1_024)) {
            "Provider request id is empty or too long."
        }
        val dao = database.generationDao()
        val attempt = validatePermitEvidence(
            attemptId = claimedSend.attemptId,
            stageId = claimedSend.stageId,
            attemptNo = claimedSend.attemptNo,
            inputHash = claimedSend.inputHash,
            intentRecordedAt = claimedSend.intentRecordedAt,
            reservationId = claimedSend.reservationId,
            allowedAttemptStatuses = setOf(RequestAttemptStatus.INTENT_RECORDED),
        )
        requireDestinationMatchesReservation(claimedSend)
        return dao.recordRequestSent(
            attemptId = attempt.attemptId,
            providerRequestId = providerRequestId,
            sentAt = sentAt,
            leaseToken = claimedSend.leaseToken,
        ).toStoredAudit()
    }

    internal suspend fun markStreamStarted(
        claimedSend: ClaimedRequestSend,
        startedAt: Long,
    ): StoredRequestAttemptAudit {
        require(startedAt >= claimedSend.leaseValidatedAt) {
            "Stream-start time cannot precede send authorization."
        }
        val dao = database.generationDao()
        validatePermitEvidence(
            attemptId = claimedSend.attemptId,
            stageId = claimedSend.stageId,
            attemptNo = claimedSend.attemptNo,
            inputHash = claimedSend.inputHash,
            intentRecordedAt = claimedSend.intentRecordedAt,
            reservationId = claimedSend.reservationId,
            allowedAttemptStatuses = setOf(RequestAttemptStatus.SENT),
        )
        requireDestinationMatchesReservation(claimedSend)
        return dao.recordStreamStarted(
            attemptId = claimedSend.attemptId,
            updatedAt = startedAt,
            leaseToken = claimedSend.leaseToken,
        ).toStoredAudit()
    }

    suspend fun findAttempt(attemptId: String): StoredRequestAttemptAudit? =
        database.generationDao().findAttempt(attemptId)?.toStoredAudit()

    suspend fun findUsageForAttempt(attemptId: String): StoredUsageLedgerAudit? =
        database.generationDao().findUsageForAttempt(attemptId)?.toStoredAudit()

    private suspend fun validatePermitEvidence(
        attemptId: String,
        stageId: String,
        attemptNo: Int,
        inputHash: String,
        intentRecordedAt: Long,
        reservationId: String,
        allowedAttemptStatuses: Set<RequestAttemptStatus>,
    ): RequestAttemptEntity {
        val dao = database.generationDao()
        val attempt = dao.findAttempt(attemptId)
            ?: throw StaleGenerationStateException("Persisted request audit evidence is incomplete.")
        val usage = dao.findUsageForAttempt(attemptId)
            ?: throw StaleGenerationStateException("Persisted request audit evidence is incomplete.")
        if (
            attempt.status !in allowedAttemptStatuses ||
            attempt.stageId != stageId ||
            attempt.attemptNo != attemptNo ||
            attempt.inputHash != inputHash ||
            attempt.requestIntentAt != intentRecordedAt ||
            usage.attemptId != attempt.attemptId ||
            usage.source != UsageSource.UNKNOWN ||
            usage.status != UsageLedgerStatus.PROVISIONAL ||
            usage.totalTokens != null
        ) {
            throw StaleGenerationStateException("Request send permit no longer matches persisted audit evidence.")
        }
        if (attempt.budgetEnforcementVersion != 1 || attempt.budgetReservationId != reservationId) {
            throw StaleGenerationStateException("Request send permit does not match a v1 budgeted attempt.")
        }
        val reservation = database.budgetDao().findReservation(reservationId)
            ?: throw StaleGenerationStateException("Persisted budget evidence is incomplete.")
        if (reservation.status != BudgetReservationStatus.RESERVED) {
            throw StaleGenerationStateException("Budget reservation is no longer reserved.")
        }
        val job = dao.findJob(attempt.jobId)
            ?: throw StaleGenerationStateException("Persisted request audit evidence is incomplete.")
        if (
            reservation.attemptId != attempt.attemptId ||
            reservation.stageId != attempt.stageId ||
            reservation.jobId != job.jobId ||
            reservation.bookId != job.bookId ||
            usage.bookId != reservation.bookId ||
            reservation.dailyPeriodKey != usage.dailyPeriodKey
        ) {
            throw StaleGenerationStateException("Budget reservation no longer matches the persisted attempt.")
        }
        return attempt
    }

    /**
     * The executor-observed destination, current disclosure, and frozen
     * reservation must all agree before any provider-open write is allowed.
     * This runs before both the same-day heartbeat and the cross-day release.
     */
    private suspend fun requireProviderOpenDestination(
        destination: ProviderOpenDestinationEvidence,
        reservation: RequestBudgetReservationEntity,
    ) {
        if (destination.connectionId != reservation.connectionId) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.CONNECTION_ID)
        }
        val current = try {
            database.connectionDao().readAcceptedDataDisclosureEvidence(reservation.connectionId)
        } catch (_: IllegalArgumentException) {
            throw providerOpenDestinationMismatch(
                ProviderOpenDestinationMismatchReason.DISCLOSURE_UNAVAILABLE,
            )
        } catch (_: IllegalStateException) {
            throw providerOpenDestinationMismatch(
                ProviderOpenDestinationMismatchReason.DISCLOSURE_UNAVAILABLE,
            )
        }
        if (destination.normalizedDestination != current.normalizedDestination) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.DESTINATION_ORIGIN)
        }
        if (destination.protocolId != current.protocolId) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.PROTOCOL)
        }
        if (current.normalizedDestination != reservation.normalizedDestination) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.DESTINATION_ORIGIN)
        }
        if (current.protocolId != reservation.protocolId) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.PROTOCOL)
        }
        if (current.disclosureVersion != reservation.disclosureVersion) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.DISCLOSURE_VERSION)
        }
        if (
            !ExternalDataDestinationBindingV1.constantTimeEquals(
                current.bindingHash,
                reservation.disclosureBindingHash,
            )
        ) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.DISCLOSURE_BINDING)
        }
        if (current.acceptedAt < reservation.disclosureAcceptedAt) {
            throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.DISCLOSURE_ACCEPTED_AT)
        }
    }

    private suspend fun requireDestinationMatchesReservation(claimedSend: ClaimedRequestSend) {
        val reservation = database.generationDao().findBudgetReservationByAttempt(claimedSend.attemptId)
            ?: throw providerOpenDestinationMismatch(
                ProviderOpenDestinationMismatchReason.DISCLOSURE_UNAVAILABLE,
            )
        val destination = claimedSend.destination
        when {
            destination.connectionId != reservation.connectionId ->
                throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.CONNECTION_ID)
            destination.normalizedDestination != reservation.normalizedDestination ->
                throw providerOpenDestinationMismatch(
                    ProviderOpenDestinationMismatchReason.DESTINATION_ORIGIN,
                )
            destination.protocolId != reservation.protocolId ->
                throw providerOpenDestinationMismatch(ProviderOpenDestinationMismatchReason.PROTOCOL)
        }
    }

    private fun providerOpenDestinationMismatch(
        reason: ProviderOpenDestinationMismatchReason,
    ): ProviderOpenDestinationMismatchException = ProviderOpenDestinationMismatchException(reason)

    private suspend fun requireJobAllowsProviderOpen(
        attempt: RequestAttemptEntity,
        validatedAt: Long,
    ) {
        val dao = database.generationDao()
        val job = requireNotNull(dao.findJob(attempt.jobId)) {
            "Owning generation job no longer exists."
        }
        if (job.status != GenerationJobStatus.RUNNING || job.currentStageId != attempt.stageId) {
            throw StaleGenerationStateException(
                "A paused, stopping, or superseded job cannot open a Provider request.",
            )
        }
        require(validatedAt >= job.updatedAt) { "Provider-open validation time cannot move backwards." }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) {
            "Provider-open generation stage no longer exists."
        }
        if (
            ChapterCandidateStageSourceGuard(database).requireProviderOpenAllowedIfBound(
                stage,
                job,
                attempt.inputHash,
            )
        ) return
        if (InitialChapterDraftSourceGuard(database).requireProviderOpenAllowedIfBound(stage, job)) return
        ChapterEditRebuildStageRepository(database).requireProviderOpenAllowedIfBound(
            stage = stage,
            job = job,
            observedAt = validatedAt,
        )
        ChapterProgressionGateRepository(database).requireProviderOpenAllowed(stage, job)
        ChapterContextAssemblyRepository(database).requireProviderOpenAllowedIfBound(stage, job)
        ChapterMemoryExtractionSourceGuard(database).requireProviderOpenAllowedIfBound(stage, job)
        ChapterTrackingProjectionSourceGuard(database).requireProviderOpenAllowedIfBound(stage, job)
    }

    private suspend fun requireUnboundPreparationAllowed(stageId: String) {
        val stage = requireNotNull(database.generationDao().findStage(stageId)) {
            "Request-intent Stage is missing."
        }
        if (requiresBoundRunnerExecution(stage)) {
            throw StaleGenerationStateException(
                "This generation request requires the bound runner preparation path.",
            )
        }
    }

    private suspend fun requireBoundRemoteExecution(
        draft: RequestIntentDraft,
        snapshot: GenerationRunnerCurrentStageRouteSnapshot,
        allowedRoutes: Set<GenerationRunnerStageRoute>,
        label: String,
    ) {
        val lease = snapshot.executionLease
        if (
            snapshot.route !in allowedRoutes ||
            draft.stageId != lease.stageId ||
            lease.jobStatus != GenerationJobStatus.RUNNING ||
            lease.stageStatus != app.zhijuan.core.model.GenerationStageStatus.PREPARING ||
            lease.jobLeaseToken.ownerId != lease.stageLeaseToken.ownerId
        ) {
            throw StaleGenerationStateException("Bound $label execution snapshot is invalid.")
        }
        val dao = database.generationDao()
        val stage = requireNotNull(dao.findStage(lease.stageId)) {
            "Bound $label Stage is missing."
        }
        val job = requireNotNull(dao.findJob(lease.jobId)) {
            "Bound $label Job is missing."
        }
        val jobHeartbeatAt = job.leaseHeartbeatAt
            ?: throw StaleGenerationStateException("Bound $label Job heartbeat is missing.")
        val stageHeartbeatAt = stage.leaseHeartbeatAt
            ?: throw StaleGenerationStateException("Bound $label Stage heartbeat is missing.")
        if (
            job.jobId != lease.jobId ||
            stage.stageId != lease.stageId ||
            stage.jobId != job.jobId ||
            job.status != GenerationJobStatus.RUNNING ||
            stage.status != app.zhijuan.core.model.GenerationStageStatus.PREPARING ||
            job.currentStageId != stage.stageId ||
            job.pauseOrStopReason != null ||
            job.leaseTokenOrNull() != lease.jobLeaseToken ||
            stage.leaseTokenOrNull() != lease.stageLeaseToken ||
            jobHeartbeatAt < lease.jobHeartbeatAt ||
            stageHeartbeatAt < lease.stageHeartbeatAt ||
            stage.attemptCount != snapshot.attemptCount ||
            stage.maxAttempts != snapshot.maxAttempts ||
            stage.attemptCount !in 0 until stage.maxAttempts ||
            GenerationRunnerStageRouteResolver.resolve(stage) != snapshot.route
        ) {
            throw StaleGenerationStateException("Bound $label execution evidence changed.")
        }
        require(
            draft.createdAt >= job.updatedAt &&
                draft.createdAt >= stage.updatedAt &&
                draft.createdAt >= jobHeartbeatAt &&
                draft.createdAt >= stageHeartbeatAt,
        ) { "Bound $label request time cannot move backwards." }
        if (
            leasePolicy.isExpired(jobHeartbeatAt, draft.createdAt) ||
            leasePolicy.isExpired(stageHeartbeatAt, draft.createdAt)
        ) {
            throw StaleGenerationStateException("Bound $label execution lease expired.")
        }
    }

    private fun requiresBoundRunnerExecution(stage: GenerationStageEntity): Boolean {
        val root = runCatching {
            BOUND_ROUTE_JSON.parseToJsonElement(stage.inputSourcesJson) as? JsonObject
        }.getOrNull()
        if (stage.phase == GenerationPhase.BUILD_CHAPTER_PLAN) {
            return root == null || "firstChapterBootstrap" !in root
        }
        return stage.phase == GenerationPhase.DRAFT_CHAPTER &&
            root?.stringOrNull("sourcePolicyVersion") == InitialChapterDraftStageBinding.SOURCE_POLICY_VERSION
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf(kotlinx.serialization.json.JsonPrimitive::isString)?.contentOrNull

private val BOUND_ROUTE_JSON = Json { isLenient = false }

internal object RequestIntentDraftPolicy {
    private const val MAX_SNAPSHOT_CHARS = 65_536
    private const val MAX_REFERENCE_CHARS = 1_024
    private val identifier = Regex("[A-Za-z0-9._:-]{1,128}")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val protectedArtifactRef = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )
    private val strictJson = Json {
        isLenient = false
    }
    private val forbiddenNormalizedKeys = setOf(
        "apikey",
        "xapikey",
        "xgoogapikey",
        "authorization",
        "password",
        "cookie",
        "setcookie",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "clientsecret",
        "credential",
        "secret",
    )

    fun validate(draft: RequestIntentDraft) {
        require(identifier.matches(draft.attemptId)) { "Attempt id is invalid." }
        require(identifier.matches(draft.usageLedgerId)) { "Usage ledger id is invalid." }
        require(identifier.matches(draft.stageId)) { "Stage id is invalid." }
        require(draft.retryParentAttemptId == null || identifier.matches(draft.retryParentAttemptId)) {
            "Retry parent attempt id is invalid."
        }
        require(sha256.matches(draft.inputHash)) { "Input hash must be lowercase SHA-256." }
        require(draft.createdAt >= 0L) { "Request intent time must not be negative." }
        require(
            draft.streamDraftRef != null &&
                draft.streamDraftRef.length <= MAX_REFERENCE_CHARS &&
                protectedArtifactRef.matches(draft.streamDraftRef),
        ) {
            "Stream draft reference must be a protected artifact UUID."
        }
        validateSnapshot("Connection", draft.connectionSnapshotJson)
        validateSnapshot("Model", draft.modelSnapshotJson)
        validateSnapshot("Protocol", draft.protocolSnapshotJson)
    }

    private fun validateSnapshot(label: String, json: String) {
        require(json.isNotBlank() && json.length <= MAX_SNAPSHOT_CHARS) {
            "$label snapshot is empty or too large."
        }
        val parsed = try {
            strictJson.parseToJsonElement(json)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("$label snapshot must be valid JSON.")
        }
        require(parsed is JsonObject) { "$label snapshot must be a JSON object." }
        require(!parsed.containsForbiddenSecretKey()) {
            "$label snapshot contains a forbidden secret-bearing field."
        }
    }

    private fun JsonElement.containsForbiddenSecretKey(): Boolean = when (this) {
        is JsonObject -> entries.any { (key, value) ->
            key.lowercase().filter(Char::isLetterOrDigit) in forbiddenNormalizedKeys ||
                value.containsForbiddenSecretKey()
        }
        is JsonArray -> any { it.containsForbiddenSecretKey() }
        else -> false
    }
}

private fun RequestIntentDraft.toInternal() = NewRequestIntent(
    attemptId = attemptId,
    usageLedgerId = usageLedgerId,
    stageId = stageId,
    retryParentAttemptId = retryParentAttemptId,
    connectionSnapshotJson = connectionSnapshotJson,
    modelSnapshotJson = modelSnapshotJson,
    protocolSnapshotJson = protocolSnapshotJson,
    inputHash = inputHash,
    streamDraftRef = streamDraftRef,
    // Non-sensitive placeholder only: the budgeted reservation core derives
    // the canonical daily key from the active DAILY policy before any write,
    // and this value never reaches the database.
    dailyPeriodKey = "policy-derived",
    createdAt = createdAt,
)

private fun RequestAttemptEntity.toStoredAudit() = StoredRequestAttemptAudit(
    attemptId = attemptId,
    jobId = jobId,
    stageId = stageId,
    attemptNo = attemptNo,
    status = status,
    requestIntentAt = requestIntentAt,
    sentAt = sentAt,
    finishedAt = finishedAt,
    retryParentAttemptId = retryParentAttemptId,
    updatedAt = updatedAt,
)

private fun UsageLedgerEntity.toStoredAudit() = StoredUsageLedgerAudit(
    usageLedgerId = usageLedgerId,
    attemptId = attemptId,
    bookId = bookId,
    source = source,
    status = status,
    totalTokens = totalTokens,
    estimatedCostMicros = estimatedCostMicros,
    finalizedAt = finalizedAt,
    updatedAt = updatedAt,
)
