package app.zhijuan.core.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StreamingDraftPolicyTest {
    @Test
    fun `default policy uses bounded two second and thirty two kibibyte checkpoints`() {
        val policy = StreamingDraftPolicy()

        assertEquals(2_000L, policy.checkpointIntervalMillis)
        assertEquals(32 * 1_024, policy.checkpointPendingBytes)
        assertEquals(4 * 1_024 * 1_024, policy.maximumPlaintextBytes)
    }

    @Test
    fun `invalid intervals thresholds and maximum sizes fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingDraftPolicy(checkpointIntervalMillis = 99)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingDraftPolicy(checkpointPendingBytes = 1_023)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingDraftPolicy(
                checkpointPendingBytes = 2_048,
                maximumPlaintextBytes = 1_024,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingDraftPolicy(maximumPlaintextBytes = 4 * 1_024 * 1_024 + 1)
        }
    }
}
