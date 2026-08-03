package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.RequestAttemptStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnknownResultRecoveryPolicyTest {
    @Test
    fun `intent only remains ambiguous even with an empty draft`() {
        assertEquals(
            UnknownResultRecoveryDecision.REQUIRE_USER_RETRY_CONFIRMATION,
            evaluate(
                attempt = RequestAttemptStatus.INTENT_RECORDED,
                stage = GenerationStageStatus.REQUEST_INTENT_RECORDED,
            ),
        )
    }

    @Test
    fun `only provider proof plus consistent local evidence permits automatic requeue`() {
        assertEquals(
            UnknownResultRecoveryDecision.REQUEUE_PROVEN_NOT_EXECUTED,
            evaluate(
                attempt = RequestAttemptStatus.STREAMING,
                stage = GenerationStageStatus.STREAMING,
                sentAt = true,
                providerRequestId = true,
                provider = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
            ),
        )
    }

    @Test
    fun `content or usage contradicting not executed proof requires confirmation`() {
        listOf(
            RecoveryDraftEvidence.CONTENT_PRESENT to false,
            RecoveryDraftEvidence.READABLE_EMPTY to true,
            RecoveryDraftEvidence.MISSING_UNREADABLE_OR_CONFLICTING to false,
        ).forEach { (draft, usage) ->
            assertEquals(
                UnknownResultRecoveryDecision.REQUIRE_USER_RETRY_CONFIRMATION,
                evaluate(
                    attempt = RequestAttemptStatus.STREAMING,
                    stage = GenerationStageStatus.STREAMING,
                    sentAt = true,
                    providerRequestId = true,
                    draft = draft,
                    knownUsage = usage,
                    provider = ProviderRecoveryEvidence.CONFIRMED_NOT_EXECUTED,
                ),
            )
        }
    }

    @Test
    fun `remote in progress is reconciled without creating a request`() {
        assertEquals(
            UnknownResultRecoveryDecision.RECONCILE_WITHOUT_NEW_REQUEST,
            evaluate(
                attempt = RequestAttemptStatus.SENT,
                stage = GenerationStageStatus.STREAMING,
                sentAt = true,
                providerRequestId = true,
                provider = ProviderRecoveryEvidence.IN_PROGRESS,
            ),
        )
    }

    @Test
    fun `temporary provider lookup failure waits without replaying the request`() {
        assertEquals(
            UnknownResultRecoveryDecision.RECONCILE_WITHOUT_NEW_REQUEST,
            evaluate(
                attempt = RequestAttemptStatus.STREAMING,
                stage = GenerationStageStatus.RECOVERY_REQUIRED,
                sentAt = true,
                providerRequestId = true,
                provider = ProviderRecoveryEvidence.INCONCLUSIVE,
            ),
        )
    }

    @Test
    fun `remote completion without recoverable local output never authorizes replay`() {
        assertEquals(
            UnknownResultRecoveryDecision.REQUIRE_USER_RETRY_CONFIRMATION,
            evaluate(
                attempt = RequestAttemptStatus.STREAMING,
                stage = GenerationStageStatus.RECOVERY_REQUIRED,
                sentAt = true,
                providerRequestId = true,
                provider = ProviderRecoveryEvidence.COMPLETED_WITHOUT_LOCAL_OUTPUT,
            ),
        )
    }

    @Test
    fun `received response awaiting local commit is recovered locally`() {
        assertEquals(
            UnknownResultRecoveryDecision.RECOVER_LOCAL_RESULT_WITHOUT_NEW_REQUEST,
            evaluate(
                attempt = RequestAttemptStatus.SUCCEEDED,
                stage = GenerationStageStatus.COMMITTING,
                sentAt = true,
                providerRequestId = true,
                draft = RecoveryDraftEvidence.CONTENT_PRESENT,
            ),
        )
    }

    private fun evaluate(
        attempt: RequestAttemptStatus,
        stage: GenerationStageStatus,
        sentAt: Boolean = false,
        providerRequestId: Boolean = false,
        draft: RecoveryDraftEvidence = RecoveryDraftEvidence.READABLE_EMPTY,
        knownUsage: Boolean = false,
        provider: ProviderRecoveryEvidence = ProviderRecoveryEvidence.NOT_AVAILABLE,
    ) = UnknownResultRecoveryPolicy.evaluate(
        UnknownResultRecoveryContext(
            attemptStatus = attempt,
            stageStatus = stage,
            sentAtRecorded = sentAt,
            providerRequestIdRecorded = providerRequestId,
            draftEvidence = draft,
            knownUsageObserved = knownUsage,
            providerEvidence = provider,
        ),
    )
}
