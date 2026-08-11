package app.zhijuan.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalDataDestinationBindingV1Test {
    @Test
    fun `equivalent urls share one canonical origin and hash`() {
        val first = ExternalDataDestinationBindingV1.create(
            baseUrl = " HTTPS://API.Example.COM/v1/ ",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val second = ExternalDataDestinationBindingV1.create(
            baseUrl = "https://api.example.com:443/another-path",
            protocolId = "OPENAI_CHAT_COMPAT",
        )

        assertEquals("https://api.example.com:443", first.normalizedDestination)
        assertEquals(first.normalizedDestination, second.normalizedDestination)
        assertEquals(first.bindingHash, second.bindingHash)
        assertFalse(first.toString().contains("api.example.com"))
        assertFalse(first.toString().contains(first.bindingHash))
    }

    @Test
    fun `port scheme and provider protocol change the binding`() {
        val default = binding("https://api.example.com", "OPENAI_CHAT_COMPAT")
        val otherPort = binding("https://api.example.com:8443", "OPENAI_CHAT_COMPAT")
        val cleartext = binding("http://api.example.com", "OPENAI_CHAT_COMPAT")
        val otherProtocol = binding("https://api.example.com", "OPENAI_RESPONSES")

        assertTrue(setOf(default, otherPort, cleartext, otherProtocol).size == 4)
    }

    @Test
    fun `stored evidence requires current version and exact canonical values`() {
        val binding = ExternalDataDestinationBindingV1.create(
            baseUrl = "https://api.example.com/v1",
            protocolId = "OPENAI_CHAT_COMPAT",
        )

        assertTrue(
            binding.matches(
                normalizedDestination = "https://api.example.com:443",
                protocolId = "OPENAI_CHAT_COMPAT",
                disclosureVersion = 1,
                bindingHash = binding.bindingHash,
            ),
        )
        assertFalse(
            binding.matches(
                normalizedDestination = "https://api.example.com:443",
                protocolId = "OPENAI_RESPONSES",
                disclosureVersion = 1,
                bindingHash = binding.bindingHash,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExternalDataDestinationBindingV1.create(
                baseUrl = "https://api.example.com",
                protocolId = "OPENAI_CHAT_COMPAT",
                disclosureVersion = 2,
            )
        }
    }

    @Test
    fun `credentials query fragment and invalid protocol fail closed`() {
        listOf(
            "https://user:pass@api.example.com",
            "https://api.example.com?token=value",
            "https://api.example.com/#fragment",
            "ftp://api.example.com",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                ExternalDataDestinationBindingV1.create(url, "OPENAI_CHAT_COMPAT")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalDataDestinationBindingV1.create("https://api.example.com", "bad protocol")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalDataDestinationBindingV1.requireValidStoredHash("not-a-hash")
        }
    }

    @Test
    fun `ipv6 and default ports canonicalize while invalid ports fail closed`() {
        val ipv6Upper = ExternalDataDestinationBindingV1.create(
            baseUrl = "https://[2001:DB8::1]:443/v1",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val ipv6Lower = ExternalDataDestinationBindingV1.create(
            baseUrl = "https://[2001:db8::1]",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        assertEquals("https://[2001:db8::1]:443", ipv6Upper.normalizedDestination)
        assertEquals(ipv6Upper.bindingHash, ipv6Lower.bindingHash)

        val httpDefault = ExternalDataDestinationBindingV1.create(
            baseUrl = "http://api.example.com",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val httpExplicit = ExternalDataDestinationBindingV1.create(
            baseUrl = "http://api.example.com:80/",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        assertEquals("http://api.example.com:80", httpDefault.normalizedDestination)
        assertEquals(httpDefault.bindingHash, httpExplicit.bindingHash)

        listOf(
            "https://api.example.com:0",
            "https://api.example.com:65536",
            "https://user@api.example.com",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                ExternalDataDestinationBindingV1.create(url, "OPENAI_CHAT_COMPAT")
            }
        }
    }

    @Test
    fun `fully qualified and bare domain share the same destination`() {
        val bare = ExternalDataDestinationBindingV1.create(
            baseUrl = "https://api.example.com",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val fullyQualified = ExternalDataDestinationBindingV1.create(
            baseUrl = "https://api.example.com.",
            protocolId = "OPENAI_CHAT_COMPAT",
        )

        assertEquals(bare.normalizedDestination, fullyQualified.normalizedDestination)
        assertEquals(bare.bindingHash, fullyQualified.bindingHash)
    }

    @Test
    fun `provider-open evidence canonicalizes paths and redacts every identity`() {
        val first = ProviderOpenDestinationEvidence.create(
            connectionId = "connection-sensitive-canary",
            baseUrl = "https://API.Example.com/v1",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val equivalent = ProviderOpenDestinationEvidence.create(
            connectionId = "connection-sensitive-canary",
            baseUrl = "https://api.example.com:443/other",
            protocolId = "OPENAI_CHAT_COMPAT",
        )
        val otherOrigin = ProviderOpenDestinationEvidence.create(
            connectionId = "connection-sensitive-canary",
            baseUrl = "https://api.example.com:8443",
            protocolId = "OPENAI_CHAT_COMPAT",
        )

        assertTrue(first.matches(equivalent))
        assertFalse(first.matches(otherOrigin))
        assertEquals("ProviderOpenDestinationEvidence(redacted=true)", first.toString())
        assertFalse(first.toString().contains("connection-sensitive-canary"))
        assertFalse(first.toString().contains("api.example.com"))
        assertFalse(first.toString().contains("OPENAI_CHAT_COMPAT"))
    }

    private fun binding(baseUrl: String, protocolId: String): String =
        ExternalDataDestinationBindingV1.create(baseUrl, protocolId).bindingHash
}
