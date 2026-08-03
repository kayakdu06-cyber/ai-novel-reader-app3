package app.zhijuan.core.task

import app.zhijuan.core.model.BudgetStatus
import app.zhijuan.core.model.FailureRequestState
import app.zhijuan.core.model.StandardErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalRequestRetryPolicyTest {
    @Test
    fun `thirteen remote failure classes have an explicit safe decision`() {
        val cases = listOf(
            Triple(StandardErrorCode.NETWORK_OFFLINE, FailureRequestState.NOT_SENT, ExternalRequestRetryDecision.WaitForCondition::class),
            Triple(StandardErrorCode.DNS_FAILED, FailureRequestState.NOT_SENT, ExternalRequestRetryDecision.RetryAfter::class),
            Triple(StandardErrorCode.TLS_FAILED, FailureRequestState.NOT_SENT, ExternalRequestRetryDecision.Stop::class),
            Triple(StandardErrorCode.AUTH_FAILED, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.Stop::class),
            Triple(StandardErrorCode.MODEL_NOT_FOUND, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.Stop::class),
            Triple(StandardErrorCode.PROTOCOL_MISMATCH, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.Stop::class),
            Triple(StandardErrorCode.RATE_LIMITED, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.RetryAfter::class),
            Triple(StandardErrorCode.QUOTA_EXHAUSTED, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.Stop::class),
            Triple(StandardErrorCode.SERVER_OVERLOADED, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.RetryAfter::class),
            Triple(StandardErrorCode.POLICY_REFUSAL, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.Stop::class),
            Triple(StandardErrorCode.CONTEXT_TOO_LARGE, FailureRequestState.PROVIDER_REJECTED, ExternalRequestRetryDecision.RepairOnce::class),
            Triple(StandardErrorCode.STREAM_INTERRUPTED, FailureRequestState.RESPONSE_STARTED, ExternalRequestRetryDecision.RetryAfter::class),
            Triple(StandardErrorCode.UNKNOWN_RESULT, FailureRequestState.RESULT_UNKNOWN, ExternalRequestRetryDecision.RequireUserConfirmation::class),
        )

        assertEquals(13, cases.size)
        cases.forEach { (code, state, decisionClass) ->
            assertEquals(decisionClass, evaluate(code, state)::class, code.name)
        }
    }

    @Test
    fun `exhausted budget stops every retry path`() {
        assertEquals(
            ExternalRequestRetryDecision.Stop(StandardErrorCode.BUDGET_EXCEEDED),
            evaluate(
                StandardErrorCode.RATE_LIMITED,
                requestState = FailureRequestState.PROVIDER_REJECTED,
                budgetStatus = BudgetStatus.EXHAUSTED,
            ),
        )
    }

    @Test
    fun `offline request known not sent waits without consuming retry`() {
        assertEquals(
            ExternalRequestRetryDecision.WaitForCondition(RetryCondition.NETWORK_AVAILABLE),
            evaluate(StandardErrorCode.NETWORK_OFFLINE, FailureRequestState.NOT_SENT),
        )
    }

    @Test
    fun `offline request with unknown delivery requires confirmation`() {
        assertCostConfirmation(
            evaluate(StandardErrorCode.NETWORK_OFFLINE, FailureRequestState.RESULT_UNKNOWN),
        )
    }

    @Test
    fun `dns failure retries only when request is known not sent`() {
        val decision = evaluate(
            StandardErrorCode.DNS_FAILED,
            requestState = FailureRequestState.NOT_SENT,
            retriesUsed = 1,
            jitterPermille = 750,
        ) as ExternalRequestRetryDecision.RetryAfter
        assertEquals(1_500, decision.delayMillis)
        assertFalse(decision.mayHaveIncurredCost)
    }

    @Test
    fun `dns failure after uncertain delivery requires confirmation`() {
        assertCostConfirmation(
            evaluate(StandardErrorCode.DNS_FAILED, FailureRequestState.RESULT_UNKNOWN),
        )
    }

    @Test
    fun `rate limit honors Retry After when provider explicitly rejected request`() {
        val decision = evaluate(
            StandardErrorCode.RATE_LIMITED,
            requestState = FailureRequestState.PROVIDER_REJECTED,
            retryAfterMillis = 12_000,
        ) as ExternalRequestRetryDecision.RetryAfter
        assertEquals(12_000, decision.delayMillis)
        assertFalse(decision.mayHaveIncurredCost)
    }

    @Test
    fun `server overload has bounded backoff after explicit rejection`() {
        val decision = evaluate(
            StandardErrorCode.SERVER_OVERLOADED,
            requestState = FailureRequestState.PROVIDER_REJECTED,
            retriesUsed = 2,
        ) as ExternalRequestRetryDecision.RetryAfter
        assertEquals(8_000, decision.delayMillis)
    }

    @Test
    fun `retryable status without rejection evidence is not automatically replayed`() {
        assertCostConfirmation(
            evaluate(StandardErrorCode.RATE_LIMITED, FailureRequestState.RESULT_UNKNOWN),
        )
    }

    @Test
    fun `stream interruption before content uses bounded retry and exposes cost risk`() {
        val decision = evaluate(
            StandardErrorCode.STREAM_INTERRUPTED,
            requestState = FailureRequestState.RESPONSE_STARTED,
        ) as ExternalRequestRetryDecision.RetryAfter
        assertEquals(1_000, decision.delayMillis)
        assertTrue(decision.mayHaveIncurredCost)
    }

    @Test
    fun `stream interruption after content requires confirmation`() {
        assertEquals(
            ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.CONTENT_ALREADY_RECEIVED,
            ),
            evaluate(
                StandardErrorCode.STREAM_INTERRUPTED,
                FailureRequestState.RESPONSE_STARTED,
                contentObserved = true,
            ),
        )
    }

    @Test
    fun `automatic retry count is capped at three`() {
        assertEquals(
            ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.AUTOMATIC_RETRY_LIMIT_REACHED,
            ),
            evaluate(
                StandardErrorCode.SERVER_OVERLOADED,
                FailureRequestState.PROVIDER_REJECTED,
                retriesUsed = ExternalRequestRetryPolicy.MAXIMUM_AUTOMATIC_RETRIES,
            ),
        )
    }

    @Test
    fun `retry delay cannot cross total wait budget`() {
        assertEquals(
            ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.RETRY_WAIT_LIMIT_REACHED,
            ),
            evaluate(
                StandardErrorCode.RATE_LIMITED,
                FailureRequestState.PROVIDER_REJECTED,
                retryAfterMillis = ExternalRequestRetryPolicy.MAXIMUM_RETRY_WAIT_MILLIS,
                retryWaitRemainingMillis = 1_000,
            ),
        )
    }

    @Test
    fun `context error performs one bounded context reduction`() {
        assertEquals(
            ExternalRequestRetryDecision.RepairOnce(
                kind = RepairKind.REDUCE_CONTEXT,
                mayHaveIncurredCost = false,
            ),
            evaluate(StandardErrorCode.CONTEXT_TOO_LARGE, FailureRequestState.PROVIDER_REJECTED),
        )
    }

    @Test
    fun `format error performs one repair and does not loop`() {
        assertEquals(
            ExternalRequestRetryDecision.RepairOnce(
                kind = RepairKind.REPAIR_FORMAT,
                mayHaveIncurredCost = true,
            ),
            evaluate(StandardErrorCode.FORMAT_INVALID, FailureRequestState.RESPONSE_STARTED),
        )
        assertEquals(
            ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.REPAIR_LIMIT_REACHED,
            ),
            evaluate(
                StandardErrorCode.FORMAT_INVALID,
                FailureRequestState.RESPONSE_STARTED,
                repairAttemptsUsed = 1,
            ),
        )
    }

    @Test
    fun `truncated output continues instead of replaying request`() {
        assertEquals(
            ExternalRequestRetryDecision.ContinueOutput,
            evaluate(
                StandardErrorCode.OUTPUT_TRUNCATED,
                FailureRequestState.RESPONSE_STARTED,
                contentObserved = true,
            ),
        )
    }

    @Test
    fun `authentication quota policy protocol and credential errors never retry`() {
        val terminalCodes = listOf(
            StandardErrorCode.TLS_FAILED,
            StandardErrorCode.AUTH_FAILED,
            StandardErrorCode.MODEL_NOT_FOUND,
            StandardErrorCode.PROTOCOL_MISMATCH,
            StandardErrorCode.QUOTA_EXHAUSTED,
            StandardErrorCode.POLICY_REFUSAL,
            StandardErrorCode.BUDGET_EXCEEDED,
            StandardErrorCode.CREDENTIAL_UNAVAILABLE,
        )
        terminalCodes.forEach { code ->
            assertEquals(
                ExternalRequestRetryDecision.Stop(code),
                evaluate(code, FailureRequestState.PROVIDER_REJECTED),
                code.name,
            )
        }
    }

    @Test
    fun `unknown result always exposes duplicate cost risk`() {
        assertCostConfirmation(
            evaluate(StandardErrorCode.UNKNOWN_RESULT, FailureRequestState.RESULT_UNKNOWN),
        )
    }

    private fun evaluate(
        code: StandardErrorCode,
        requestState: FailureRequestState,
        contentObserved: Boolean = false,
        retriesUsed: Int = 0,
        repairAttemptsUsed: Int = 0,
        retryWaitRemainingMillis: Long = ExternalRequestRetryPolicy.MAXIMUM_RETRY_WAIT_MILLIS,
        retryAfterMillis: Long? = null,
        budgetStatus: BudgetStatus = BudgetStatus.AVAILABLE,
        jitterPermille: Int = 1_000,
    ): ExternalRequestRetryDecision = ExternalRequestRetryPolicy.evaluate(
        ExternalRequestRetryContext(
            code = code,
            requestState = requestState,
            contentObserved = contentObserved,
            automaticRetriesUsed = retriesUsed,
            repairAttemptsUsed = repairAttemptsUsed,
            retryWaitRemainingMillis = retryWaitRemainingMillis,
            retryAfterMillis = retryAfterMillis,
            budgetStatus = budgetStatus,
            jitterPermille = jitterPermille,
        ),
    )

    private fun assertCostConfirmation(decision: ExternalRequestRetryDecision) {
        assertEquals(
            ExternalRequestRetryDecision.RequireUserConfirmation(
                RetryConfirmationReason.REQUEST_MAY_HAVE_INCURRED_COST,
            ),
            decision,
        )
    }
}
