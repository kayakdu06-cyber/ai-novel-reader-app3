package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.connection.AcceptedDataDisclosureEvidence
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetReservationStatus
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import kotlinx.coroutines.CancellationException

/**
 * Redacted estimate draft for one budgeted request. Callers provide the
 * per-attempt reservation id, the per-request hard token limit and their own
 * token estimate; the repository derives the daily key and selects the active
 * policies itself. [toString] never expands ids, amounts, currency, destination
 * or hash.
 */
class RequestBudgetReservationDraft(
    val reservationId: String,
    val requestMaxTokens: Long,
    val requestMaxCostMicros: Long? = null,
    val requestCurrency: String? = null,
    val estimatedTokens: Long,
    val estimatedCostMicros: Long? = null,
    val estimatedCurrency: String? = null,
    val estimateSourceVersion: String? = null,
    val connectionId: String,
) {
    init {
        require(reservationId.isNotBlank()) { "Reservation id must not be blank." }
        require(connectionId.isNotBlank()) { "Connection id must not be blank." }
        require(requestMaxTokens > 0) { "Request max tokens must be positive." }
        require(estimatedTokens > 0) { "Estimated tokens must be positive." }
        require(requestMaxCostMicros == null || requestMaxCostMicros >= 0) {
            "Request max cost cannot be negative."
        }
        require((requestMaxCostMicros == null) == (requestCurrency == null)) {
            "Request max cost and currency must be provided together."
        }
        require(estimatedCostMicros == null || estimatedCostMicros >= 0) {
            "Estimated cost cannot be negative."
        }
        require((estimatedCostMicros == null) == (estimatedCurrency == null)) {
            "Estimated cost and currency must be provided together."
        }
        require(requestCurrency == null || requestCurrency.matches(Regex("[A-Z]{3}"))) {
            "Currency must be a three-letter uppercase code."
        }
        require(estimatedCurrency == null || estimatedCurrency.matches(Regex("[A-Z]{3}"))) {
            "Currency must be a three-letter uppercase code."
        }
    }

    override fun toString(): String = "RequestBudgetReservationDraft(redacted=true)"
}

/**
 * Limited rejection reasons exposed to callers. [BudgetReservationRejectedException]
 * never expands ids, amounts, currency, zone, destination or hash.
 */
internal enum class BudgetReservationRejectionReason {
    LIMIT_EXCEEDED,
    MONETARY_ESTIMATE_UNAVAILABLE,
    CURRENCY_MISMATCH,
    POLICY_UNAVAILABLE,
}

/**
 * Fail-closed rejection carrying only a limited scope and reason.
 */
internal class BudgetReservationRejectedException(
    val scope: BudgetScope,
    val reason: BudgetReservationRejectionReason,
) : RuntimeException("Budget reservation rejected: scope=$scope reason=$reason") {
    override fun toString(): String = "BudgetReservationRejectedException(scope=$scope, reason=$reason)"
}

/**
 * Redacted success result; [toString] never expands ids.
 */
internal class BudgetedRequestIntentResult internal constructor(
    val attemptId: String,
    val reservationId: String,
) {
    override fun toString(): String = "BudgetedRequestIntentResult(redacted=true)"
}

/**
 * Atomic reservation core for Phase 3A. One Room transaction performs, in
 * strict order: read the current BOOK/DAILY policy and accepted disclosure,
 * derive the canonical daily key, INSERT the RESERVED candidate (the first
 * budget write of the transaction), aggregate all non-released reservations
 * for the same book and daily key (including the candidate, across policy
 * revisions), enforce request/book/daily hard limits, then delegate to the
 * existing [GenerationDao.recordRequestIntent] with the derived key,
 * enforcement version 1 and the reservation id. Any rejection or later
 * failure rolls the whole transaction back, leaving no half state.
 */
