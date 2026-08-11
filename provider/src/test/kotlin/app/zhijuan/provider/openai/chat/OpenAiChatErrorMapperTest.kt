package app.zhijuan.provider.openai.chat

import app.zhijuan.core.model.StandardErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenAiChatErrorMapperTest {
    @Test
    fun `structured evidence takes priority over compatible HTTP fallbacks`() {
        val structuredCases = listOf(
            "context_length_exceeded" to StandardErrorCode.CONTEXT_TOO_LARGE,
            "model_not_found" to StandardErrorCode.MODEL_NOT_FOUND,
            "insufficient_quota" to StandardErrorCode.QUOTA_EXHAUSTED,
            "rate_limit_exceeded" to StandardErrorCode.RATE_LIMITED,
            "invalid_api_key" to StandardErrorCode.AUTH_FAILED,
            "content_filter" to StandardErrorCode.POLICY_REFUSAL,
        )
        structuredCases.forEach { (code, expected) ->
            val error = parseErrorObject("""{"error":{"code":"$code"}}""".encodeToByteArray())
            assertEquals(expected, OpenAiChatErrorMapper.map(418, error), code)
        }
    }

    @Test
    fun `HTTP fallbacks cover seven conservative categories`() {
        val statusCases = listOf(
            401 to StandardErrorCode.AUTH_FAILED,
            402 to StandardErrorCode.QUOTA_EXHAUSTED,
            429 to StandardErrorCode.RATE_LIMITED,
            503 to StandardErrorCode.SERVER_OVERLOADED,
            400 to StandardErrorCode.PROTOCOL_MISMATCH,
            404 to StandardErrorCode.PROTOCOL_MISMATCH,
            418 to StandardErrorCode.UNKNOWN_RESULT,
        )
        assertEquals(7, statusCases.size)
        statusCases.forEach { (status, expected) ->
            assertEquals(expected, OpenAiChatErrorMapper.map(status, null), status.toString())
        }
    }
}
