package app.zhijuan.core.database.generation

import app.zhijuan.core.model.ProviderOpenDestinationEvidence
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationRequestAuditPolicyTest {
    @Test
    fun `safe snapshots allow secret references but reject secret bearing fields`() {
        RequestIntentDraftPolicy.validate(draft())
        RequestIntentDraftPolicy.validate(
            draft(connection = "{\"secretRefId\":\"safe-reference\"}"),
        )

        listOf(
            "{\"api_key\":\"forbidden\"}",
            "{\"Authorization\":\"forbidden\"}",
            "{\"x-goog-api-key\":\"forbidden\"}",
            "{\"headers\":{\"access_token\":\"forbidden\"}}",
            "{'password':'forbidden'}",
        ).forEach { snapshot ->
            expectFailure { RequestIntentDraftPolicy.validate(draft(connection = snapshot)) }
        }
    }

    @Test
    fun `invalid identifiers hashes sizes and times fail closed`() {
        expectFailure { RequestIntentDraftPolicy.validate(draft(attemptId = "has space")) }
        expectFailure { RequestIntentDraftPolicy.validate(draft(inputHash = "not-a-hash")) }
        expectFailure { RequestIntentDraftPolicy.validate(draft(createdAt = -1L)) }
        expectFailure { RequestIntentDraftPolicy.validate(draft(connection = "[]")) }
        expectFailure { RequestIntentDraftPolicy.validate(draft(connection = "not-json")) }
        expectFailure { RequestIntentDraftPolicy.validate(draft(streamDraftRef = null)) }
        expectFailure { RequestIntentDraftPolicy.validate(draft(streamDraftRef = "forged-path")) }
        expectFailure {
            RequestIntentDraftPolicy.validate(draft(connection = "x".repeat(65_537)))
        }
    }

    @Test
    fun `stream draft retention defaults are one day seven days and fail closed`() {
        val policy = StreamingDraftRetentionPolicy()

        assertEquals(24L * 60L * 60L * 1_000L, policy.committedSuccessMillis)
        assertEquals(7L * 24L * 60L * 60L * 1_000L, policy.unsuccessfulMillis)
        assertEquals(24L * 60L * 60L * 1_000L, policy.orphanMillis)
        expectFailure { StreamingDraftRetentionPolicy(committedSuccessMillis = 1L) }
        expectFailure {
            StreamingDraftRetentionPolicy(
                committedSuccessMillis = 7L * 24L * 60L * 60L * 1_000L,
                unsuccessfulMillis = 24L * 60L * 60L * 1_000L,
            )
        }
    }

    @Test
    fun `persisted send permit is single claim and redacts audit identifiers`() {
        val hash = "a".repeat(64)
        val permit = PersistedRequestSendPermit(
            attemptId = "attempt-secret",
            stageId = "stage-secret",
            attemptNo = 1,
            inputHash = hash,
            leaseToken = GenerationLeaseToken("worker-secret", 3L),
            intentRecordedAt = 3L,
            reservationId = "reservation-secret",
        )

        val destination = ProviderOpenDestinationEvidence.create(
            connectionId = "connection-secret",
            baseUrl = "https://destination-secret.example.invalid/v1",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val claimed = permit.claimAfterPersistedLeaseValidation(3L, destination)
        val rendered = permit.toString() + claimed.toString() + destination.toString()
        assertFalse(rendered.contains("attempt-secret"))
        assertFalse(rendered.contains("stage-secret"))
        assertFalse(rendered.contains("worker-secret"))
        assertFalse(rendered.contains("reservation-secret"))
        assertFalse(rendered.contains("connection-secret"))
        assertFalse(rendered.contains("destination-secret.example.invalid"))
        assertFalse(rendered.contains(hash))
        assertTrue(permit.toString().contains("claimed=true"))
        expectFailure { permit.claimAfterPersistedLeaseValidation(4L, destination) }
    }

    @Test
    fun `public intent draft and budget draft redact identifiers amounts and destinations`() {
        val intent = draft()
        val budget = RequestBudgetReservationDraft(
            reservationId = "reservation-secret",
            requestMaxTokens = 1_000_000L,
            requestMaxCostMicros = 123_456L,
            requestCurrency = "USD",
            estimatedTokens = 42L,
            estimatedCostMicros = 9_999L,
            estimatedCurrency = "USD",
            estimateSourceVersion = "zhijuan.estimate.v1",
            connectionId = "connection-secret",
        )

        val rendered = intent.toString() + budget.toString()
        assertFalse(rendered.contains("reservation-secret"))
        assertFalse(rendered.contains("connection-secret"))
        assertFalse(rendered.contains("123456"))
        assertFalse(rendered.contains("9999"))
        assertFalse(rendered.contains("USD"))
        assertFalse(rendered.contains("deepseek"))
        assertFalse(rendered.contains("policy-derived"))
        assertFalse(rendered.contains(hashValue))
    }

    private fun draft(
        attemptId: String = "attempt-1",
        inputHash: String = "a".repeat(64),
        connection: String = "{\"secretRefId\":\"secret-ref-1\"}",
        streamDraftRef: String? = "00000000-0000-0000-0000-000000000001",
        createdAt: Long = 3L,
    ) = RequestIntentDraft(
        attemptId = attemptId,
        usageLedgerId = "ledger-1",
        stageId = "stage-1",
        retryParentAttemptId = null,
        connectionSnapshotJson = connection,
        modelSnapshotJson = "{\"model\":\"fixture\"}",
        protocolSnapshotJson = "{\"protocol\":\"fixture\"}",
        inputHash = inputHash,
        streamDraftRef = streamDraftRef,
        createdAt = createdAt,
    )

    private val hashValue = "a".repeat(64)

    private fun expectFailure(block: () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }
}
