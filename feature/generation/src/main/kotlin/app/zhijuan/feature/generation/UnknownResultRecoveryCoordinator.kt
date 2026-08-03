package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.FinalUsageCommit
import app.zhijuan.core.database.generation.GenerationLeaseToken
import app.zhijuan.core.database.generation.GenerationRecoveryProbe
import app.zhijuan.core.database.generation.GenerationRecoveryResult
import app.zhijuan.core.database.generation.GenerationStreamingDraftRepository
import app.zhijuan.core.model.UsageSource
import app.zhijuan.core.task.ProviderRecoveryEvidence
import app.zhijuan.provider.common.ProviderAdapter
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderRemoteRequestId
import app.zhijuan.provider.common.ProviderRequestRecoveryCapability
import app.zhijuan.provider.common.ProviderRequestRecoveryResult
import app.zhijuan.provider.common.ProviderUsage
import app.zhijuan.provider.common.ProviderUsageQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The coordinator may query an existing provider request, but it never invokes generate().
 */
class UnknownResultRecoveryCoordinator(
    private val drafts: GenerationStreamingDraftRepository,
    private val clock: GenerationExecutionClock = SystemGenerationExecutionClock,
    private val queryTimeoutMillis: Long = 15_000L,
) {
    init {
        require(queryTimeoutMillis in 1_000L..60_000L) {
            "Provider recovery query timeout must be between 1 and 60 seconds."
        }
    }

    suspend fun auditExpiredAttempt(
        attemptId: String,
        observedLease: GenerationLeaseToken,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
    ): GenerationRecoveryResult {
        val probe = drafts.inspectRecovery(attemptId, now())
        val provider = queryExistingRequest(probe, adapter, profile)
        val auditedAt = now()
        return drafts.auditExpiredAttempt(
            attemptId = attemptId,
            observedLease = observedLease,
            providerEvidence = provider.toEvidence(),
            providerUsage = provider.toFinalUsageCommitOrNull(),
            auditedAt = auditedAt,
        )
    }

    suspend fun reconcilePendingAttempt(
        attemptId: String,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
    ): GenerationRecoveryResult {
        val probe = drafts.inspectRecovery(attemptId, now())
        val provider = queryExistingRequest(probe, adapter, profile)
        val auditedAt = now()
        return drafts.reconcilePendingAttempt(
            attemptId = attemptId,
            providerEvidence = provider.toEvidence(),
            providerUsage = provider.toFinalUsageCommitOrNull(),
            auditedAt = auditedAt,
        )
    }

    suspend fun confirmRetry(attemptId: String): GenerationRecoveryResult =
        drafts.confirmUnknownResultRetry(attemptId, now())

    private suspend fun queryExistingRequest(
        probe: GenerationRecoveryProbe,
        adapter: ProviderAdapter,
        profile: ProviderConnectionProfile,
    ): ProviderRequestRecoveryResult {
        if (adapter.requestRecoveryCapability != ProviderRequestRecoveryCapability.STATUS_QUERY) {
            return ProviderRequestRecoveryResult.NotSupported
        }
        val reference = probe.providerRequestReference
            ?: return ProviderRequestRecoveryResult.Inconclusive
        val remoteId = reference.withValue { value ->
            runCatching { ProviderRemoteRequestId.from(value) }.getOrNull()
        } ?: return ProviderRequestRecoveryResult.Inconclusive
        return try {
            withTimeoutOrNull(queryTimeoutMillis) {
                adapter.queryRequestRecovery(profile, remoteId)
            } ?: ProviderRequestRecoveryResult.Inconclusive
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ProviderRequestRecoveryResult.Inconclusive
        }
    }

    private fun ProviderRequestRecoveryResult.toEvidence(): ProviderRecoveryEvidence = when (this) {
        ProviderRequestRecoveryResult.NotSupported -> ProviderRecoveryEvidence.NOT_AVAILABLE
        ProviderRequestRecoveryResult.Inconclusive -> ProviderRecoveryEvidence.INCONCLUSIVE
        ProviderRequestRecoveryResult.InProgress -> ProviderRecoveryEvidence.IN_PROGRESS
        ProviderRequestRecoveryResult.ConfirmedNotExecuted ->
            ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED
        is ProviderRequestRecoveryResult.CompletedWithoutLocalOutput ->
            ProviderRecoveryEvidence.COMPLETED_WITHOUT_LOCAL_OUTPUT
    }

    private fun ProviderRequestRecoveryResult.toFinalUsageCommitOrNull(): FinalUsageCommit? =
        (this as? ProviderRequestRecoveryResult.CompletedWithoutLocalOutput)
            ?.usage
            ?.toFinalUsageCommit()

    private fun ProviderUsage.toFinalUsageCommit(): FinalUsageCommit {
        if (quality == ProviderUsageQuality.UNKNOWN) return FinalUsageCommit.UNKNOWN
        val derivedTotal = totalTokens ?: addOrNull(inputTokens, outputTokens)
        if (derivedTotal == null) return FinalUsageCommit.UNKNOWN
        val exactProviderTotal = totalTokens != null && quality == ProviderUsageQuality.PROVIDER_REPORTED
        return FinalUsageCommit(
            source = if (exactProviderTotal) UsageSource.PROVIDER_REPORTED else UsageSource.ESTIMATED,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedTokens = addOrNull(cachedInputTokens, cachedWriteTokens),
            reasoningTokens = reasoningTokens,
            totalTokens = derivedTotal,
        )
    }

    private fun addOrNull(first: Long?, second: Long?): Long? {
        if (first == null && second == null) return null
        return Math.addExact(first ?: 0L, second ?: 0L)
    }

    private fun now(): Long = clock.nowMillis().also { value ->
        require(value >= 0L) { "Generation recovery clock returned an invalid time." }
    }
}
