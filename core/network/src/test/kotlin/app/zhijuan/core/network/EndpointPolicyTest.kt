package app.zhijuan.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndpointPolicyTest {
    private val policy = EndpointPolicy()

    @Test
    fun `https remote endpoint is accepted and normalized`() {
        val endpoint = policy.validateBaseUrl("  https://API.Example.com:443/v1/  ")

        assertEquals("https://api.example.com/v1/", endpoint.url.toString())
        assertFalse(endpoint.isExplicitLocalCleartext)
    }

    @Test
    fun `remote cleartext is rejected even when local cleartext is enabled`() {
        val error = assertThrows(EndpointRejectedException::class.java) {
            policy.validateBaseUrl("http://api.example.com/v1", allowExplicitLocalCleartext = true)
        }

        assertEquals(EndpointRejectionReason.CLEARTEXT_REMOTE, error.reason)
    }

    @Test
    fun `local cleartext requires explicit confirmation`() {
        val error = assertThrows(EndpointRejectedException::class.java) {
            policy.validateBaseUrl("http://127.0.0.1:11434")
        }
        val accepted = policy.validateBaseUrl(
            "http://127.0.0.1:11434",
            allowExplicitLocalCleartext = true,
        )

        assertEquals(EndpointRejectionReason.CLEARTEXT_LOCAL_NOT_CONFIRMED, error.reason)
        assertTrue(accepted.isExplicitLocalCleartext)
    }

    @Test
    fun `private LAN literal can be explicitly confirmed but hostname cannot bypass remote policy`() {
        assertTrue(
            policy.validateBaseUrl("http://192.168.1.20:11434", true).isExplicitLocalCleartext,
        )
        val error = assertThrows(EndpointRejectedException::class.java) {
            policy.validateBaseUrl("http://ollama.internal:11434", true)
        }
        assertEquals(EndpointRejectionReason.CLEARTEXT_REMOTE, error.reason)
    }

    @Test
    fun `credentials query and fragment are rejected in base URL`() {
        assertEquals(
            EndpointRejectionReason.EMBEDDED_CREDENTIALS,
            assertThrows(EndpointRejectedException::class.java) {
                policy.validateBaseUrl("https://user:password@example.com/v1")
            }.reason,
        )
        assertEquals(
            EndpointRejectionReason.BASE_URL_QUERY,
            assertThrows(EndpointRejectedException::class.java) {
                policy.validateBaseUrl("https://example.com/v1?key=secret")
            }.reason,
        )
        assertEquals(
            EndpointRejectionReason.BASE_URL_FRAGMENT,
            assertThrows(EndpointRejectedException::class.java) {
                policy.validateBaseUrl("https://example.com/v1#fragment")
            }.reason,
        )
    }

    @Test
    fun `request URL may contain non-secret query but never embedded credentials`() {
        val valid = policy.validateRequestUrl("https://example.com/models?page=2".toHttpUrl())
        assertEquals("page=2", valid.url.query)

        assertThrows(EndpointRejectedException::class.java) {
            policy.validateRequestUrl("https://user:password@example.com/v1".toHttpUrl())
        }
        assertEquals(
            EndpointRejectionReason.REQUEST_URL_FRAGMENT,
            assertThrows(EndpointRejectedException::class.java) {
                policy.validateRequestUrl("https://example.com/v1#ignored".toHttpUrl())
            }.reason,
        )
    }
}
