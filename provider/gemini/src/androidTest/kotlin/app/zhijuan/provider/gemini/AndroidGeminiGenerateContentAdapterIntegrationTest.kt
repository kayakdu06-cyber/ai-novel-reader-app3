package app.zhijuan.provider.gemini

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.provider.common.GenerationParameters
import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderFinishReason
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderProtocol
import app.zhijuan.provider.common.ProviderStreamEvent
import app.zhijuan.provider.common.ProviderTimeoutPolicy
import app.zhijuan.provider.common.SensitiveProviderText
import app.zhijuan.provider.transport.ProviderSecretMaterialSource
import app.zhijuan.provider.transport.ProviderSecretPurpose
import app.zhijuan.provider.transport.SecureProviderHttpTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGeminiGenerateContentAdapterIntegrationTest {
    @Test
    fun privateHeaderHttpsStreamSurvivesUtf8ByteFragmentationOnAndroid() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer()
        try {
            server.useHttps(serverCertificates.sslSocketFactory())
            server.start()
            val expected = "\u5b89\u5353\u6d41\u5f0f"
            val stream =
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"hidden\"},{\"text\":\"$expected\"}]},\"index\":0}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":4,\"totalTokenCount\":9}}\n\n"
            server.enqueue(
                MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(Buffer().writeUtf8(stream))
                    .throttleBody(2, 1, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
            val adapter = GeminiGenerateContentAdapter(
                SecureProviderHttpTransport(
                    secretSource = FakeSecretSource(),
                    baseClient = client,
                ),
            )
            val profile = ProviderConnectionProfile.create(
                connectionId = "android-gemini-025",
                protocol = ProviderProtocol.GEMINI_GENERATE_CONTENT,
                baseUrl = server.url("/v1beta").toString(),
                primarySecretRefId = SECRET_REF,
            )

            val events = adapter.generate(profile, request()).toList()

            val text = events.filterIsInstance<ProviderStreamEvent.TextDelta>()
                .joinToString("") { it.text.withValue { value -> value } }
            assertEquals(expected, text)
            assertFalse(events.toString().contains("hidden"))
            assertFalse(events.any { it is ProviderStreamEvent.Failed })
            assertEquals(1, events.count { it is ProviderStreamEvent.UsageUpdate })
            assertEquals(ProviderFinishReason.STOP, events.filterIsInstance<ProviderStreamEvent.Completed>().single().reason)
            val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/v1beta/models/android-test-model:streamGenerateContent?alt=sse", recorded.target)
            assertEquals(TEST_CREDENTIAL, recorded.headers["x-goog-api-key"])
            assertEquals(null, recorded.url.queryParameter("key"))
            val requestBody = requireNotNull(recorded.body).utf8()
            assertTrue(requestBody.contains("\"maxOutputTokens\":512"))
            assertTrue(requestBody.contains("\"role\":\"user\""))
            assertTrue(requestBody.contains("\"store\":false"))
        } finally {
            server.close()
        }
    }

    private fun request() = GenerationRequest(
        requestId = "android-gemini-request-025",
        generationId = "android-generation-025",
        stageId = "android-stage-025",
        attemptId = "android-attempt-025",
        modelId = ProviderModelId.from("android-test-model"),
        prompt = ProviderPrompt(
            listOf(PromptPart(PromptLayer.STAGE_CONTRACT, SensitiveProviderText.from("Generate test text."))),
        ),
        parameters = GenerationParameters(maxOutputTokens = 512),
        structuredOutputSchema = null,
        stream = true,
        timeouts = ProviderTimeoutPolicy(1_000, 10_000, 5_000, 30_000),
        idempotencyKey = null,
    )

    private class FakeSecretSource : ProviderSecretMaterialSource {
        override fun <T> withSecret(
            secretRefId: String,
            purpose: ProviderSecretPurpose,
            now: Long,
            block: (ByteArray) -> T,
        ): T {
            require(secretRefId == SECRET_REF)
            val bytes = TEST_CREDENTIAL.toByteArray()
            return try { block(bytes) } finally { bytes.fill(0) }
        }
    }

    private companion object {
        const val SECRET_REF = "123e4567-e89b-42d3-a456-426614174025"
        const val TEST_CREDENTIAL = "ZHIJUAN_ANDROID_GEMINI_CREDENTIAL_025"
    }
}
