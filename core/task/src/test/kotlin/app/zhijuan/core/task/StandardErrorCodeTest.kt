package app.zhijuan.core.task

import app.zhijuan.core.model.RetryDisposition
import app.zhijuan.core.model.StandardErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StandardErrorCodeTest {
    @Test
    fun `policy refusal is never automatically retried`() {
        assertEquals(
            RetryDisposition.NEVER_AUTOMATICALLY,
            StandardErrorCode.POLICY_REFUSAL.retryDisposition,
        )
    }

    @Test
    fun `unknown result requires user confirmation`() {
        assertEquals(
            RetryDisposition.USER_CONFIRMATION_REQUIRED,
            StandardErrorCode.UNKNOWN_RESULT.retryDisposition,
        )
    }

    @Test
    fun `rate limit allows only bounded retry handling`() {
        assertEquals(
            RetryDisposition.LIMITED_AUTOMATIC_RETRY,
            StandardErrorCode.RATE_LIMITED.retryDisposition,
        )
    }

    @Test
    fun `local credential failure is not confused with a retryable provider error`() {
        assertEquals(
            RetryDisposition.NEVER_AUTOMATICALLY,
            StandardErrorCode.CREDENTIAL_UNAVAILABLE.retryDisposition,
        )
    }
}