internal class PersistentBudgetReservationRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    suspend fun recordBudgetedRequestIntent(
        intent: NewRequestIntent,
        budget: RequestBudgetReservationDraft,
        leaseToken: GenerationLeaseToken,
    ): BudgetedRequestIntentResult = recordBudgetedRequestIntentInternal(
        intent = intent,
        budget = budget,
        leaseToken = leaseToken,
        rolloverRequirement = null,
    )

    suspend fun recordDailyRolloverReplacementRequestIntent(
        intent: NewRequestIntent,
        budget: RequestBudgetReservationDraft,
        executionLease: GenerationRunnerExecutionLeaseSnapshot,
        parentAttemptId: String,
        sourceArtifactRefId: String,
    ): BudgetedRequestIntentResult = recordBudgetedRequestIntentInternal(
        intent = intent,
        budget = budget,
        leaseToken = executionLease.stageLeaseToken,
        rolloverRequirement = DailyRolloverReplacementRequirement(
            parentAttemptId = parentAttemptId,
            sourceArtifactRefId = sourceArtifactRefId,
            executionLease = executionLease,
        ),
    )

    private suspend fun recordBudgetedRequestIntentInternal(
        intent: NewRequestIntent,
        budget: RequestBudgetReservationDraft,
        leaseToken: GenerationLeaseToken,
        rolloverRequirement: DailyRolloverReplacementRequirement?,
    ): BudgetedRequestIntentResult = database.withTransaction {
        val budgetDao = database.budgetDao()
        val generationDao = database.generationDao()

        // 1. Stage/Job/Book are read only to select the current policy and to
        // build the candidate; final state/lease/retry validation stays in
        // recordRequestIntent.
        val stage = generationDao.findStage(intent.stageId)
            ?: reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        val job = generationDao.findJob(stage.jobId)
            ?: reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        val bookId = job.bookId
        val latestAttempt = generationDao.attemptsForStage(stage.stageId).lastOrNull()
        val rolloverParent = when {
            rolloverRequirement != null -> requireDailyRolloverReplacementParent(
                generationDao = generationDao,
                stage = stage,
                job = job,
                latestAttempt = latestAttempt,
                intent = intent,
                budget = budget,
                requirement = rolloverRequirement,
            )
            latestAttempt?.standardErrorCode ==
                StandardErrorCode.DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND -> {
                throw StaleGenerationStateException(
                    "A daily-rollover retry requires the dedicated replacement preparation path.",
                )
            }
            else -> null
        }

        // 2. Current BOOK head/revision; missing fails closed, no defaults.
        val bookHead = budgetDao.findHead(BudgetScope.BOOK, bookId)
            ?: reject(BudgetScope.BOOK, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        val bookRevision = budgetDao.findRevision(bookHead.currentBudgetPolicyId)
            ?: reject(BudgetScope.BOOK, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        if (bookRevision.bookId != bookId) {
            reject(BudgetScope.BOOK, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        }

        // 3. Current DAILY GLOBAL head/revision; missing fails closed and the
        // daily zone must be supported.
        val dailyHead = budgetDao.findHead(BudgetScope.DAILY, DAILY_POLICY_SCOPE_KEY)
            ?: reject(BudgetScope.DAILY, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        val dailyRevision = budgetDao.findRevision(dailyHead.currentBudgetPolicyId)
            ?: reject(BudgetScope.DAILY, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        val dailyZoneId = dailyRevision.dailyZoneId
            ?: reject(BudgetScope.DAILY, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        if (!BudgetDailyPeriodKeyV1.isSupportedZoneId(dailyZoneId)) {
            reject(BudgetScope.DAILY, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        }

        // 4. Dynamic disclosure evidence; caller destination/protocol/hash are
        // never trusted.
        val evidence: AcceptedDataDisclosureEvidence = try {
            database.connectionDao().readAcceptedDataDisclosureEvidence(budget.connectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        }

        // 5. Policy/head creation/update and disclosure acceptance must not
        // postdate the request.
        if (
            bookHead.updatedAt > intent.createdAt ||
            bookRevision.createdAt > intent.createdAt ||
            dailyHead.updatedAt > intent.createdAt ||
            dailyRevision.createdAt > intent.createdAt ||
            evidence.acceptedAt > intent.createdAt
        ) {
            reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.POLICY_UNAVAILABLE)
        }

        // 6. Canonical daily key derived from the daily policy zone and the
        // request time; any caller-provided key is ignored.
        val dailyPeriodKey = BudgetDailyPeriodKeyV1.create(intent.createdAt, dailyZoneId)

        rolloverParent?.let { parent ->
            require(parent.reservation.dailyPeriodKey != dailyPeriodKey) {
                "Daily rollover replacement must use a new daily period."
            }
            require(
                evidence.connectionId == parent.reservation.connectionId &&
                    evidence.normalizedDestination == parent.reservation.normalizedDestination &&
                    evidence.protocolId == parent.reservation.protocolId &&
                    evidence.disclosureVersion == parent.reservation.disclosureVersion &&
                    evidence.bindingHash == parent.reservation.disclosureBindingHash &&
                    evidence.acceptedAt >= parent.reservation.disclosureAcceptedAt,
            ) {
                "Daily rollover replacement connection evidence changed."
            }
        }

        // 8. The candidate INSERT is the first budget write of this
        // transaction; the aggregations below therefore include it.
        val candidate = RequestBudgetReservationEntity(
            budgetReservationId = budget.reservationId,
            attemptId = intent.attemptId,
            jobId = stage.jobId,
            stageId = stage.stageId,
            bookId = bookId,
            status = BudgetReservationStatus.RESERVED,
            requestMaxTokens = budget.requestMaxTokens,
            requestMaxCostMicros = budget.requestMaxCostMicros,
            requestCurrency = budget.requestCurrency,
            estimatedTokens = budget.estimatedTokens,
            estimatedCostMicros = budget.estimatedCostMicros,
            estimatedCurrency = budget.estimatedCurrency,
            estimateSourceVersion = budget.estimateSourceVersion,
            accountedTokens = budget.estimatedTokens,
            accountedCostMicros = budget.estimatedCostMicros,
            accountedCurrency = budget.estimatedCurrency,
            bookPolicyId = bookRevision.budgetPolicyId,
            dailyPolicyId = dailyRevision.budgetPolicyId,
            dailyPeriodKey = dailyPeriodKey,
            connectionId = budget.connectionId,
            normalizedDestination = evidence.normalizedDestination,
            protocolId = evidence.protocolId,
            disclosureVersion = evidence.disclosureVersion,
            disclosureBindingHash = evidence.bindingHash,
            disclosureAcceptedAt = evidence.acceptedAt,
            createdAt = intent.createdAt,
            updatedAt = intent.createdAt,
        )
        budgetDao.insertReservation(candidate)

        // 9. Aggregate all non-RELEASED reservations for the same book and
        // daily key, without filtering by policy revision.
        val bookAggregate = budgetDao.aggregateBookReservations(
            bookId = bookId,
            currency = bookRevision.currency,
            excludedStatus = BudgetReservationStatus.RELEASED,
        )
        val dailyAggregate = budgetDao.aggregateDailyReservations(
            dailyPeriodKey = dailyPeriodKey,
            currency = dailyRevision.currency,
            excludedStatus = BudgetReservationStatus.RELEASED,
        )

        // 10. Token hard limits. A NULL SUM means overflow or missing rows and
        // must fail closed instead of being treated as an empty balance.
        val bookTokens = bookAggregate?.tokens
            ?: reject(BudgetScope.BOOK, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        val dailyTokens = dailyAggregate?.tokens
            ?: reject(BudgetScope.DAILY, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        if (budget.estimatedTokens > budget.requestMaxTokens) {
            reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        }
        if (bookTokens > bookRevision.maxTokens) {
            reject(BudgetScope.BOOK, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        }
        if (dailyTokens > dailyRevision.maxTokens) {
            reject(BudgetScope.DAILY, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        }

        // 11. Monetary hard limits only when configured; every non-released
        // accounted cost in the scope must be non-null and use exactly the
        // limit currency, otherwise reject conservatively.
        checkRequestMonetaryLimit(budget, candidate)
        checkScopeMonetaryLimit(
            scope = BudgetScope.BOOK,
            limitCostMicros = bookRevision.maxCostMicros,
            limitCurrency = bookRevision.currency,
            aggregate = bookAggregate,
        )
        checkScopeMonetaryLimit(
            scope = BudgetScope.DAILY,
            limitCostMicros = dailyRevision.maxCostMicros,
            limitCurrency = dailyRevision.currency,
            aggregate = dailyAggregate,
        )

        // 13. Delegate to the existing intent recorder with the derived daily
        // key, enforcement version 1 and the reservation id. Any failure here
        // rolls the candidate back together with the rest of the transaction.
        val stageAttemptCount = stage.attemptCount
        generationDao.recordRequestIntent(
            intent.copy(
                dailyPeriodKey = dailyPeriodKey,
                budgetEnforcementVersion = 1,
                budgetReservationId = budget.reservationId,
            ),
            leaseToken,
        )

        // 14. Write-then-read-back: reservation, Attempt, Usage and Stage must
        // all exist and match before the redacted result is returned.
        val persisted = budgetDao.findReservation(budget.reservationId)
            ?: error("Budget reservation was not persisted.")
        check(persisted.status == BudgetReservationStatus.RESERVED) {
            "Budget reservation has an unexpected status."
        }
        check(persisted.attemptId == intent.attemptId) { "Budget reservation attempt mismatch." }
        check(persisted.jobId == stage.jobId && persisted.stageId == stage.stageId) {
            "Budget reservation stage mismatch."
        }
        check(persisted.accountedTokens == budget.estimatedTokens) {
            "Budget reservation accounted tokens mismatch."
        }
        check(persisted.accountedCostMicros == budget.estimatedCostMicros) {
            "Budget reservation accounted cost mismatch."
        }
        check(persisted.accountedCurrency == budget.estimatedCurrency) {
            "Budget reservation accounted currency mismatch."
        }
        check(persisted.dailyPeriodKey == dailyPeriodKey) { "Budget reservation daily key mismatch." }
        check(persisted.bookPolicyId == bookRevision.budgetPolicyId) {
            "Budget reservation book policy mismatch."
        }
        check(persisted.dailyPolicyId == dailyRevision.budgetPolicyId) {
            "Budget reservation daily policy mismatch."
        }
        check(persisted.normalizedDestination == evidence.normalizedDestination) {
            "Budget reservation destination mismatch."
        }
        check(persisted.protocolId == evidence.protocolId) { "Budget reservation protocol mismatch." }
        check(persisted.disclosureBindingHash == evidence.bindingHash) {
            "Budget reservation disclosure hash mismatch."
        }
        check(persisted.disclosureAcceptedAt == evidence.acceptedAt) {
            "Budget reservation disclosure time mismatch."
        }
        check(budgetDao.findReservationByAttempt(intent.attemptId)?.budgetReservationId == budget.reservationId) {
            "Budget reservation attempt lookup mismatch."
        }

        val attempt = generationDao.findAttempt(intent.attemptId)
            ?: error("Request attempt was not persisted.")
        check(attempt.budgetEnforcementVersion == 1) { "Attempt enforcement version mismatch." }
        check(attempt.budgetReservationId == budget.reservationId) {
            "Attempt reservation reference mismatch."
        }
        check(attempt.status == RequestAttemptStatus.INTENT_RECORDED) {
            "Attempt status mismatch."
        }

        val ledger = generationDao.findUsageLedger(intent.usageLedgerId)
            ?: error("Usage ledger was not persisted.")
        check(ledger.attemptId == intent.attemptId) { "Usage ledger attempt mismatch." }
        check(ledger.source == UsageSource.UNKNOWN) { "Usage ledger source mismatch." }
        check(ledger.status == UsageLedgerStatus.PROVISIONAL) { "Usage ledger status mismatch." }
        check(ledger.dailyPeriodKey == dailyPeriodKey) { "Usage ledger daily key mismatch." }

        val stageAfter = generationDao.findStage(intent.stageId)
            ?: error("Stage was not persisted.")
        check(stageAfter.status == GenerationStageStatus.REQUEST_INTENT_RECORDED) {
            "Stage status mismatch."
        }
        check(stageAfter.attemptCount == stageAttemptCount + 1) {
            "Stage attempt count mismatch."
        }
        rolloverParent?.let { parent ->
            check(attempt.retryParentAttemptId == parent.attempt.attemptId) {
                "Daily rollover replacement parent mismatch."
            }
            check(attempt.attemptNo == parent.attempt.attemptNo + 1) {
                "Daily rollover replacement attempt number mismatch."
            }
            check(attempt.streamDraftRef != parent.attempt.streamDraftRef) {
                "Daily rollover replacement reused the source artifact."
            }
            check(persisted.dailyPeriodKey != parent.reservation.dailyPeriodKey) {
                "Daily rollover replacement reused the released daily period."
            }
        }

        BudgetedRequestIntentResult(
            attemptId = intent.attemptId,
            reservationId = budget.reservationId,
        )
    }

    private suspend fun requireDailyRolloverReplacementParent(
        generationDao: GenerationDao,
        stage: GenerationStageEntity,
        job: GenerationJobEntity,
        latestAttempt: RequestAttemptEntity?,
        intent: NewRequestIntent,
        budget: RequestBudgetReservationDraft,
        requirement: DailyRolloverReplacementRequirement,
    ): DailyRolloverParentEvidence {
        val parent = latestAttempt
            ?: throw StaleGenerationStateException("Daily rollover replacement parent is missing.")
        val usage = generationDao.findUsageForAttempt(parent.attemptId)
            ?: throw StaleGenerationStateException("Daily rollover replacement usage is missing.")
        val reservation = generationDao.findBudgetReservationByAttempt(parent.attemptId)
            ?: throw StaleGenerationStateException("Daily rollover replacement reservation is missing.")
        val finishedAt = parent.finishedAt
            ?: throw StaleGenerationStateException("Daily rollover replacement parent is not terminal.")
        val streamDraftRef = parent.streamDraftRef
            ?: throw StaleGenerationStateException("Daily rollover replacement source artifact is missing.")
        val executionLease = requirement.executionLease
        val jobHeartbeatAt = job.leaseHeartbeatAt
            ?: throw StaleGenerationStateException("Daily rollover replacement Job heartbeat is missing.")
        val stageHeartbeatAt = stage.leaseHeartbeatAt
            ?: throw StaleGenerationStateException("Daily rollover replacement Stage heartbeat is missing.")

        if (
            requirement.parentAttemptId != parent.attemptId ||
            requirement.sourceArtifactRefId != streamDraftRef ||
            intent.retryParentAttemptId != parent.attemptId ||
            parent.stageId != stage.stageId ||
            parent.jobId != job.jobId ||
            parent.attemptNo != stage.attemptCount ||
            parent.status != RequestAttemptStatus.FAILED_RETRYABLE ||
            parent.standardErrorCode != StandardErrorCode.DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND ||
            parent.budgetEnforcementVersion != 1 ||
            parent.budgetReservationId != reservation.budgetReservationId ||
            parent.sentAt != null ||
            parent.providerRequestId != null ||
            parent.httpStatus != null ||
            parent.outputHash != null ||
            finishedAt != parent.updatedAt ||
            generationDao.attemptsForStreamDraft(streamDraftRef).singleOrNull()?.attemptId != parent.attemptId
        ) {
            throw StaleGenerationStateException("Daily rollover replacement parent evidence changed.")
        }
        if (
            usage.source != UsageSource.UNKNOWN ||
            usage.status != UsageLedgerStatus.FINAL ||
            usage.inputTokens != null ||
            usage.outputTokens != null ||
            usage.cachedTokens != null ||
            usage.reasoningTokens != null ||
            usage.totalTokens != null ||
            usage.currency != null ||
            usage.estimatedCostMicros != null ||
            usage.priceCatalogVersion != null ||
            usage.finalizedAt != finishedAt ||
            usage.updatedAt != finishedAt
        ) {
            throw StaleGenerationStateException("Daily rollover replacement usage evidence changed.")
        }
        if (
            reservation.attemptId != parent.attemptId ||
            reservation.jobId != job.jobId ||
            reservation.stageId != stage.stageId ||
            reservation.bookId != job.bookId ||
            reservation.dailyPeriodKey != usage.dailyPeriodKey ||
            reservation.status != BudgetReservationStatus.RELEASED ||
            reservation.accountedTokens != 0L ||
            reservation.accountedCostMicros != null ||
            reservation.accountedCurrency != null ||
            reservation.releasedAt != finishedAt ||
            reservation.settledAt != null ||
            reservation.updatedAt != finishedAt
        ) {
            throw StaleGenerationStateException("Daily rollover replacement reservation evidence changed.")
        }
        if (
            stage.status != GenerationStageStatus.PREPARING ||
            job.status != GenerationJobStatus.RUNNING ||
            job.currentStageId != stage.stageId ||
            job.pauseOrStopReason != null ||
            executionLease.jobId != job.jobId ||
            executionLease.stageId != stage.stageId ||
            executionLease.jobStatus != GenerationJobStatus.RUNNING ||
            executionLease.stageStatus != GenerationStageStatus.PREPARING ||
            executionLease.jobLeaseToken.ownerId != executionLease.stageLeaseToken.ownerId ||
            job.leaseTokenOrNull() != executionLease.jobLeaseToken ||
            stage.leaseTokenOrNull() != executionLease.stageLeaseToken ||
            jobHeartbeatAt < executionLease.jobHeartbeatAt ||
            stageHeartbeatAt < executionLease.stageHeartbeatAt
        ) {
            throw StaleGenerationStateException("Daily rollover replacement execution lease changed.")
        }
        require(
            intent.createdAt > finishedAt &&
                intent.createdAt >= job.updatedAt &&
                intent.createdAt >= stage.updatedAt &&
                intent.createdAt >= jobHeartbeatAt &&
                intent.createdAt >= stageHeartbeatAt,
        ) {
            "Daily rollover replacement time must advance all persisted evidence."
        }
        if (
            leasePolicy.isExpired(jobHeartbeatAt, intent.createdAt) ||
            leasePolicy.isExpired(stageHeartbeatAt, intent.createdAt)
        ) {
            throw StaleGenerationStateException("Daily rollover replacement execution lease expired.")
        }
        if (
            intent.attemptId == parent.attemptId ||
            intent.usageLedgerId == usage.usageLedgerId ||
            budget.reservationId == reservation.budgetReservationId ||
            intent.streamDraftRef == null ||
            intent.streamDraftRef == streamDraftRef ||
            generationDao.attemptsForStreamDraft(intent.streamDraftRef).isNotEmpty()
        ) {
            throw StaleGenerationStateException("Daily rollover replacement identities are not unique.")
        }
        if (
            intent.connectionSnapshotJson != parent.connectionSnapshotJson ||
            intent.modelSnapshotJson != parent.modelSnapshotJson ||
            intent.protocolSnapshotJson != parent.protocolSnapshotJson ||
            intent.inputHash != parent.inputHash ||
            budget.connectionId != reservation.connectionId ||
            budget.requestMaxTokens != reservation.requestMaxTokens ||
            budget.requestMaxCostMicros != reservation.requestMaxCostMicros ||
            budget.requestCurrency != reservation.requestCurrency ||
            budget.estimatedTokens != reservation.estimatedTokens ||
            budget.estimatedCostMicros != reservation.estimatedCostMicros ||
            budget.estimatedCurrency != reservation.estimatedCurrency ||
            budget.estimateSourceVersion != reservation.estimateSourceVersion
        ) {
            throw StaleGenerationStateException("Daily rollover replacement request evidence changed.")
        }
        return DailyRolloverParentEvidence(parent, usage, reservation)
    }

    private fun checkRequestMonetaryLimit(
        budget: RequestBudgetReservationDraft,
        candidate: RequestBudgetReservationEntity,
    ) {
        val limitCostMicros = budget.requestMaxCostMicros ?: return
        val limitCurrency = budget.requestCurrency ?: return
        if (candidate.accountedCostMicros == null || candidate.accountedCurrency == null) {
            reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.MONETARY_ESTIMATE_UNAVAILABLE)
        }
        if (candidate.accountedCurrency != limitCurrency) {
            reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.CURRENCY_MISMATCH)
        }
        if (candidate.accountedCostMicros > limitCostMicros) {
            reject(BudgetScope.REQUEST, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        }
    }

    private fun checkScopeMonetaryLimit(
        scope: BudgetScope,
        limitCostMicros: Long?,
        limitCurrency: String?,
        aggregate: BudgetReservationAggregate?,
    ) {
        if (limitCostMicros == null) return
        if (limitCurrency == null) return
        val row = aggregate
            ?: reject(scope, BudgetReservationRejectionReason.MONETARY_ESTIMATE_UNAVAILABLE)
        if (row.nullCostCount > 0) {
            reject(scope, BudgetReservationRejectionReason.MONETARY_ESTIMATE_UNAVAILABLE)
        }
        if (row.foreignCurrencyCount > 0) {
            reject(scope, BudgetReservationRejectionReason.CURRENCY_MISMATCH)
        }
        val cost = row.costMicros
            ?: reject(scope, BudgetReservationRejectionReason.MONETARY_ESTIMATE_UNAVAILABLE)
        if (cost > limitCostMicros) {
            reject(scope, BudgetReservationRejectionReason.LIMIT_EXCEEDED)
        }
    }

    private fun reject(
        scope: BudgetScope,
        reason: BudgetReservationRejectionReason,
    ): Nothing = throw BudgetReservationRejectedException(scope, reason)

    private class DailyRolloverReplacementRequirement(
        val parentAttemptId: String,
        val sourceArtifactRefId: String,
        val executionLease: GenerationRunnerExecutionLeaseSnapshot,
    ) {
        override fun toString(): String = "DailyRolloverReplacementRequirement(redacted=true)"
    }

    private class DailyRolloverParentEvidence(
        val attempt: RequestAttemptEntity,
        val usage: UsageLedgerEntity,
        val reservation: RequestBudgetReservationEntity,
    ) {
        override fun toString(): String = "DailyRolloverParentEvidence(redacted=true)"
    }
}
