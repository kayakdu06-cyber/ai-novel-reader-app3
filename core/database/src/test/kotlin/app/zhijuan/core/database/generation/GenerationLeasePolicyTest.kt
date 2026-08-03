package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationStageStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationLeasePolicyTest {
    @Test
    fun `lease expires exactly at the configured timeout`() {
        val policy = GenerationLeasePolicy(
            heartbeatIntervalMillis = 10L,
            timeoutMillis = 30L,
        )

        assertFalse(policy.isExpired(heartbeatAt = 100L, now = 129L))
        assertTrue(policy.isExpired(heartbeatAt = 100L, now = 130L))
    }

    @Test
    fun `invalid policy token and backwards time fail closed`() {
        expectFailure { GenerationLeasePolicy(heartbeatIntervalMillis = 0L, timeoutMillis = 30L) }
        expectFailure { GenerationLeasePolicy(heartbeatIntervalMillis = 10L, timeoutMillis = 29L) }
        expectFailure { GenerationLeaseToken(ownerId = "", acquiredAt = 1L) }
        expectFailure { GenerationLeaseToken(ownerId = "worker", acquiredAt = -1L) }
        expectFailure { GenerationLeasePolicy().isExpired(heartbeatAt = 10L, now = 9L) }
    }

    @Test
    fun `only pre request preparing work is safe to requeue automatically`() {
        assertTrue(GenerationStageStatus.PREPARING.canSafelyRequeueAfterLeaseExpiry())
        GenerationStageStatus.entries
            .filterNot { it == GenerationStageStatus.PREPARING }
            .forEach { status -> assertFalse(status.canSafelyRequeueAfterLeaseExpiry()) }
    }

    private fun expectFailure(block: () -> Unit): Throwable = try {
        block()
        throw AssertionError("Expected operation to fail.")
    } catch (error: Throwable) {
        if (error is AssertionError && error.message == "Expected operation to fail.") throw error
        error
    }
}
