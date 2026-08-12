package app.zhijuan.core.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GenerationStartContractTest {
    @Test
    fun `request requires frozen destination and positive explicit token limits`() {
        val request = request()
        assertEquals("book-1", request.bookId)
        assertThrows<IllegalArgumentException> {
            request().copy(creationSnapshotContentHash = "stale")
        }
        assertThrows<IllegalArgumentException> {
            budget().copy(priceUnknownAccepted = false)
        }
        assertThrows<IllegalArgumentException> {
            budget().copy(bookTokenHardLimit = 10L)
        }
    }

    @Test
    fun `diagnostic strings redact identities destinations models and limits`() {
        val request = request()
        val result = GenerationStartResult.Started("book-1", "job-1", replayed = true)
        listOf(
            "book-1",
            "snapshot-1",
            "connection-1",
            "deepseek-chat",
            "api.example.com",
            "1000000",
        ).forEach { secret ->
            assertFalse(request.toString().contains(secret))
            assertFalse(request.budget.toString().contains(secret))
            assertFalse(result.toString().contains(secret))
        }
    }

    private fun request() = GenerationStartRequest(
        bookId = "book-1",
        creationSnapshotId = "snapshot-1",
        creationSnapshotContentHash = "a".repeat(64),
        connectionId = "connection-1",
        modelId = "deepseek-chat",
        normalizedDestination = "https://api.example.com:443",
        destinationProtocolId = "OPENAI_CHAT_COMPAT",
        destinationDisclosureVersion = 1,
        destinationBindingHash = "b".repeat(64),
        budget = budget(),
        confirmedAt = 1L,
    )

    private fun budget() = GenerationBudgetConfirmation(
        requestTokenHardLimit = 64_000L,
        bookTokenHardLimit = 1_000_000L,
        dailyTokenHardLimit = 1_000_000L,
        dailyZoneId = "Asia/Shanghai",
        priceUnknownAccepted = true,
    )
}

